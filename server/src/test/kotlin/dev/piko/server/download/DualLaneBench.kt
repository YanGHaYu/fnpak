package dev.piko.server.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.piko.shared.download.DirectDownloadProgress
import dev.piko.shared.log.PikoLog
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * 双直链 lane 的离线验收台（只编译进 test 源集，不随安装包发布）。
 *
 * 假 CDN 的规则对齐实测：同一 token（签名 URL）最多 8 条并发，第 9 条返回 503；
 * 每条连接限速约 400 KiB/s。用同一份文件跑三种场景，验证：
 * 1. 关闭开关 = 原行为（8 段、单 URL）；
 * 2. 开启开关且第二条签名不同 = 16 段、8+8，耗时明显低于单 lane；
 * 3. 开启开关但第二条仍是同一签名 = 自动放弃，回到单 lane，不报错。
 */
private const val TOTAL_BYTES = 32L * 1024 * 1024
private const val PER_TOKEN_LIMIT = 8

private class FakeCdn(private val source: File) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val active = ConcurrentHashMap<String, AtomicInteger>()
    private val maxActive = ConcurrentHashMap<String, AtomicInteger>()
    val rejected = AtomicInteger()

    fun peakActive(token: String): Int = maxActive[token]?.get() ?: 0

    val port: Int get() = server.address.port

    init {
        // 守护线程：main 结束后 JVM 能自行退出，不会把 Gradle 任务挂住
        server.executor = Executors.newFixedThreadPool(32) { runnable ->
            Thread(runnable, "fake-cdn").apply { isDaemon = true }
        }
        server.createContext("/dl") { exchange -> handle(exchange) }
    }

    fun start() = server.start()
    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val token = exchange.requestURI.rawQuery.orEmpty()
            .split('&').firstOrNull { it.startsWith("token=") }?.substringAfter("token=") ?: "none"
        if (token == "expired") {
            // 模拟第二 lane 的直链全程 403：应当刷新 3 次后放弃，由主 lane 续传，整体任务不能失败
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return
        }
        val counter = active.getOrPut(token) { AtomicInteger() }
        val now = counter.incrementAndGet()
        maxActive.getOrPut(token) { AtomicInteger() }.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        if (now > PER_TOKEN_LIMIT) {
            counter.decrementAndGet()
            rejected.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
            return
        }
        try {
            val total = source.length()
            val range = exchange.requestHeaders.getFirst("Range").orEmpty()
            val match = Regex("bytes=(\\d+)-(\\d+)").find(range)
            val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = match?.groupValues?.get(2)?.toLongOrNull() ?: (total - 1)
            val length = end - start + 1
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
            exchange.sendResponseHeaders(206, length)
            RandomAccessFile(source, "r").use { raf ->
                raf.seek(start)
                var remaining = length
                val buffer = ByteArray(8 * 1024)
                while (remaining > 0) {
                    // 先等再写：最后一包发完就立刻结束，不让「服务端还在收尾」的尾巴造成假并发
                    // 8 KiB / 20 ms ≈ 400 KiB/s，模拟单连接被 CDN 限速
                    Thread.sleep(20)
                    val n = minOf(buffer.size.toLong(), remaining).toInt()
                    raf.readFully(buffer, 0, n)
                    exchange.responseBody.write(buffer, 0, n)
                    exchange.responseBody.flush()
                    remaining -= n
                }
            }
        } finally {
            counter.decrementAndGet()
            exchange.close()
        }
    }
}

private fun writeSource(file: File) {
    RandomAccessFile(file, "rw").use { raf ->
        raf.setLength(TOTAL_BYTES)
        var offset = 0L
        while (offset < TOTAL_BYTES) {
            val chunk = ByteArray(64 * 1024) { i -> ((offset + i) % 251).toInt().toByte() }
            raf.write(chunk)
            offset += chunk.size
        }
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private suspend fun runOne(
    label: String,
    dual: Boolean,
    primaryUrl: String,
    secondUrl: String,
    out: File,
    segments: Int,
): Long {
    Files.deleteIfExists(out.toPath())
    val tuning = DownloadTuning(
        segments = segments,
        maxConnections = 32,
        dualSignedUrls = dual,
        downloadDiagnostics = true,
    )
    val primary: suspend (String) -> String = { primaryUrl }
    val second: suspend (String) -> String = { secondUrl }
    val downloader = SegmentDownloader(
        urlProvider = primary,
        secondUrlProvider = second,
        budget = ConnectionBudget(32),
        tuning = tuning,
    )
    val mark = TimeSource.Monotonic.markNow()
    try {
        downloader.download(
            fileId = "bench",
            fileName = out.name,
            targetPath = out.absolutePath,
            totalBytes = TOTAL_BYTES,
            completedRanges = emptyList(),
            onProgress = { _: DirectDownloadProgress -> },
            onCheckpoint = { _ -> },
        )
    } finally {
        downloader.close()
    }
    val elapsed = mark.elapsedNow().inWholeMilliseconds
    println("[dual-bench] $label：$elapsed ms，${out.length()} 字节")
    return elapsed
}

fun main() = runBlocking {
    val root = Files.createTempDirectory("fnpak-dual-bench").toFile()
    PikoLog.install(root.resolve("log").absolutePath) { level, tag, message, error ->
        val detail = error?.let { " | ${it::class.simpleName}: ${it.message}" }.orEmpty()
        println("[${level.letter}][$tag] $message$detail")
    }
    val source = root.resolve("source.bin").apply { writeSource(this) }
    val expected = sha256(source)
    val cdn = FakeCdn(source)
    cdn.start()
    println("[dual-bench] 临时目录 ${root.absolutePath}，假 CDN 端口 ${cdn.port}")

    val urlA = "http://127.0.0.1:${cdn.port}/dl?token=A&sig=aaa"
    val urlB = "http://127.0.0.1:${cdn.port}/dl?token=B&sig=bbb"
    try {
        val outSingle = root.resolve("single.bin")
        val single = runOne("关闭开关（单 lane）", dual = false, urlA, urlB, outSingle, segments = 8)
        check(sha256(outSingle) == expected) { "单 lane 输出与源文件不一致" }

        val outDual = root.resolve("dual.bin")
        val dual = runOne("开启开关（8+8 双 lane）", dual = true, urlA, urlB, outDual, segments = 8)
        check(sha256(outDual) == expected) { "双 lane 输出与源文件不一致" }

        val outSame = root.resolve("same.bin")
        val same = runOne("开启开关但第二条同签名", dual = true, urlA, urlA, outSame, segments = 8)
        check(sha256(outSame) == expected) { "同签名降级输出与源文件不一致" }

        val expiredUrl = "http://127.0.0.1:${cdn.port}/dl?token=expired&sig=ccc"
        val outExpired = root.resolve("expired.bin")
        val expired = runOne("第二 lane 全程 403 后降级", dual = true, urlA, expiredUrl, outExpired, segments = 8)
        check(sha256(outExpired) == expected) { "第二 lane 失效后输出与源文件不一致" }
        check(expired > 0) { "第二 lane 失效场景没有产生耗时" }

        println("[dual-bench] 单 lane ${single} ms / 双 lane ${dual} ms / 同签名降级 ${same} ms / " +
            "403 降级 ${expired} ms，假 CDN 的 503 次数 ${cdn.rejected.get()}")
        check(cdn.rejected.get() == 0) { "出现 503：${cdn.rejected.get()} 次，说明连接上限没被 gate 拦住" }
        check(cdn.peakActive("A") <= PER_TOKEN_LIMIT && cdn.peakActive("B") <= PER_TOKEN_LIMIT) {
            "单 URL 并发超过 $PER_TOKEN_LIMIT：A=${cdn.peakActive("A")}, B=${cdn.peakActive("B")}"
        }
        check(dual <= single * 0.8) { "双 lane 提速不足 20%：$dual vs $single" }
        check(same <= single * 1.15) { "同签名降级耗时异常：$same vs $single" }
        println("[dual-bench] 全部断言通过")
    } finally {
        cdn.stop()
        root.deleteRecursively()
    }
}
