package dev.piko.server.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.piko.shared.download.DirectDownloadProgress
import dev.piko.shared.log.PikoLog
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * 离线并发/限流验收台：本地假 CDN 可以分别限制「账号总并发」「单签名 URL 并发」，
 * 并在越界后进入惩罚窗口（回 503，或模拟真机那种「声明了 content-length 却 0 字节直接断开」）。
 *
 * 用法示例：
 *   --tasks 3 --budget 16 --initial 16 --adaptive 1 --lanes 2 --max-conns 6 --url-conns 4 --penalty drop --ban-ms 20000
 *   --tasks 3 --budget 16 --initial 16 --adaptive 0 --lanes 2 --max-conns 6 --url-conns 4 --penalty drop --ban-ms 20000
 */
private const val MIB = 1024L * 1024L

private class Cdn(
    @Volatile var accountCap: Int,
    @Volatile var urlCap: Int,
    @Volatile var banMs: Long,
    @Volatile var penalty: String,
    /** 单 URL 累计请求数上限（不是并发）：超过后一律按惩罚处理，用于验证「请求次数」假设。 */
    @Volatile var requestCap: Int = 0,
) {
    val activeByUrl = ConcurrentHashMap<String, AtomicInteger>()
    val requestsByUrl = ConcurrentHashMap<String, AtomicInteger>()
    val total = AtomicInteger(0)
    val bannedUntil = AtomicLong(0)
    val penalties = AtomicInteger(0)
    val requests = AtomicInteger(0)
}

fun main(args: Array<String>) = runBlocking {
    var factor = 8
    var total = 128L * MIB
    var rate = 1L * MIB
    var tasks = 1
    var slowEvery = 0
    var ceiling = DownloadTuning.DEFAULT_MAX_CONNECTIONS
    var initial = 0
    var adaptive = true
    var maxConns = 0
    var urlConns = 0
    var banMs = 0L
    var penalty = "503"
    var lanes = 1
    var requestCap = 0
    var maxPanels = 32
    var sim = false
    var slowConns = 1
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--panel-factor" -> factor = args[++index].toInt()
            "--total" -> total = args[++index].toLong()
            "--rate" -> rate = args[++index].toLong()
            "--tasks" -> tasks = args[++index].toInt()
            "--slow-every" -> slowEvery = args[++index].toInt()
            "--budget" -> ceiling = args[++index].toInt()
            "--initial" -> initial = args[++index].toInt()
            "--adaptive" -> adaptive = args[++index] == "1"
            "--max-conns" -> maxConns = args[++index].toInt()
            "--url-conns" -> urlConns = args[++index].toInt()
            "--ban-ms" -> banMs = args[++index].toLong()
            "--penalty" -> penalty = args[++index]
            "--lanes" -> lanes = args[++index].toInt()
            "--req-cap" -> requestCap = args[++index].toInt()
            "--max-panels" -> maxPanels = args[++index].toInt()
            "--sim" -> sim = true
            "--slow-conns" -> slowConns = args[++index].toInt()
        }
        index++
    }
    if (sim) {
        // 纯调度模型：比较「固定等分面板」与「动态切分/工作窃取」在慢连接尾部下的耗时与请求数
        simPanelStrategies(total, minOf(16, ceiling), rate, slowConns)
        return@runBlocking
    }
    System.setProperty("piko.download.panelFactor", factor.toString())
    System.setProperty("piko.download.maxPanels", maxPanels.toString())
    val logDir = File(System.getProperty("java.io.tmpdir"), "piko-tail-bench").apply { mkdirs() }
    PikoLog.install(logDir.absolutePath) { level, tag, message, error ->
        if (level.letter != 'I') println("[${level.letter}][$tag] $message${error?.let { " | ${it::class.simpleName}: ${it.message}" }.orEmpty()}")
    }

    val cdn = Cdn(maxConns, urlConns, banMs, penalty, requestCap)
    val requestSeq = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/f") { exchange ->
        val seq = requestSeq.incrementAndGet()
        cdn.requests.incrementAndGet()
        val key = exchange.requestURI.query ?: "u=0"
        val perUrl = cdn.requestsByUrl.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        val now = System.currentTimeMillis()
        // 单 URL 累计请求数上限：与并发无关，第 N+1 次请求起直接拒绝（模拟按请求次数惩罚的 CDN）
        if (cdn.requestCap > 0 && perUrl > cdn.requestCap) {
            cdn.penalties.incrementAndGet()
            penalize(exchange, total, cdn)
            return@createContext
        }
        if (now < cdn.bannedUntil.get()) {
            cdn.penalties.incrementAndGet()
            penalize(exchange, total, cdn)
            return@createContext
        }
        val urlActive = cdn.activeByUrl.getOrPut(key) { AtomicInteger(0) }
        val totalActive = cdn.total.incrementAndGet()
        val urlNow = urlActive.incrementAndGet()
        val over = (cdn.accountCap > 0 && totalActive > cdn.accountCap) ||
            (cdn.urlCap > 0 && urlNow > cdn.urlCap)
        if (over) {
            cdn.total.decrementAndGet()
            urlActive.decrementAndGet()
            if (cdn.banMs > 0) cdn.bannedUntil.set(now + cdn.banMs)
            cdn.penalties.incrementAndGet()
            penalize(exchange, total, cdn)
            return@createContext
        }
        try {
            val effective = if (slowEvery > 0 && seq % slowEvery == 0) (rate / 5).coerceAtLeast(32 * 1024L) else rate
            serve(exchange, total, effective)
        } finally {
            cdn.total.decrementAndGet()
            urlActive.decrementAndGet()
        }
    }
    server.start()
    val port = server.address.port
    val base = "http://127.0.0.1:$port/f"
    val startAt = if (initial > 0) initial else minOf(16, ceiling)
    println(
        "[cdn] tasks=$tasks lanes=$lanes total=${total / MIB}MiB rate=${rate / 1024}KiB/s " +
            "accountCap=$maxConns urlCap=$urlConns reqCap=$requestCap penalty=$penalty ban=${banMs}ms | " +
            "budget=$ceiling initial=$startAt adaptive=$adaptive maxPanels=$maxPanels",
    )

    val budget = ConnectionBudget(ceiling, initialLimit = startAt, adaptive = adaptive)
    val started = TimeSource.Monotonic.markNow()
    val results = (1..tasks).map { i ->
        async(Dispatchers.IO) {
            sample(
                name = "task$i",
                primaryUrl = "$base?u=task$i-a",
                secondUrl = if (lanes >= 2) "$base?u=task$i-b" else null,
                total = total,
                budget = budget,
                adaptiveDownloads = adaptive,
            )
        }
    }.awaitAll()
    val elapsed = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
    results.forEach { println(it) }
    println(
        "[result] 总耗时 $elapsed ms，成功 ${results.count { !it.contains("FAILED") }}/$tasks，" +
            "峰值连接 ${budget.peak}，最终允许并发 ${budget.max}/${budget.maxCeiling}，" +
            "HTTP 总请求 ${cdn.requests.get()}，CDN 惩罚次数 ${cdn.penalties.get()}",
    )
    server.stop(0)
}

private suspend fun sample(
    name: String,
    primaryUrl: String,
    secondUrl: String?,
    total: Long,
    budget: ConnectionBudget,
    adaptiveDownloads: Boolean,
): String {
    val target = Files.createTempFile("tail-$name-", ".bin").toFile()
    target.deleteOnExit()
    val tuning = DownloadTuning(8, 64, dualSignedUrls = secondUrl != null)
    val downloader = SegmentDownloader(
        urlProvider = { primaryUrl },
        secondUrlProvider = secondUrl?.let { { _: String -> it } },
        budget = budget,
        tuning = tuning,
    )
    val marks = sortedMapOf<Int, Long>()
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
    val tail = "budget=${budget.max}/${budget.maxCeiling}" + if (adaptiveDownloads) "(自适应)" else "(固定)"
    if (failure != null) {
        return "[task][$name] FAILED after $wall ms：$failure | 最高完成度 ${(marks.keys.maxOrNull() ?: 0)}% | $tail"
    }
    val curve = THRESHOLDS.joinToString(" ") { t -> "$t%=${((marks[t] ?: 0L) / 1024)}KiB/s" }
    return "[task][$name] 用时 $wall ms，平均 ${total * 1000 / wall / 1024} KiB/s | $curve | $tail"
}

private val THRESHOLDS = listOf(50, 85, 95, 99)

/** 越界/惩罚窗口内的响应：503 或「声明长度但 0 字节就断开」。 */
private fun penalize(exchange: HttpExchange, total: Long, cdn: Cdn) {
    if (cdn.penalty == "drop") {
        try {
            val (start, end, status) = rangeOf(exchange, total)
            if (status == 206) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            // 只发响应头就断开：客户端会收到 "fixed content-length: N, bytes received: 0"
            exchange.sendResponseHeaders(status, end - start + 1)
        } catch (_: Exception) {
        } finally {
            runCatching { exchange.close() }
        }
        return
    }
    try {
        val body = "busy".toByteArray()
        exchange.sendResponseHeaders(503, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    } catch (_: Exception) {
    } finally {
        runCatching { exchange.close() }
    }
}

private fun rangeOf(exchange: HttpExchange, total: Long): Triple<Long, Long, Int> {
    val range = exchange.requestHeaders.getFirst("Range")
    if (range == null || !range.startsWith("bytes=")) return Triple(0L, total - 1, 200)
    val spec = range.removePrefix("bytes=").substringBefore(',')
    val from = spec.substringBefore('-').toLongOrNull() ?: 0L
    val to = spec.substringAfter('-').toLongOrNull()
    val start = from.coerceIn(0, total - 1)
    val end = (to ?: (total - 1)).coerceIn(start, total - 1)
    return Triple(start, end, 206)
}

private fun serve(exchange: HttpExchange, total: Long, rate: Long) {
    try {
        val (start, end, status) = rangeOf(exchange, total)
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

/**
 * 纯调度模型：比较「固定等分面板」与「动态切分/工作窃取」在慢连接尾部场景下的耗时与请求数。
 *
 * 模型：N 条连接，前 slowConns 条速率只有正常连接的 1/5（模拟某个 CDN 节点/连接在尾部变慢）。
 * - 方案 1：固定 32 个等分面板，轮询分给连接串行处理，总请求数 = 32；
 * - 方案 2：初始只开 N 段，连接空下来时从「剩余时间最长」的连接切走一半，请求数 ≈ 2N。
 */
private fun simPanelStrategies(totalBytes: Long, conns: Int, rate: Long, slowConns: Int) {
    val slowRate = (rate / 5.0).coerceAtLeast(1.0)
    fun speed(i: Int) = if (i < slowConns) slowRate else rate.toDouble()

    val panels = 32
    val perWorker = DoubleArray(conns)
    for (p in 0 until panels) perWorker[p % conns] += totalBytes.toDouble() / panels
    val fixedTime = (0 until conns).maxOf { perWorker[it] / speed(it) }

    val remaining = DoubleArray(conns) { totalBytes.toDouble() / conns }
    var requests = conns
    var now = 0.0
    while (true) {
        val active = (0 until conns).filter { remaining[it] > 1e-9 }
        if (active.isEmpty()) break
        val idx = active.minByOrNull { remaining[it] / speed(it) }!!
        val step = remaining[idx] / speed(idx)
        now += step
        for (i in active) remaining[i] = maxOf(0.0, remaining[i] - speed(i) * step)
        remaining[idx] = 0.0
        val victim = (0 until conns)
            .filter { remaining[it] > 1e-9 }
            .maxByOrNull { remaining[it] / speed(it) } ?: continue
        val steal = remaining[victim] / 2
        remaining[victim] -= steal
        remaining[idx] = steal
        requests++
    }

    fun fmt(seconds: Double) = String.format("%.2f", seconds)
    println(
        "[sim] 连接=$conns（慢连接 $slowConns 条，速率 1/5），文件=${totalBytes / MIB}MiB，" +
            "单连接速率=${rate / 1024}KiB/s",
    )
    println("[sim] 方案 1 固定 $panels 段：耗时 ${fmt(fixedTime)} s，总请求 $panels")
    println("[sim] 方案 2 动态切分/窃取：耗时 ${fmt(now)} s，总请求 $requests")
}
