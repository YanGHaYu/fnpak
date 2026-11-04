package dev.piko.server.download

import dev.piko.download.DownloadedSpan
import dev.piko.shared.download.DirectDownloadProgress
import dev.piko.shared.log.PikoLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.TimeSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 分段下载的离线验收台（只编译进 test 源集，不随安装包发布）。
 *
 * 用法：`--spec <直链>,<输出文件>,<续传状态文件>` 可重复，多个 spec 会并发跑并共享一个连接预算。
 * 状态文件与 `download-tasks.json` 里的 `completedRanges` 同构，kill 后重启能接着下。
 */
@Serializable
private data class BenchState(val completedRanges: List<DownloadedSpan> = emptyList())

private data class Spec(val url: String, val out: String, val state: String)

private val json = Json { prettyPrint = true; encodeDefaults = true }

fun main(args: Array<String>) = runBlocking {
    // 把下载器自己的日志（重试、降级、直链刷新）同时打到标准输出，验收时直接可见
    File("/opt/dltest/piko-log").mkdirs()
    PikoLog.install("/opt/dltest/piko-log") { level, tag, message, error ->
        val detail = error?.let { " | ${it::class.simpleName}: ${it.message}" }.orEmpty()
        println("[${level.letter}][$tag] $message$detail")
    }
    val specs = mutableListOf<Spec>()
    var segments = 8
    var budgetMax = 32
    var retries = 5
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--spec" -> {
                val parts = args.getOrNull(index + 1)?.split(',') ?: error("--spec 需要 <url>,<out>,<state>")
                specs += Spec(parts[0], parts[1], parts[2])
                index++
            }
            "--segments" -> { segments = args.getOrNull(index + 1)?.toIntOrNull() ?: error("--segments 需要整数"); index++ }
            "--budget" -> { budgetMax = args.getOrNull(index + 1)?.toIntOrNull() ?: error("--budget 需要整数"); index++ }
            "--retries" -> { retries = args.getOrNull(index + 1)?.toIntOrNull() ?: error("--retries 需要整数"); index++ }
        }
        index++
    }
    require(specs.isNotEmpty()) { "至少给一个 --spec" }
    val budget = ConnectionBudget(budgetMax)
    val tuning = DownloadTuning(segments, budgetMax)
    println("[bench] 任务 ${specs.size} 个，每任务 $segments 段，全局连接预算 $budgetMax，重试上限 $retries")
    var failed = 0
    coroutineScope {
        specs.forEach { spec ->
            launch {
                try {
                    runOne(spec, budget, tuning, retries)
                } catch (error: Throwable) {
                    failed++
                    println("[bench][FAIL] ${spec.out}：${error::class.simpleName}: ${error.message}")
                }
            }
        }
    }
    println("[bench] 结束：失败 $failed 个，峰值连接 ${budget.peak}/${budget.max}")
    if (failed > 0) kotlin.system.exitProcess(1)
}

private suspend fun runOne(spec: Spec, budget: ConnectionBudget, tuning: DownloadTuning, retries: Int) {
    val total = sizeOfUrl(spec.url)
    val stateFile = File(spec.state)
    val resumed = runCatching {
        json.decodeFromString<BenchState>(stateFile.readText()).completedRanges
    }.getOrDefault(emptyList())
    println("[bench] 开始 ${spec.out}：$total 字节，续传记录 ${resumed.size} 段 / ${resumed.sumOf { it.length }} 字节，" +
        "分段=${tuning.segmentCount}")
    val started = TimeSource.Monotonic.markNow()
    var lastPrint = 0L
    val checkpoints = java.util.concurrent.atomic.AtomicInteger(0)
    val downloader = SegmentDownloader(
        urlProvider = { spec.url },
        budget = budget,
        tuning = DownloadTuning(tuning.segmentCount, budget.max).also { it.updateRetries(retries) },
    )
    try {
        downloader.download(
            fileId = "bench",
            fileName = File(spec.out).name,
            targetPath = spec.out,
            totalBytes = total,
            completedRanges = resumed,
            onProgress = { progress ->
                val elapsed = started.elapsedNow().inWholeMilliseconds
                if (elapsed - lastPrint >= 2_000) {
                    lastPrint = elapsed
                    println("[bench][progress] ${File(spec.out).name} ${progress.downloadedBytes}/${progress.totalBytes} " +
                        "(${progress.downloadedBytes * 1000 / elapsed / 1024} KiB/s 累计平均)，" +
                        "窗口速率 ${progress.speedBytesPerSec / 1024} KiB/s，" +
                        "分段 ${progress.segments.size}，活跃 ${progress.segments.count { it.active }}")
                }
                printTop(progress)
            },
            onCheckpoint = { spans ->
                checkpoints.incrementAndGet()
                writeState(stateFile, spans)
            },
        )
    } finally {
        downloader.close()
    }
    val elapsed = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
    val written = File(spec.out).length()
    println("[bench][done] ${spec.out}：$written 字节，历时 $elapsed ms，" +
        "平均 ${(total - resumed.sumOf { it.length }) * 1000 / elapsed} 字节/秒，落盘次数 ${checkpoints.get()}")
}

private var printedSegments = false

private fun printTop(progress: DirectDownloadProgress) {
    if (printedSegments) return
    printedSegments = true
    println("[bench][segments] " + progress.segments.joinToString(" ") { s ->
        "${s.index}:${s.startByte}-${s.endByte}"
    })
}

private fun writeState(file: File, spans: List<DownloadedSpan>) {
    file.parentFile?.mkdirs()
    val temp = File("${file.absolutePath}.tmp")
    Files.writeString(temp.toPath(), json.encodeToString(BenchState.serializer(), BenchState(spans)))
    runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        .onFailure { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
}

private fun sizeOfUrl(url: String): Long {
    // 用一个 0 字节的 Range 探测总长度：不读响应体，也绕开 HEAD 的实现差异
    val connection = java.net.URI.create(url).toURL().openConnection() as java.net.HttpURLConnection
    connection.setRequestProperty("User-Agent", "piko-bench")
    connection.setRequestProperty("Range", "bytes=0-0")
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    return try {
        val status = connection.responseCode
        check(status in 200..299) { "探测返回 HTTP $status" }
        val contentRange = connection.getHeaderField("Content-Range")
        if (contentRange != null && contentRange.contains('/')) {
            contentRange.substringAfterLast('/').trim().toLong()
        } else {
            connection.getHeaderField("Content-Length")?.toLongOrNull() ?: error("没有 Content-Length")
        }
    } finally {
        connection.disconnect()
    }
}
