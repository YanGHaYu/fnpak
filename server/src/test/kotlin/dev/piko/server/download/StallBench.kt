package dev.piko.server.download

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadedSpan
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.download.DirectDownloadProgress
import dev.piko.shared.download.PikoDirectDownloader
import dev.piko.shared.download.PikoDownloadCoordinator
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.TaskPhase
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「排队永远不开始」的离线验收台（只编译进 test 源集，不随安装包发布）。
 *
 * 用真实 `PikoDownloadCoordinator` + 真实 `ConnectionBudget`，只把分段直链下载替换成
 * 「每段先申请全局连接名额、再慢速下载」的假实现。覆盖三条路径：
 * 1. 线上操作复现：3 个并发任务一起暂停再继续，全部应完成、名额归零；
 * 2. 名额泄漏自愈：人为让两个协程拿到名额后结束且不归还，后续任务应在自愈后继续；
 * 3. 登记残留自愈：人为注入一个「已结束」的任务登记，排队中的任务应被自愈捞出并完成，无需重启。
 *
 * 运行：`gradlew :server:stallBench`（见验收目录里的 init 脚本）。
 */
private class BudgetedDownloader(private val budget: ConnectionBudget, private val panels: Int = 8) : PikoDirectDownloader {
    override val name = "budgeted-direct"

    override suspend fun download(
        fileId: String,
        fileName: String,
        targetPath: String,
        totalBytes: Long,
        completedRanges: List<DownloadedSpan>,
        onProgress: suspend (DirectDownloadProgress) -> Unit,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
    ) {
        val done = AtomicInteger(0)
        val panelBytes = totalBytes / panels
        coroutineScope {
            (0 until panels).map {
                launch {
                    // 与 SegmentDownloader.downloadPanel 相同的顺序：先拿名额，再下载，finally 归还
                    val permit = budget.acquire()
                    try {
                        delay(200)
                        val progressed = done.incrementAndGet() * panelBytes
                        onProgress(DirectDownloadProgress(progressed, totalBytes, 0L, emptyList()))
                    } finally {
                        budget.release(permit)
                    }
                }
            }.joinAll()
        }
        RandomAccessFile(targetPath, "rw").use { file ->
            if (file.length() < totalBytes) file.setLength(totalBytes)
            file.seek(0)
            file.write(ByteArray(1024) { 0x5A })
        }
        onCheckpoint(listOf(DownloadedSpan(0, totalBytes - 1)))
        onProgress(DirectDownloadProgress(totalBytes, totalBytes, 0L, emptyList()))
    }
}

private fun benchFile(id: String, size: Long) = FileStat(
    id = id,
    name = "stall-$id.bin",
    hash = "",
    size = size.toString(),
    parentId = "",
    kind = FileKind.FILE,
    phase = TaskPhase.COMPLETE,
)

private fun PikoDownloadCoordinator.describe(): String = tasks.value.values
    .sortedBy { it.taskId }
    .joinToString(" | ") { "${it.taskId.removePrefix("stall-")}=${it.status}(${it.downloadedBytes}/${it.totalBytes})" }

private fun PikoDownloadCoordinator.statusOfFile(fileId: String): DownloadStatus? =
    tasks.value.values.firstOrNull { it.fileId == fileId }?.status

private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        delay(100)
    }
    return condition()
}

/**
 * 往 `jobs` 里塞一个已经结束的登记，复现线上「进程内残留、重启才恢复」的状态。
 * 只用于离线验收，生产代码不会走到这里。
 */
@Suppress("UNCHECKED_CAST")
private fun injectStaleJob(coordinator: PikoDownloadCoordinator, taskId: String) {
    val flow = PikoDownloadCoordinator::class.java.getDeclaredField("jobs")
        .apply { isAccessible = true }
        .get(coordinator) as MutableStateFlow<Map<String, Job>>
    flow.value = flow.value + (taskId to Job().apply { complete() })
    println("[bench] 已注入残留登记：$taskId -> 已结束的 Job")
}

fun main(args: Array<String>) = runBlocking {
    var failures = 0
    fun check(ok: Boolean, message: String) {
        println(if (ok) "[PASS] $message" else "[FAIL] $message")
        if (!ok) failures++
    }

    val root = Files.createTempDirectory("fnpak-stall-bench").toFile()
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

    // ---- 0. 预算单测：等待者的 acquire 被取消，名额必须还回来 ----
    run {
        val budget = ConnectionBudget(1)
        val first = budget.acquire()
        val waiter = scope.launch { budget.acquire() }
        delay(100)
        waiter.cancelAndJoin()
        budget.release(first)
        val reacquired = withTimeoutOrNull(800) { budget.acquire() }
        reacquired?.let { budget.release(it) }
        println("[bench] 取消等待者后：active=${budget.current}/${budget.max}，等待=${budget.waiting}，重新申请${if (reacquired == null) "超时" else "成功"}")
        check(reacquired != null, "取消等待者后名额立即可用（不泄漏）")
        check(budget.current == 0, "单测结束后名额归零")
    }

    // ---- 0b. 自愈保护：持有者协程仍活跃时，等待超时也不回收 ----
    run {
        val budget = ConnectionBudget(1, reclaimAfterMs = 400)
        val holder = scope.launch {
            val permit = budget.acquire()
            delay(2_500)
            budget.release(permit)
        }
        delay(100)
        val t0 = System.currentTimeMillis()
        val grantedAt = CompletableDeferred<Long>()
        val waiter = scope.launch {
            val permit = budget.acquire()
            budget.release(permit)
            grantedAt.complete(System.currentTimeMillis() - t0)
        }
        delay(1_500) // 期间超时自愈至少跑了 3 次
        println("[bench] 持有者活跃时：active=${budget.current}/${budget.max}，等待=${budget.waiting}，1.5s 内已放行=${grantedAt.isCompleted}")
        check(!grantedAt.isCompleted, "持有者协程仍活跃时不回收名额（不误伤正常下载）")
        val waited = withTimeoutOrNull(4_000) { grantedAt.await() }
        println("[bench] 持有者归还后等待者拿到名额，用时 ${waited ?: -1} ms")
        check(waited != null && waited >= 2_300, "只有持有者真正归还后才拿到名额")
        holder.join()
        waiter.join()
    }

    // 全局名额故意小于「3 个并发任务 × 8 段」= 24，让分段必须排队；
    // 自愈窗口调到 1.5s 便于离线验证（生产默认 30s，见 ConnectionBudget.RECLAIM_AFTER_MS）。
    val budget = ConnectionBudget(2, reclaimAfterMs = 1_500)
    val downloader = BudgetedDownloader(budget)
    val coordinator = PikoDownloadCoordinator(provider, preferences, storage, scope, directDownloader = downloader)
    // 残留登记场景用独立的协调器与预算（在场景 3 开场才创建，保证看门狗 tick 从注入之后算起）
    val staleRoot = Files.createTempDirectory("fnpak-stall-stale").toFile()
    val staleDownloads = staleRoot.resolve("downloads").apply { mkdirs() }
    val staleCache = staleRoot.resolve("cache").apply { mkdirs() }
    println("[bench] 临时目录 ${root.absolutePath}，连接预算 ${budget.max}，BATCH_PARALLEL=3，每任务 8 段")

    try {
        // ---- 1. 线上操作复现：入队 → 暂停 → 继续，全部应完成 ----
        coordinator.enqueueFiles((1..6).map { benchFile("S$it", 1_048_576L) }, batchId = "stall")
        delay(300)
        println("[bench] 启动后：${coordinator.describe()}")
        println("[bench] 启动后预算：active=${budget.current}/${budget.max}，peak=${budget.peak}，等待=${budget.waiting}")
        coordinator.pauseBatch("stall")
        delay(400)
        println("[bench] 暂停后：${coordinator.describe()}，预算 active=${budget.current}/${budget.max}")
        coordinator.resumeBatch("stall")
        val healthy = waitUntil(25_000) {
            coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED }
        }
        println("[bench] 恢复后：${coordinator.describe()}")
        println("[bench] 恢复后预算：active=${budget.current}/${budget.max}，peak=${budget.peak}，等待=${budget.waiting}")
        check(healthy, "暂停/继续后 6 个任务全部 COMPLETED")
        check(coordinator.tasks.value.values.all { it.downloadedBytes == it.totalBytes }, "6 个任务字节数完整")
        check(budget.current == 0, "预算名额全部归还（active=0）")

        // ---- 2. 名额泄漏自愈：两个协程拿到名额后结束且不归还 ----
        val orphanCount = 2
        (1..orphanCount).map { scope.launch { budget.acquire() } }.joinAll()
        println("[bench] 人为泄漏后：active=${budget.current}/${budget.max}，等待=${budget.waiting}（持有者协程均已结束）")
        check(budget.current == budget.max, "已人为占满全部名额且持有者协程已结束")
        coordinator.enqueueFiles(listOf(benchFile("S10", 1_048_576L)), batchId = "orphan")
        val orphanStart = System.currentTimeMillis()
        val healed = waitUntil(12_000) { coordinator.statusOfFile("S10") == DownloadStatus.COMPLETED }
        val healedMs = System.currentTimeMillis() - orphanStart
        println("[bench] 泄漏自愈：S10=${coordinator.statusOfFile("S10")}，用时 ${healedMs} ms，预算 active=${budget.current}/${budget.max}，等待=${budget.waiting}")
        check(healed, "名额泄漏后任务在 ${healedMs} ms 内自愈并完成（无需重启）")
        check(healedMs >= 1_400, "确实等到自愈窗口才继续，而不是被直接放过")
        check(budget.current == 0, "自愈回收后名额归零")

        // ---- 3. 登记残留自愈：注入一个「已结束」的任务登记，复现永久排队 ----
        // 场景专用协调器：创建后 10s 才第一次自检，注入后能稳定观察到「仍停在 PENDING」
        val staleBudget = ConnectionBudget(2, reclaimAfterMs = 1_500)
        val staleCoordinator = PikoDownloadCoordinator(
            provider,
            ServerPreferences(staleRoot.toPath()) { staleDownloads.toPath() },
            ServerDownloadStorage(staleCache) { staleDownloads },
            scope,
            directDownloader = BudgetedDownloader(staleBudget),
        )
        // 先用 3 个任务占住全部并发位置，S11 排在第 4 位，才能在 PENDING 状态下被注入
        staleCoordinator.enqueueFiles(
            (1..3).map { benchFile("G$it", 4_194_304L) } + benchFile("S11", 1_048_576L),
            batchId = "stale",
        )
        delay(150)
        val staleId = staleCoordinator.tasks.value.values.first { it.fileId == "S11" }.taskId
        println("[bench] 注入前：S11=${staleCoordinator.statusOfFile("S11")}，任务表 ${staleCoordinator.describe()}")
        check(staleCoordinator.statusOfFile("S11") == DownloadStatus.PENDING, "S11 以 PENDING 状态排队")
        injectStaleJob(staleCoordinator, staleId)
        delay(2_000)
        println("[bench] 注入后 2s：S11=${staleCoordinator.statusOfFile("S11")}，预算 active=${staleBudget.current}/${staleBudget.max}")
        check(staleCoordinator.statusOfFile("S11") == DownloadStatus.PENDING, "复现：残留登记把任务挡在 PENDING")
        val retry = staleCoordinator.retryTask(staleId)
        delay(500)
        println("[bench] 对 PENDING 任务点重试：accepted=${retry.accepted}，状态=${staleCoordinator.statusOfFile("S11")}")
        check(staleCoordinator.statusOfFile("S11") == DownloadStatus.PENDING, "复现：重试也不会开始（与线上一致）")
        val staleStart = System.currentTimeMillis()
        val revived = waitUntil(13_000) { staleCoordinator.statusOfFile("S11") == DownloadStatus.COMPLETED }
        val revivedMs = System.currentTimeMillis() - staleStart
        println("[bench] 队列自愈：S11=${staleCoordinator.statusOfFile("S11")}，自愈用时 ${revivedMs} ms，预算 active=${staleBudget.current}/${staleBudget.max}")
        check(revived, "残留登记在 ${revivedMs} ms 内被自愈清理，任务完成（无需重启）")
        check(revivedMs >= 4_000, "确实等看门狗（约 10s 周期）兜住，而不是别处顺手放行")
        check(staleBudget.current == 0, "自愈后名额归零")

        // ---- 4. 取消回归：取消一个正在跑的任务，同批另一个继续完成，名额归还 ----
        coordinator.enqueueFiles(listOf(benchFile("S7", 1_048_576L), benchFile("S8", 1_048_576L)), batchId = "cancel")
        delay(200)
        val cancelId = coordinator.tasks.value.values.first { it.fileId == "S7" }.taskId
        coordinator.cancelDownload(cancelId)
        val cancelled = waitUntil(15_000) { coordinator.statusOfFile("S8") == DownloadStatus.COMPLETED }
        val drain = mutableListOf<Int>()
        repeat(30) {
            delay(100)
            drain += budget.current
        }
        println("[bench] 取消后：${coordinator.describe()}，预算 active 采样（每 100ms）=${drain.joinToString(",")}，等待=${budget.waiting}")
        check(coordinator.tasks.value.values.none { it.fileId == "S7" }, "取消后任务记录已移除")
        check(cancelled, "同批另一个任务继续完成")
        check(drain.last() == 0, "取消后名额全部归还（active=0）")
    } finally {
        scope.cancel()
        root.deleteRecursively()
        staleRoot.deleteRecursively()
    }
    if (failures > 0) {
        println("[bench] 失败断言 $failures 条")
        kotlin.system.exitProcess(1)
    }
    println("[bench] 完成：全部断言通过")
}
