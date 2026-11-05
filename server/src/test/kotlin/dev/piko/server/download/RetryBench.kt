package dev.piko.server.download

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadedSpan
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.download.DirectDownloadProgress
import dev.piko.shared.download.PikoDirectDownloader
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.notice.PikoErrorMapper
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.TaskPhase
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * 0.7.1「只重试失败项」的本地验收台（只编译进 test 源集，不随安装包发布）。
 *
 * 走的是真实 `PikoDownloadCoordinator`：入队、排队、落盘、失败、重试、续传点都由它的代码决定；
 * 只把「分段直链下载」替换成一个可控的假实现，用来精确制造失败点与断点。
 */
private class FlakyDownloader : PikoDirectDownloader {
    override val name = "harness-direct"

    /** 每个文件每次开始时的续传点，重试前后对比用。 */
    val starts = CopyOnWriteArrayList<String>()

    /** 第一次调用在下载一半后抛 HTTP 503。 */
    val failOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 进入 download 后先卡住，用来验证「正在下载中」的拒绝。 */
    val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val gates: MutableMap<String, CompletableDeferred<Unit>> = ConcurrentHashMap()

    val completed = AtomicInteger(0)

    fun gate(id: String): CompletableDeferred<Unit> = gates.getOrPut(id) { CompletableDeferred() }

    override suspend fun download(
        fileId: String,
        fileName: String,
        targetPath: String,
        totalBytes: Long,
        completedRanges: List<DownloadedSpan>,
        onProgress: suspend (DirectDownloadProgress) -> Unit,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
    ) {
        val done = completedRanges.sumOf { it.length }
        starts += "$fileId: 续传 ${done}/${totalBytes} 字节，${completedRanges.size} 段"
        if (fileId in blocked) {
            println("[bench] $fileId 卡在下载中，等释放")
            gate(fileId).await()
        }
        val start = completedRanges.maxOfOrNull { it.endByte + 1 } ?: 0L
        if (fileId in failOnce) {
            failOnce.remove(fileId)
            val upto = start + (totalBytes - start) / 2
            write(targetPath, start, upto)
            onCheckpoint(listOf(DownloadedSpan(0, upto - 1)))
            onProgress(DirectDownloadProgress(upto, totalBytes, 0L, emptyList()))
            throw IOException("HTTP 503 Service Unavailable")
        }
        write(targetPath, start, totalBytes)
        onCheckpoint(listOf(DownloadedSpan(0, totalBytes - 1)))
        onProgress(DirectDownloadProgress(totalBytes, totalBytes, 0L, emptyList()))
        completed.incrementAndGet()
    }

    private fun write(path: String, from: Long, to: Long) {
        RandomAccessFile(path, "rw").use { file ->
            if (file.length() < to) file.setLength(to)
            file.seek(from)
            var remaining = to - from
            while (remaining > 0) {
                val chunk = minOf(remaining, 4096L).toInt()
                file.write(ByteArray(chunk) { 0x5A })
                remaining -= chunk
            }
        }
    }
}

private fun fileStat(id: String, name: String, size: Long) = FileStat(
    id = id,
    name = name,
    hash = "",
    size = size.toString(),
    parentId = "",
    kind = FileKind.FILE,
    phase = TaskPhase.COMPLETE,
)

private suspend fun awaitStatus(coordinator: PikoDownloadCoordinator, id: String, status: DownloadStatus, timeoutMs: Long = 8_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (coordinator.tasks.value[id]?.status == status) return
        delay(20)
    }
    error("等待 $id 变为 $status 超时，当前 ${coordinator.tasks.value[id]?.status}")
}

fun main() = runBlocking {
    val root = Files.createTempDirectory("fnpak-retry-bench").toFile()
    val downloads = root.resolve("downloads").apply { mkdirs() }
    val cache = root.resolve("cache").apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val client = PikPakClient(
        account = "bench@piko.dev",
        password = "pw",
        sessionStore = InMemorySessionStore(),
        rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
    )
    val provider = object : PikoClientProvider {
        override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(client)
    }
    val storage = ServerDownloadStorage(cache) { downloads }
    val preferences = ServerPreferences(root.toPath()) { downloads.toPath() }
    val downloader = FlakyDownloader()
    val coordinator = PikoDownloadCoordinator(provider, preferences, storage, scope, directDownloader = downloader)
    println("[bench] 临时目录 ${root.absolutePath}")

    try {
        // ---- 1. 同一批两个文件：A 中途失败，B 正常完成 ----
        val a = fileStat("A", "retry-a.bin", 1024)
        val b = fileStat("B", "retry-b.bin", 512)
        downloader.failOnce += "A"
        coordinator.enqueueFiles(listOf(a, b), batchId = "batch-1")
        awaitStatus(coordinator, "A", DownloadStatus.FAILED)
        awaitStatus(coordinator, "B", DownloadStatus.COMPLETED)
        val failed = coordinator.tasks.value.getValue("A")
        println("[bench] 首次失败：A 状态=${failed.status}，已下 ${failed.downloadedBytes}/${failed.totalBytes}，" +
            "区间=${failed.completedRanges.map { "${it.startByte}-${it.endByte}" }}，原因=「${failed.errorMessage}」")
        println("[bench] 同批 B 状态=${coordinator.tasks.value.getValue("B").status}，磁盘 ${downloads.resolve("retry-b.bin").length()} 字节")
        check(failed.errorMessage?.contains("HTTP 503") == true) { "失败原因应带 HTTP 503" }
        val bBytesBefore = downloads.resolve("retry-b.bin").length()

        // ---- 2. 单条重试：只重跑 A，从 512 字节续传 ----
        val retryA = coordinator.retryTask("A")
        println("[bench] 单条重试 A：accepted=${retryA.accepted} reason=${retryA.reason}")
        check(retryA.accepted)
        awaitStatus(coordinator, "A", DownloadStatus.COMPLETED)
        val aAfter = coordinator.tasks.value.getValue("A")
        println("[bench] 重试后：A 状态=${aAfter.status}，已下 ${aAfter.downloadedBytes}/${aAfter.totalBytes}，" +
            "磁盘 ${downloads.resolve("retry-a.bin").length()} 字节；B 磁盘 ${downloads.resolve("retry-b.bin").length()} 字节（重试前 $bBytesBefore）")
        check(downloads.resolve("retry-a.bin").length() == 1024L)
        check(downloads.resolve("retry-b.bin").length() == bBytesBefore)
        check(coordinator.tasks.value.getValue("B").status == DownloadStatus.COMPLETED)

        // ---- 3. 已完成的文件再点重试：明确拒绝 ----
        val retryB = coordinator.retryTask("B")
        println("[bench] 对已完成 B 重试：accepted=${retryB.accepted} reason=${retryB.reason}")
        check(!retryB.accepted)

        // ---- 4. 正在下载的文件再点重试：拒绝且不产生重复任务 ----
        val c = fileStat("C", "retry-c.bin", 2048)
        downloader.blocked += "C"
        coordinator.enqueue(c)
        awaitStatus(coordinator, "C", DownloadStatus.DOWNLOADING)
        val beforeCount = coordinator.tasks.value.size
        val retryC = coordinator.retryTask(coordinator.tasks.value.values.first { it.fileId == "C" }.taskId)
        val afterCount = coordinator.tasks.value.size
        println("[bench] 对下载中 C 重试：accepted=${retryC.accepted} reason=「${retryC.reason}」，任务数 $beforeCount -> $afterCount")
        check(!retryC.accepted)
        check(beforeCount == afterCount)
        downloader.gate("C").complete(Unit)
        awaitStatus(coordinator, "C", DownloadStatus.COMPLETED)

        // ---- 5. 批量重试：只挑失败项，已完成的 D 不动 ----
        val d = fileStat("D", "retry-d.bin", 1024)
        val e = fileStat("E", "retry-e.bin", 256)
        downloader.failOnce += "D"
        coordinator.enqueueFiles(listOf(d, e), batchId = "batch-2")
        awaitStatus(coordinator, "D", DownloadStatus.FAILED)
        awaitStatus(coordinator, "E", DownloadStatus.COMPLETED)
        val eBytesBefore = downloads.resolve("retry-e.bin").length()
        val outcomes = coordinator.retryFailedInGroup("batch-2")
        println("[bench] 批量重试 batch-2：${outcomes.joinToString { "${it.taskId}:${it.accepted}:${it.reason ?: "ok"}" }}")
        check(outcomes.size == 1 && outcomes.single().taskId == "D" && outcomes.single().accepted)
        awaitStatus(coordinator, "D", DownloadStatus.COMPLETED)
        println("[bench] 批量重试后：D 磁盘 ${downloads.resolve("retry-d.bin").length()} 字节；" +
            "E 磁盘 ${downloads.resolve("retry-e.bin").length()} 字节（重试前 $eBytesBefore），状态=${coordinator.tasks.value.getValue("E").status}")
        check(downloads.resolve("retry-e.bin").length() == eBytesBefore)
        check(coordinator.tasks.value.getValue("E").status == DownloadStatus.COMPLETED)

        // ---- 6. 无失败项的批：批量重试返回空 ----
        val noFail = coordinator.retryFailedInGroup("batch-2")
        println("[bench] 全部完成后再批量重试：返回 ${noFail.size} 项（路由会回 409 nothing_to_retry）")

        // ---- 7. 失败原因映射 ----
        println("[bench] 原因映射 404：${PikoErrorMapper.download(PikPakException(3, "file_not_found", "", 404, emptyMap(), "", null))}")
        println("[bench] 原因映射 403：${PikoErrorMapper.download(PikPakException(3, "forbidden", "", 403, emptyMap(), "", null))}")
        println("[bench] 原因映射 空间：${PikoErrorMapper.download(IOException("No space left on device (ENOSPC)"))}")
        println("[bench] 原因映射 目录：${PikoErrorMapper.download(IOException("Permission denied (Read-only file system)"))}")
        println("[bench] 原因映射 直链：${PikoErrorMapper.download(IOException("直链失效：HTTP 403"))}")
        println("[bench] 续传记录：${downloader.starts.joinToString(" | ")}")
        println("[bench] 完成：全部断言通过")
    } finally {
        scope.cancel()
        root.deleteRecursively()
    }
}
