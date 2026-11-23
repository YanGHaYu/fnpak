package dev.piko.server.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.piko.shared.download.DirectDownloadProgress
import dev.piko.shared.log.PikoLog
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * 离线掉速验收台：本地假 CDN 给每条连接限速，再按「完成度」采样聚合速度。
 * 对比旧分段数（factor=1）与细分后的分段数（factor=8）：前者尾部只剩 1 条连接，后者能跑满。
 *
 * 用法：`--panel-factor 1|8 --total 134217728 --rate 1048576 --tasks 1`
 * 结果只打印到 stdout，不写仓库文件。
 */
private const val MIB = 1024L * 1024L

fun main(args: Array<String>) = runBlocking {
    var factor = 8
    var total = 128L * MIB
    var rate = 1L * MIB
    var tasks = 1
    var slowEvery = 0
    var budgetMax = DownloadTuning.DEFAULT_MAX_CONNECTIONS
    var maxConns = 0
    var banMs = 0L
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--panel-factor" -> factor = args[++index].toInt()
            "--total" -> total = args[++index].toLong()
            "--rate" -> rate = args[++index].toLong()
            "--tasks" -> tasks = args[++index].toInt()
            "--slow-every" -> slowEvery = args[++index].toInt()
            "--budget" -> budgetMax = args[++index].toInt()
            "--max-conns" -> maxConns = args[++index].toInt()
            "--ban-ms" -> banMs = args[++index].toLong()
        }
        index++
    }
    System.setProperty("piko.download.panelFactor", factor.toString())
    File(System.getProperty("java.io.tmpdir"), "piko-tail-bench").mkdirs()
    PikoLog.install(File(System.getProperty("java.io.tmpdir"), "piko-tail-bench").absolutePath) { level, tag, message, error ->
        if (level.letter != 'I') println("[${level.letter}][$tag] $message${error?.let { " | ${it::class.simpleName}: ${it.message}" }.orEmpty()}")
    }

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = Executors.newCachedThreadPool()
    val requestSeq = java.util.concurrent.atomic.AtomicInteger()
    val active = java.util.concurrent.atomic.AtomicInteger()
    val bannedUntil = java.util.concurrent.atomic.AtomicLong(0L)
    server.createContext("/f") { exchange ->
        val seq = requestSeq.incrementAndGet()
        // 账号/IP 级并发上限：超出的连接直接 503；被动断连一次后进入 banMs 冷却期（PikPak CDN 的真实行为）。
        if (maxConns > 0) {
            val now = System.currentTimeMillis()
            if (now < bannedUntil.get()) {
                reply503(exchange)
                return@createContext
            }
            if (active.incrementAndGet() > maxConns) {
                active.decrementAndGet()
                bannedUntil.set(now + banMs)
                reply503(exchange)
                return@createContext
            }
        }
        try {
            // 每 slowEvery 个请求模拟一条慢连接（CDN 不同边缘节点的速率差），这是旧分段最怕的情况：
            // 一个慢分段会把尾部拖成单连接低速；细分后慢的只是一个 1–2 MiB 的小段。
            val effective = if (slowEvery > 0 && seq % slowEvery == 0) (rate / 5).coerceAtLeast(32 * 1024L) else rate
            serve(exchange, total, effective)
        } finally {
            if (maxConns > 0) active.decrementAndGet()
        }
    }
    server.start()
    val url = "http://127.0.0.1:${server.address.port}/f"
    println("[tail] panelFactor=$factor total=${total / MIB}MiB rate=${rate / 1024}KiB/s per-conn slowEvery=$slowEvery tasks=$tasks budget=$budgetMax cdnMaxConns=$maxConns banMs=$banMs")

    val budget = ConnectionBudget(budgetMax)
    val started = TimeSource.Monotonic.markNow()
    val results = (1..tasks).map { i ->
        async(Dispatchers.IO) { sample("task$i", url, total, budget, factor) }
    }.awaitAll()
    val elapsed = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
    results.forEach { println(it) }
    println("[tail] 总耗时 $elapsed ms，峰值连接 ${budget.peak}，成功 ${results.count { !it.contains("FAILED") }}/$tasks")
    server.stop(0)
}

private suspend fun sample(name: String, url: String, total: Long, budget: ConnectionBudget, factor: Int): String {
    val target = Files.createTempFile("tail-$name-", ".bin").toFile()
    target.deleteOnExit()
    val tuning = DownloadTuning(8, 64)
    val downloader = SegmentDownloader(urlProvider = { url }, budget = budget, tuning = tuning)
    val marks = sortedMapOf<Int, Long>()
    val samples = mutableListOf<Pair<Long, Long>>()
    val start = TimeSource.Monotonic.markNow()
    var failure: String? = null
    try {
        downloader.download(
            fileId = name,
            fileName = target.name,
            targetPath = target.absolutePath,
            totalBytes = total,
            completedRanges = emptyList(),
            onProgress = { progress: DirectDownloadProgress ->
                val pct = (progress.downloadedBytes * 100 / total).toInt()
                samples += pct.toLong() to progress.speedBytesPerSec
                for (threshold in THRESHOLDS) {
                    if (pct >= threshold && threshold !in marks) marks[threshold] = progress.speedBytesPerSec
                }
            },
            onCheckpoint = {},
        )
    } catch (error: Throwable) {
        failure = "${error::class.simpleName}: ${error.message}"
    } finally {
        downloader.close()
    }
    val wall = start.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
    if (failure != null) {
        return "[tail][$name] FAILED after $wall ms：$failure | 最高完成度 ${(marks.keys.maxOrNull() ?: 0)}%"
    }
    val curve = THRESHOLDS.joinToString(" ") { t -> "$t%=${((marks[t] ?: 0L) / 1024)}KiB/s" }
    return "[tail][$name] 用时 $wall ms，平均 ${total * 1000 / wall / 1024} KiB/s | $curve"
}

private val THRESHOLDS = listOf(10, 25, 50, 75, 85, 90, 95, 99)

/** 模拟 CDN 对超出并发/进入冷却期的请求直接回 503。 */
private fun reply503(exchange: HttpExchange) {
    try {
        val body = "busy".toByteArray()
        exchange.sendResponseHeaders(503, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    } catch (_: Exception) {
    } finally {
        runCatching { exchange.close() }
    }
}

private fun serve(exchange: HttpExchange, total: Long, rate: Long) {
    try {
        val range = exchange.requestHeaders.getFirst("Range")
        var start = 0L
        var end = total - 1
        var status = 200
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val from = spec.substringBefore('-').toLongOrNull() ?: 0L
            val to = spec.substringAfter('-').toLongOrNull()
            start = from.coerceIn(0, total - 1)
            end = (to ?: (total - 1)).coerceIn(start, total - 1)
            status = 206
        }
        val length = end - start + 1
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        if (status == 206) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
        exchange.sendResponseHeaders(status, length)
        val chunk = 64 * 1024
        val sleepMs = (chunk * 1000L / rate).coerceAtLeast(1L)
        exchange.responseBody.use { out ->
            var remaining = length
            while (remaining > 0) {
                val n = min(chunk.toLong(), remaining).toInt()
                out.write(ByteArray(n))
                out.flush()
                remaining -= n
                if (remaining > 0) Thread.sleep(sleepMs)
            }
        }
    } catch (_: Exception) {
        // 客户端取消 / 断连属正常路径
    } finally {
        runCatching { exchange.close() }
    }
}
