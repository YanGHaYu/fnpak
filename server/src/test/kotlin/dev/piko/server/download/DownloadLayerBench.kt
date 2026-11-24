package dev.piko.server.download

import dev.piko.download.DownloadSegmentInfo
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.download.DownloadedSpan
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.download.DirectDownloadProgress
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.PikoDirectDownloader
import dev.piko.shared.download.PikoDownloadCoordinator
import io.github.nihildigit.pikpak.DurableBlockStore
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RangeSource
import io.github.nihildigit.pikpak.fileCache
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 离线验收台：覆盖旧任务迁移、落盘提交、单文件/多文件/文件夹递归、暂停继续、失败重试、删除记录。
 *
 * 传输层已经被替换成 SDK 块缓存（`PikoFileCache`）；没有真实账号时无法离线跑 SDK 的 Range 传输，
 * 因此这里用一个 [PikoDirectDownloader] 测试替身只验证服务端的编排、进度、落盘与任务表状态机。
 * 真正走 SDK 块缓存的那一段，需要在有账号的真机上验收。
 */
fun main() = runBlocking {
    println("===== 1. 旧任务迁移 =====")
    migrationBench()

    println("===== 2. 落盘提交 =====")
    storageBench()

    println("===== 3. 单文件 / 多文件 / 暂停继续 / 失败重试 / 删除记录 =====")
    coordinatorBench()

    println("===== 4. 整文件夹递归 =====")
    folderBench()

    println("===== 5. 单文件在跑时追加文件夹（队列唤醒） =====")
    queueAfterSingleBench()

    println("===== 6. SDK 两个文件缓存并发（共用 client 预算） =====")
    sdkTwoCachesBench()

    println("===== 7. 可恢复传输失败（自动重试而不是判失败） =====")
    autoRetryBench()

    println("BENCH OK")
}

private fun migrationBench() {
    val home = Files.createTempDirectory("fnpak-migrate-")
    val tasks = listOf(
        DownloadTask(
            taskId = "completed", fileId = "f0", fileName = "done.bin", gcid = "g0", totalBytes = 100,
            downloadedBytes = 100, status = DownloadStatus.COMPLETED,
        ),
        DownloadTask(
            taskId = "segmented", fileId = "f1", fileName = "seg.bin", gcid = "g1", totalBytes = 100,
            downloadedBytes = 40, status = DownloadStatus.PAUSED, segmented = true,
            completedRanges = listOf(DownloadedSpan(0, 19)),
        ),
        DownloadTask(
            taskId = "sequential", fileId = "f2", fileName = "seq.bin", gcid = "g2", totalBytes = 100,
            downloadedBytes = 30, status = DownloadStatus.PAUSED, segmented = false,
        ),
    )
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    Files.writeString(home.resolve("download-tasks.json"), json.encodeToString(ListSerializer(DownloadTask.serializer()), tasks))

    val result = LegacyDownloadMigration.migrate(home)
    val migrated = json.decodeFromString(
        ListSerializer(DownloadTask.serializer()),
        Files.readString(home.resolve("download-tasks.json")),
    ).associateBy { it.taskId }
    check(result.total == 3 && result.completed == 1 && result.reset == 1 && result.kept == 1) { "迁移计数不对：$result" }
    check(migrated.getValue("completed").downloadedBytes == 100L)
    check(migrated.getValue("segmented").downloadedBytes == 0L && !migrated.getValue("segmented").segmented)
    check(migrated.getValue("segmented").errorMessage?.contains("从头下载") == true)
    check(migrated.getValue("sequential").downloadedBytes == 30L)
    println("[migrate] total=3 completed=1 reset=1 kept=1；segmented 已重置并带用户提示，sequential 保留 30 B")
}

private suspend fun storageBench() {
    val root = Files.createTempDirectory("fnpak-storage-")
    val cache = root.resolve("cache").toFile().apply { mkdirs() }
    val download = root.resolve("download").toFile().apply { mkdirs() }
    val storage = ServerDownloadStorage(cache, { download })
    val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
    val target = storage.downloadTarget("demo.bin")
    Files.write(Path.of(target), bytes)
    val committed = storage.commit("demo.bin", target)
    val size = Files.size(Path.of(committed))
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(committed)))
    check(size == bytes.size.toLong()) { "落盘大小不一致：$size" }
    check(digest.contentEquals(MessageDigest.getInstance("SHA-256").digest(bytes))) { "落盘校验失败" }
    check(Files.list(cache.toPath()).noneMatch { it.fileName.toString().endsWith(".tmp") }) { "缓存目录有临时文件残留" }
    println("[storage] 1 MiB 写入并提交成功，大小=$size，sha256=${digest.toHex()}")
}

private suspend fun coordinatorBench() = runBlocking {
    withGraph { graph, downloader ->
        val single = file("single", "single.bin", 512 * 1024)
        graph.downloadCoordinator.enqueue(single)
        val singleTask = awaitStatus(graph, "single.bin", DownloadStatus.COMPLETED)
        val singlePath = graph.downloadStorage.downloadTarget("single.bin")
        check(Files.size(Path.of(singlePath)) == single.sizeBytes) { "单文件大小不对" }
        check(checksum(Path.of(singlePath)) == downloader.checksumFor("single.bin")) { "单文件内容不对" }
        println("[single] 完成 ${singleTask.downloadedBytes} B，校验一致")

        val multi = listOf(file("m1", "multi-1.bin", 128 * 1024), file("m2", "multi-2.bin", 192 * 1024), file("m3", "multi-3.bin", 256 * 1024))
        graph.downloadCoordinator.enqueueFiles(multi)
        multi.forEach { awaitStatus(graph, it.name, DownloadStatus.COMPLETED) }
        println("[multi] 3 个文件全部完成")

        // 暂停 / 继续
        downloader.hold("paused.bin")
        val pausedFile = file("paused", "paused.bin", 256 * 1024)
        graph.downloadCoordinator.enqueue(pausedFile)
        awaitStatus(graph, "paused.bin", DownloadStatus.DOWNLOADING)
        graph.downloadCoordinator.pauseDownload(taskIdOf(graph, "paused.bin"))
        val paused = awaitStatus(graph, "paused.bin", DownloadStatus.PAUSED)
        check(paused.downloadedBytes >= 0)
        downloader.release("paused.bin")
        graph.downloadCoordinator.startDownload(paused.taskId)
        awaitStatus(graph, "paused.bin", DownloadStatus.COMPLETED)
        println("[pause] 暂停后状态 PAUSED，继续后完成")

        // 失败重试
        downloader.failOnce("retry.bin")
        graph.downloadCoordinator.enqueue(file("retry", "retry.bin", 128 * 1024))
        val failed = awaitStatus(graph, "retry.bin", DownloadStatus.FAILED)
        val outcome = graph.downloadCoordinator.retryTask(failed.taskId)
        check(outcome.accepted) { "重试被拒绝：${outcome.reason}" }
        awaitStatus(graph, "retry.bin", DownloadStatus.COMPLETED)
        println("[retry] 首次失败 → retryTask → 完成")

        // 删除任务记录：只删记录，不删已完成的文件
        val before = taskIdOf(graph, "multi-1.bin")
        graph.downloadCoordinator.removeTaskRecords(listOf(before))
        check(graph.downloadCoordinator.tasks.value[before] == null) { "任务记录未删除" }
        check(Files.exists(Path.of(graph.downloadStorage.downloadTarget("multi-1.bin")))) { "文件被误删" }
        println("[delete] 任务记录已删除，文件保留")
    }
}

private suspend fun folderBench() = runBlocking {
    withGraph { graph, _ ->
        val source = object : DownloadFolderSource {
            override suspend fun list(folderId: String): List<FileStat> = when (folderId) {
                "root-folder" -> listOf(
                    file("sub", "sub", 0, kind = FileKind.FOLDER, parentId = "root-folder"),
                    file("r1", "root-1.bin", 64 * 1024, parentId = "root-folder"),
                )
                "sub" -> listOf(file("r2", "root-2.bin", 96 * 1024, parentId = "sub"))
                else -> emptyList()
            }

            override suspend fun remainingDailyDownload(): Long? = null
        }
        val folder = FileStat(kind = FileKind.FOLDER, id = "root-folder", name = "Season 1")
        graph.downloadCoordinator.enqueueFolders(listOf(folder), source, maxHeight = null, targetDirectory = null)
        val completed = withTimeoutOrNull(20_000) {
            while (true) {
                val tasks = graph.downloadCoordinator.tasks.value.values
                if (tasks.size >= 2 && tasks.all { it.status == DownloadStatus.COMPLETED }) return@withTimeoutOrNull tasks
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList<DownloadTask>()
        } ?: error("文件夹下载超时")
        check(completed.all { it.parentTaskId.isNotBlank() }) { "文件夹任务缺少 parentTaskId" }
        check(completed.all { it.rootFolderId == "root-folder" }) { "文件夹任务 rootFolderId 不对" }
        println("[folder] 递归展开 ${completed.size} 个文件，全部完成，parentTaskId/rootFolderId 正确")
    }
}

private suspend fun queueAfterSingleBench() = runBlocking {
    withGraph { graph, downloader ->
        // 先让单文件停在下载中，模拟真机「先加单文件、它正在下」的状态
        downloader.hold("first-single.bin")
        graph.downloadCoordinator.enqueue(file("first", "first-single.bin", 512 * 1024))
        awaitStatus(graph, "first-single.bin", DownloadStatus.DOWNLOADING)

        val source = object : DownloadFolderSource {
            override suspend fun list(folderId: String): List<FileStat> = when (folderId) {
                "later-folder" -> listOf(file("late-1", "late-1.bin", 64 * 1024, parentId = "later-folder"))
                else -> emptyList()
            }

            override suspend fun remainingDailyDownload(): Long? = null
        }
        val folder = FileStat(kind = FileKind.FOLDER, id = "later-folder", name = "Later")
        graph.downloadCoordinator.enqueueFolders(listOf(folder), source, maxHeight = null, targetDirectory = null)

        val started = withTimeoutOrNull(5_000) {
            while (true) {
                val batchTask = graph.downloadCoordinator.tasks.value.values.firstOrNull {
                    it.batch != null && it.fileName.endsWith("late-1.bin")
                }
                if (batchTask != null && (batchTask.status == DownloadStatus.DOWNLOADING || batchTask.status == DownloadStatus.COMPLETED)) {
                    return@withTimeoutOrNull batchTask.status
                }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        val snapshot = graph.downloadCoordinator.tasks.value.values
            .joinToString { "${it.fileName}:${it.status}" }
        check(started != null) { "单文件在跑时文件夹任务没有被唤醒，当前任务：$snapshot" }
        println("[queue] 单文件保持下载中，后加的文件夹任务已进入 $started；当前任务：$snapshot")
        downloader.release("first-single.bin")
    }
}

/**
 * 直接压 SDK：同一个 PikPakClient 上开两个文件缓存，A 持续慢慢下载大文件，
 * B 下载 1 MiB 小文件。验证「一个文件在传输时，第二个文件的 download 会不会被饿死」。
 */
private suspend fun sdkTwoCachesBench() = runBlocking {
    val client = PikPakClient("bench-token")
    val bigSize = 256L * 1024 * 1024
    val smallSize = 1L * 1024 * 1024
    val cacheA = client.fileCache(
        source = SlowRangeSource(bigSize, delayMs = 200),
        size = bigSize,
        storeKey = "bench-a",
        blockStore = MemoryDurableStore(),
        connectionBudget = 8,
    )
    val cacheB = client.fileCache(
        source = SlowRangeSource(smallSize, delayMs = 0),
        size = smallSize,
        storeKey = "bench-b",
        blockStore = MemoryDurableStore(),
        connectionBudget = 8,
    )
    try {
        val jobA = cacheA.download()
        delay(2_000)
        val aDelivered = cacheA.deliveredBytes
        val bDone = withTimeoutOrNull(10_000) {
            cacheB.download().await()
            true
        }
        println(
            "[sdk] A delivered=$aDelivered B=$bDone budgetUsed=${client.transportMemoryUsedBytes}/${client.transportMemoryBudgetBytes}",
        )
        check(bDone == true) { "第二个文件缓存在第一个仍在传输时没有完成（疑似被共享预算/连接闸饿死）" }
        jobA.cancel()
    } finally {
        runCatching { cacheA.close() }
        runCatching { cacheB.close() }
        runCatching { client.close() }
    }
}

private class SlowRangeSource(private val total: Long, private val delayMs: Long) : RangeSource {
    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T {
        val end = minOf(start + length, total)
        val channel = io.ktor.utils.io.ByteChannel(autoFlush = false)
        val producer = CoroutineScope(currentCoroutineContext()).launch {
            var pos = start
            while (pos < end) {
                val n = minOf(64L * 1024L, end - pos).toInt()
                channel.writeFully(ByteArray(n) { ((pos + it) % 251).toByte() })
                channel.flush()
                pos += n
                if (delayMs > 0) delay(delayMs)
            }
            channel.flushAndClose()
        }
        try {
            return block(channel)
        } finally {
            producer.cancel()
            channel.cancel(java.util.concurrent.CancellationException("bench read finished"))
        }
    }
}

private class MemoryDurableStore : DurableBlockStore {
    private val blocks = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()
    override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = blocks[offset]
    override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
        blocks[offset] = bytes
    }

    override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> {
        val blockSize = 256L * 1024L
        val out = mutableListOf<LongRange>()
        for (range in ranges) {
            var pos = range.first
            while (pos <= range.last) {
                if (blocks[pos] == null) out += pos..pos
                pos += blockSize
            }
        }
        return out
    }
}

private suspend fun autoRetryBench() = runBlocking {
    withGraph { graph, downloader ->
        downloader.transientFailOnce("flaky.bin")
        graph.downloadCoordinator.enqueue(file("flaky", "flaky.bin", 128 * 1024))

        val waiting = withTimeoutOrNull(10_000) {
            while (true) {
                val task = graph.downloadCoordinator.tasks.value.values.firstOrNull { it.fileName == "flaky.bin" }
                if (task?.status == DownloadStatus.FAILED) error("可恢复失败被直接判失败：${task.errorMessage}")
                if (task?.status == DownloadStatus.PENDING && task.errorMessage?.contains("自动重试") == true) {
                    return@withTimeoutOrNull task
                }
                delay(30)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: error("可恢复失败没有进入等待重试")
        println("[retry] 首次传输中断 → ${waiting.status}：${waiting.errorMessage}")

        val completed = withTimeoutOrNull(25_000) {
            while (true) {
                val task = graph.downloadCoordinator.tasks.value.values.firstOrNull { it.fileName == "flaky.bin" }
                if (task?.status == DownloadStatus.FAILED) error("自动重试后仍失败：${task.errorMessage}")
                if (task?.status == DownloadStatus.COMPLETED) return@withTimeoutOrNull task
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: error("自动重试没有恢复下载")
        check(completed.downloadedBytes == completed.totalBytes) { "自动恢复后大小不对" }
        println("[retry] 退避后自动恢复并完成，held=${completed.downloadedBytes}/${completed.totalBytes}")
    }
}

private suspend fun withGraph(block: suspend (Graph, FakeDirectDownloader) -> Unit) {
    val root = Files.createTempDirectory("fnpak-bench-")
    val downloadDir = root.resolve("download").toFile().apply { mkdirs() }
    val prefs = ServerPreferences(root) { downloadDir.toPath() }
    val storage = ServerDownloadStorage(root.resolve("cache").toFile(), { downloadDir })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val downloader = FakeDirectDownloader(downloadDir.toPath())
    val client = PikPakClient("bench-token")
    val coordinator = PikoDownloadCoordinator(
        clientProvider = FakeClientProvider(client),
        preferences = prefs,
        storage = storage,
        scope = scope,
        directDownloader = downloader,
    )
    try {
        block(Graph(coordinator, storage), downloader)
    } finally {
        runCatching { coordinator.pauseAll() }
        runCatching { client.close() }
        scope.cancel()
    }
}

private class Graph(val downloadCoordinator: PikoDownloadCoordinator, val downloadStorage: ServerDownloadStorage)

private class FakeClientProvider(private val client: PikPakClient) : PikoClientProvider {
    override val currentClient = MutableStateFlow<PikPakClient?>(client)
}

private fun file(id: String, name: String, size: Long, kind: String = FileKind.FILE, parentId: String = "root") =
    FileStat(kind = kind, id = id, parentId = parentId, name = name, size = size.toString())

private suspend fun awaitStatus(graph: Graph, fileName: String, status: DownloadStatus): DownloadTask {
    val deadline = System.currentTimeMillis() + 20_000
    while (System.currentTimeMillis() < deadline) {
        val task = graph.downloadCoordinator.tasks.value.values.firstOrNull { it.fileName == fileName }
        if (task?.status == status) return task
        if (task?.status == DownloadStatus.FAILED && status != DownloadStatus.FAILED) {
            error("任务 $fileName 失败：${task.errorMessage}")
        }
        delay(30)
    }
    val tasks = graph.downloadCoordinator.tasks.value.values.joinToString { "${it.fileName}:${it.status}" }
    error("等待 $fileName -> $status 超时，当前任务：$tasks")
}

private fun taskIdOf(graph: Graph, fileName: String): String =
    graph.downloadCoordinator.tasks.value.values.first { it.fileName == fileName }.taskId

private fun checksum(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    return digest.toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * 只负责写盘的测试替身：与真实 SegmentDownloader 一样把字节写到目标文件，并回报进度。
 * 支持「挂起」「失败一次」两种控制，用于暂停/继续与失败重试场景。
 */
private class FakeDirectDownloader(private val root: Path) : PikoDirectDownloader {
    override val name: String = "bench-fake"
    private val holds = mutableSetOf<String>()
    private val failures = mutableMapOf<String, Int>()
    private val transientFailures = mutableMapOf<String, Int>()
    private val digests = mutableMapOf<String, String>()

    fun hold(fileName: String) { holds += fileName }
    fun release(fileName: String) { holds -= fileName }
    fun failOnce(fileName: String) { failures[fileName] = 1 }
    fun transientFailOnce(fileName: String) { transientFailures[fileName] = 1 }
    fun checksumFor(fileName: String): String = digests[fileName].orEmpty()

    override suspend fun download(
        fileId: String,
        fileName: String,
        targetPath: String,
        totalBytes: Long,
        completedRanges: List<DownloadedSpan>,
        onProgress: suspend (DirectDownloadProgress) -> Unit,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
    ) {
        fails(fileName)
        val target = Path.of(targetPath)
        Files.createDirectories(target.parent)
        val start = completedRanges.maxOfOrNull { it.endByte + 1 } ?: 0L
        java.io.RandomAccessFile(target.toFile(), "rw").use { out ->
            out.seek(start)
            var position = start
            while (position < totalBytes) {
                val n = minOf(32 * 1024L, totalBytes - position).toInt()
                out.write(ByteArray(n) { ((position + it) % 251).toByte() })
                position += n
                onProgress(
                    DirectDownloadProgress(
                        downloadedBytes = position,
                        totalBytes = totalBytes,
                        speedBytesPerSec = 1024 * 1024,
                        segments = listOf(DownloadSegmentInfo(0, 0, totalBytes - 1, position, true)),
                    ),
                )
                if (position * 2 >= totalBytes && fileName in holds) {
                    while (fileName in holds) delay(50)
                }
            }
        }
        digests[fileName] = checksum(target)
        onCheckpoint(listOf(DownloadedSpan(0, totalBytes - 1)))
    }

    private fun fails(fileName: String) {
        val transient = transientFailures[fileName] ?: 0
        if (transient > 0) {
            transientFailures[fileName] = transient - 1
            throw java.io.IOException("bench 模拟传输中断（可重试）")
        }
        val left = failures[fileName] ?: 0
        if (left > 0) {
            failures[fileName] = left - 1
            error("bench 模拟失败：$fileName")
        }
    }
}
