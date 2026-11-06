package dev.piko.server.download

import dev.piko.download.DownloadSegmentInfo
import dev.piko.download.DownloadedSpan
import dev.piko.shared.download.DirectDownloadProgress
import dev.piko.shared.download.PikoDirectDownloader
import dev.piko.shared.log.PikoLog
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Download"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
private const val BUFFER_BYTES = 64 * 1024
private const val PROGRESS_INTERVAL_MS = 500L
private const val CHECKPOINT_INTERVAL_MS = 5_000L
private const val SPEED_WINDOW_MS = 3_000L
private const val MAX_LINK_REFRESH = 3
private const val MIN_PANEL_BYTES = 1L * 1024 * 1024

/** 每条签名直链最多同时开几条连接：CDN 对同一签名 URL 的第 9 条起返回 503（SDK 实测 n−8）。 */
private const val PER_URL_CONNECTIONS = 8

/** 主 lane 先跑这么久再取第二条直链，避开文件详情接口的短时频率限制。 */
private const val DUAL_LANE_DELAY_MS = 1_500L

/** 小文件不值得为第二条直链多打一次详情接口：至少要能切出两条 lane 各 [MIN_PANEL_BYTES] 段的空间。 */
private const val DUAL_LANE_MIN_BYTES = 16L * 1024 * 1024

/** 运行期可调的下载参数。设置页改完立即用于新任务；已在跑的任务沿用启动时的分段数。 */
class DownloadTuning(
    segments: Int = DEFAULT_SEGMENTS,
    maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    dualSignedUrls: Boolean = false,
    downloadDiagnostics: Boolean = false,
) {
    @Volatile
    var segmentCount: Int = segments.coerceIn(1, 32)
        private set

    @Volatile
    var maxRetries: Int = DEFAULT_MAX_RETRIES
        private set

    /** 同一文件是否再取一条不同签名的直链并行分段；任务启动时读一次快照。 */
    @Volatile
    var dualSignedUrls: Boolean = dualSignedUrls
        private set

    /** 只读诊断指标日志；关闭时不采集，也不改变下载路径。 */
    @Volatile
    var downloadDiagnostics: Boolean = downloadDiagnostics
        private set

    fun updateSegments(value: Int) {
        segmentCount = value.coerceIn(1, 32)
    }

    fun updateRetries(value: Int) {
        maxRetries = value.coerceIn(1, 10)
    }

    fun updateAcceleration(dual: Boolean, diagnostics: Boolean) {
        dualSignedUrls = dual
        downloadDiagnostics = diagnostics
    }

    companion object {
        const val DEFAULT_SEGMENTS = 8
        const val DEFAULT_MAX_CONNECTIONS = 32
        const val DEFAULT_MAX_RETRIES = 5
    }
}

/**
 * 全局连接预算：所有下载任务的所有分段共用。
 *
 * 不用 `Semaphore` 是因为上限要在运行期改（设置页），而 `Semaphore` 的许可数固定；这里的等待者
 * 由自己排队，唤醒时直接把名额移交，活跃数不会短暂超过上限。
 */
class ConnectionBudget(
    initialLimit: Int,
    /** 等待者超过这个时长还没拿到名额时，做一次「持有者协程已结束」的残留名额回收。 */
    private val reclaimAfterMs: Long = RECLAIM_AFTER_MS,
) {
    /**
     * 一次申请到的名额。归还幂等：被自愈回收之后，原持有者的 `finally` 再归还也不会多减。
     * 记下持有者 [owner]，自愈时只回收「协程已经结束」的名额，仍在传输的不会被误收。
     */
    class Permit internal constructor(internal val owner: Job?) {
        internal val granted = CompletableDeferred<Unit>()
        internal val released = AtomicBoolean(false)
    }

    private val lock = Mutex()
    private var limit = initialLimit.coerceIn(1, 128)
    private val held = mutableSetOf<Permit>()
    private val waiters = ArrayDeque<Permit>()

    @Volatile
    private var active = 0

    @Volatile
    private var waitingCount = 0

    @Volatile
    private var peakActive = 0

    val current: Int get() = active
    val peak: Int get() = peakActive
    val max: Int get() = limit

    /** 正在等名额的分段数：正常下载里接近 0，长期大于 0 说明名额可能没还回来。 */
    val waiting: Int get() = waitingCount

    suspend fun acquire(): Permit {
        val permit = Permit(currentCoroutineContext()[Job])
        val queued = lock.withLock {
            if (held.size < limit) {
                grantLocked(permit)
                false
            } else {
                waiters.addLast(permit)
                waitingCount = waiters.size
                true
            }
        }
        if (!queued) return permit
        try {
            while (true) {
                // 正常下载名额是「释放即移交」，等待者几乎不会真的等这么久；能走到这里说明有
                // 持有者的协程已经结束却没把名额还回来（取消路径漏归还、进程内遗留等）。
                if (withTimeoutOrNull(reclaimAfterMs) { permit.granted.await() } != null) return permit
                reclaimOrphans()
            }
        } catch (e: CancellationException) {
            // 取消时必须把等待者从队列里摘掉：否则 release() 会把名额移交给一个已经没人等待的
            // Deferred，active 永远减不下去，之后所有分段都卡在申请名额（任务停在 DOWNLOADING 0 字节，
            // 后面的任务一直 PENDING）。
            withContext(NonCancellable) {
                if (permit.granted.isCompleted) {
                    // 已经被 release() 移交（名额记到了这个等待者头上）：自己再还回去
                    release(permit)
                } else {
                    lock.withLock {
                        waiters.remove(permit)
                        waitingCount = waiters.size
                    }
                }
            }
            throw e
        }
    }

    /** 归还名额；等待队列非空时直接把名额移交给下一个人，不先减再加。 */
    suspend fun release(permit: Permit) = withContext(NonCancellable) {
        if (!permit.released.compareAndSet(false, true)) return@withContext
        val next = lock.withLock {
            if (held.remove(permit)) {
                active--
                if (waiters.isEmpty()) null else waiters.removeFirst().also {
                    waitingCount = waiters.size
                    grantLocked(it)
                }
            } else {
                // 已被自愈回收，名额已经还过了
                null
            }
        }
        next?.granted?.complete(Unit)
    }

    suspend fun updateLimit(value: Int) {
        val wake = mutableListOf<Permit>()
        lock.withLock {
            limit = value.coerceIn(1, 128)
            while (held.size < limit && waiters.isNotEmpty()) {
                wake += waiters.removeFirst().also { grantLocked(it) }
                waitingCount = waiters.size
            }
        }
        wake.forEach { it.granted.complete(Unit) }
    }

    /**
     * 回收「持有者协程已经结束」的名额，并把腾出来的名额移交给等待者。
     * 只在有等待者等到超时才调用：健康下载不会走到这里，真正传输中的分段协程也还在活跃，
     * 不会被误收。返回回收了几个。
     */
    suspend fun reclaimOrphans(): Int {
        var reclaimed = 0
        val wake = mutableListOf<Permit>()
        lock.withLock {
            for (permit in held.toList()) {
                if (permit.owner?.isActive != true) {
                    held.remove(permit)
                    permit.released.set(true)
                    active--
                    reclaimed++
                }
            }
            while (held.size < limit && waiters.isNotEmpty()) {
                wake += waiters.removeFirst().also { grantLocked(it) }
                waitingCount = waiters.size
            }
        }
        wake.forEach { it.granted.complete(Unit) }
        if (reclaimed > 0) {
            PikoLog.w(TAG, "连接预算自愈：回收 $reclaimed 个已结束协程占用的名额，当前 $active/$limit，等待 ${waiters.size}")
        }
        return reclaimed
    }

    private fun grantLocked(permit: Permit) {
        held += permit
        active++
        if (active > peakActive) peakActive = active
    }

    companion object {
        /** 正常分段申请名额是毫秒级；等待超过这个时长才做一次自愈回收，避免误伤健康下载。 */
        const val RECLAIM_AFTER_MS = 30_000L
    }
}

/** 单个分段彻底失败：交给外层降级成单连接续传。 */
private class SegmentFailed(val panel: Panel, cause: Throwable) :
    IOException("分段 ${panel.index} 连续失败：${cause.message}", cause)

/** 直链过期（签名有期限）：重新取直链后从原位置继续。 */
private class LinkExpired(val url: String, val reason: String) : IOException("直链失效：$reason")

private data class Panel(val index: Int, val start: Long, val end: Long) {
    val size: Long get() = end - start + 1
}

/**
 * 服务端自研的多线程下载：把一个文件按字节切成若干段，对同一条签名直链并发发 `Range: bytes=a-b`，
 * 各段用 [FileChannel] 的定位写直接写进同一个**预分配**文件的不同偏移。
 *
 * 为什么选「预分配单文件 + 定位写」而不是「每段一个临时文件最后合并」：
 * 1. 不需要合并步骤，也就没有合并时再读一遍、再写一遍的磁盘放大；
 * 2. 断点续传只维护一份文件，续传点就是各段已落盘的字节区间，重启后直接按偏移补齐；
 * 3. 没有「合并到一半进程被杀」的中间态。代价是文件在下载开始时就占满名义长度（Linux 上是稀疏文件，
 *    实际占用按已写块增长），用户看到的目录大小会提前变大。
 */
class SegmentDownloader(
    private val urlProvider: suspend (fileId: String) -> String,
    /** 同一文件的第二次签名直链（可为同一个取详情方法）；为空或同签名时保持单 lane。 */
    private val secondUrlProvider: (suspend (fileId: String) -> String)? = null,
    private val budget: ConnectionBudget,
    private val tuning: DownloadTuning,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : PikoDirectDownloader, AutoCloseable {

    override val name: String get() = "segmented"

    override suspend fun download(
        fileId: String,
        fileName: String,
        targetPath: String,
        totalBytes: Long,
        completedRanges: List<DownloadedSpan>,
        onProgress: suspend (DirectDownloadProgress) -> Unit,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(totalBytes > 0) { "分段下载需要已知文件大小：$fileName" }
        val dualEnabled = tuning.dualSignedUrls && secondUrlProvider != null &&
            totalBytes >= DUAL_LANE_MIN_BYTES
        // 开启双直链时段数翻倍：每条 lane 各自跑满 segmentCount 条连接，而不是把原有 8 段拆成 4+4
        val panels = planPanels(totalBytes, if (dualEnabled) tuning.segmentCount * 2 else tuning.segmentCount)
        val book = ProgressBook(panels, completedRanges)
        val degraded = AtomicBoolean(false)
        val metrics = DownloadMetrics(tuning.downloadDiagnostics)
        val startedNs = System.nanoTime()
        val raf = RandomAccessFile(targetPath, "rw")
        val channel = raf.channel
        val holder = UrlHolder(fileId, urlProvider, urlProvider(fileId), PER_URL_CONNECTIONS)
        try {
            if (raf.length() != totalBytes) raf.setLength(totalBytes)
            PikoLog.i(TAG, "分段下载：$fileName，$totalBytes 字节，${panels.size} 段，已落盘 ${book.total()} 字节，" +
                "连接预算 ${budget.max}" + if (dualEnabled) "，准备第二条签名直链" else "")
            try {
                coroutineScope {
                    // 进度协程是常驻循环：分段协程全部结束后必须显式取消，否则 coroutineScope 永远不返回
                    val ticker = launch { progressLoop(book, totalBytes, degraded, onProgress, onCheckpoint, channel) }
                    try {
                        val jobs = mutableListOf<Job>()
                        if (!dualEnabled) {
                            panels.forEach { panel ->
                                jobs += launch {
                                    downloadPanel(panel, channel, holder, book, onCheckpoint, "primary", metrics)
                                }
                            }
                        } else {
                            // 主 lane 先领一半立即开跑，另一半留给第二 lane；第二 lane 拿不到不同签名时由主 lane 接手
                            val primaryPanels = panels.take((panels.size + 1) / 2)
                            val secondPanels = panels.drop(primaryPanels.size)
                            primaryPanels.forEach { panel ->
                                jobs += launch {
                                    downloadPanel(panel, channel, holder, book, onCheckpoint, "primary", metrics)
                                }
                            }
                            jobs += launch {
                                downloadSecondLane(
                                    fileId, secondPanels, channel, holder, book, onCheckpoint, metrics,
                                )
                            }
                        }
                        jobs.joinAll()
                    } finally {
                        ticker.cancel()
                    }
                }
            } catch (failure: SegmentFailed) {
                degraded.set(true)
                PikoLog.w(TAG, "分段失败，降级为单连接续传：$fileName，还剩 ${book.missing()} 字节，" +
                    "原因=${failure.message}")
                singleConnectionPass(panels, channel, holder, book, onCheckpoint, metrics)
            }
            check(book.missing() == 0L) { "仍有 ${book.missing()} 字节未完成" }
            PikoLog.i(TAG, "分段下载收尾：$fileName，覆盖校验通过，峰值连接 ${budget.peak}/${budget.max}")
            metrics.logSummary(fileName, book, budget, (System.nanoTime() - startedNs) / 1_000_000)
        } finally {
            withContext(NonCancellable) {
                runCatching { onCheckpoint(book.spans()) }
                runCatching { channel.force(false) }
                runCatching { channel.close() }
                runCatching { raf.close() }
            }
        }
    }

    /** 单段：从该段已落盘的位置开始拉，失败按指数退避重试；直链过期先换直链再续。 */
    private suspend fun downloadPanel(
        panel: Panel,
        channel: FileChannel,
        holder: UrlHolder,
        book: ProgressBook,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
        lane: String,
        metrics: DownloadMetrics,
    ) {
        val startedNs = System.nanoTime()
        var attempts = 0
        var refreshes = 0
        book.setActive(panel.index, true)
        try {
            while (true) {
                val from = panel.start + book.written(panel.index)
                if (from > panel.end) return
                try {
                    // 先拿本直链的 8 连接名额，再拿全局预算：避免占着全局名额却卡在单 URL 上限上
                    holder.gate.withPermit {
                        val waitStart = System.nanoTime()
                        val permit = budget.acquire()
                        metrics.recordBudgetWait((System.nanoTime() - waitStart) / 1_000_000)
                        try {
                            fetchRange(channel, holder, from, panel.end, lane, panel.index, metrics) { bytes ->
                                book.add(panel.index, bytes)
                            }
                        } finally {
                            budget.release(permit)
                        }
                    }
                    attempts = 0
                    // 一段刚下完就落一次续传点，不必等下一个 5 秒周期
                    onCheckpoint(book.spans())
                    if (panel.start + book.written(panel.index) > panel.end) return
                } catch (expired: LinkExpired) {
                    if (++refreshes > MAX_LINK_REFRESH) throw SegmentFailed(panel, expired)
                    PikoLog.w(TAG, "分段 ${panel.index} 直链失效（${expired.reason}），重新取直链后续传")
                    holder.refresh(expired.url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (++attempts > tuning.maxRetries) throw SegmentFailed(panel, error)
                    val wait = backoffMillis(attempts)
                    PikoLog.w(TAG, "分段 ${panel.index} 第 $attempts 次失败（${error.message}），${wait} ms 后重试")
                    delay(wait)
                }
            }
        } finally {
            book.setActive(panel.index, false)
            metrics.recordPanel(lane, panel.index, panel.size, book.written(panel.index),
                (System.nanoTime() - startedNs) / 1_000_000)
        }
    }

    /**
     * 第二 lane：主 lane 跑满 [DUAL_LANE_DELAY_MS] 后再取一条直链。
     * 只有「path + 排序后的 query」不同才启用；取失败、同签名、或该文件已经下完时交给主 lane 收尾。
     */
    private suspend fun downloadSecondLane(
        fileId: String,
        panels: List<Panel>,
        channel: FileChannel,
        primary: UrlHolder,
        book: ProgressBook,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
        metrics: DownloadMetrics,
    ) {
        delay(DUAL_LANE_DELAY_MS)
        if (panels.isEmpty() || book.missing() == 0L) return
        val provider = secondUrlProvider ?: return downloadOnPrimary(panels, channel, primary, book, onCheckpoint, metrics)
        val second = try {
            provider(fileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            PikoLog.w(TAG, "第二条直链获取失败（${error.message}），保持单 lane")
            return downloadOnPrimary(panels, channel, primary, book, onCheckpoint, metrics)
        }
        if (identityOf(second) == identityOf(primary.current())) {
            PikoLog.i(TAG, "第二条直链与主直链同签名，保持单 lane：文件=$fileId")
            return downloadOnPrimary(panels, channel, primary, book, onCheckpoint, metrics)
        }
        val holder = UrlHolder(fileId, provider, second, PER_URL_CONNECTIONS)
        PikoLog.i(TAG, "启用第二 lane：文件=$fileId，${panels.size} 段走第二条签名直链")
        coroutineScope {
            panels.forEach { panel ->
                launch {
                    try {
                        downloadPanel(panel, channel, holder, book, onCheckpoint, "second", metrics)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failed: Throwable) {
                        PikoLog.w(TAG, "第二 lane 分段 ${panel.index} 失败（${failed.message}），交由主 lane 续传")
                    }
                }
            }
        }
        val unfinished = panels.filter { book.written(it.index) < it.size }
        if (unfinished.isNotEmpty()) {
            PikoLog.w(TAG, "第二 lane 有 ${unfinished.size} 段未完成，降级到主 lane 续传：文件=$fileId")
            // 仍按主 lane 的并行方式补，靠主直链的 8 连接门控制并发；真失败再走单连接兜底
            downloadOnPrimary(unfinished, channel, primary, book, onCheckpoint, metrics)
        }
    }

    /** 没有第二 lane 时，把留给它的分段重新交给主 lane。 */
    private suspend fun downloadOnPrimary(
        panels: List<Panel>,
        channel: FileChannel,
        primary: UrlHolder,
        book: ProgressBook,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
        metrics: DownloadMetrics,
    ) = coroutineScope {
        panels.forEach { panel ->
            launch { downloadPanel(panel, channel, primary, book, onCheckpoint, "primary", metrics) }
        }
    }

    /** 直链身份：只看 path 与排序后的 query，换域名（host）不算新签名。 */
    private fun identityOf(rawUrl: String): String {
        val uri = runCatching { URI.create(rawUrl) }.getOrNull() ?: return rawUrl
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.sorted().joinToString("&")
        return uri.rawPath.orEmpty() + "?" + query
    }

    /** 降级路径：只用一条连接，从各段断点顺序补齐剩余字节。 */
    private suspend fun singleConnectionPass(
        panels: List<Panel>,
        channel: FileChannel,
        holder: UrlHolder,
        book: ProgressBook,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
        metrics: DownloadMetrics,
    ) {
        for (panel in panels) {
            var attempts = 0
            var refreshes = 0
            while (true) {
                val from = panel.start + book.written(panel.index)
                if (from > panel.end) break
                try {
                    holder.gate.withPermit {
                        val waitStart = System.nanoTime()
                        val permit = budget.acquire()
                        metrics.recordBudgetWait((System.nanoTime() - waitStart) / 1_000_000)
                        try {
                            book.setActive(panel.index, true)
                            fetchRange(channel, holder, from, panel.end, "single", panel.index, metrics) { bytes ->
                                book.add(panel.index, bytes)
                            }
                        } finally {
                            book.setActive(panel.index, false)
                            budget.release(permit)
                        }
                    }
                    attempts = 0
                    onCheckpoint(book.spans())
                    if (panel.start + book.written(panel.index) > panel.end) break
                } catch (expired: LinkExpired) {
                    if (++refreshes > MAX_LINK_REFRESH) throw SegmentFailed(panel, expired)
                    PikoLog.w(TAG, "单连接续传分段 ${panel.index} 直链失效（${expired.reason}），重新取直链")
                    holder.refresh(expired.url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (++attempts > tuning.maxRetries) throw SegmentFailed(panel, error)
                    val wait = backoffMillis(attempts)
                    PikoLog.w(TAG, "单连接续传第 $attempts 次失败（${error.message}），${wait} ms 后重试")
                    delay(wait)
                }
            }
        }
        onCheckpoint(book.spans())
    }

    /**
     * 拉取 [from]–[to] 这一段并定位写进文件。返回即代表这一段已经写满；
     * 上游提前断流、状态码异常、直链返回错误页都抛异常，由调用方决定重试还是换直链。
     */
    private suspend fun fetchRange(
        channel: FileChannel,
        holder: UrlHolder,
        from: Long,
        to: Long,
        lane: String,
        panelIndex: Int,
        metrics: DownloadMetrics,
        onBytes: (Int) -> Unit,
    ) {
        val url = holder.current()
        val startedNs = System.nanoTime()
        val request = HttpRequest.newBuilder(URI.create(url))
            // 单段最长 60 秒内必须读完；分段下载里一段通常不到这个量级，超时后重试即可
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Range", "bytes=$from-$to")
            .GET()
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).awaitCancellable()
        val headersMs = (System.nanoTime() - startedNs) / 1_000_000
        val status = response.statusCode()
        var bytesRead = 0L
        try {
            val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
            if (status == 401 || status == 403 || status == 410) {
                runCatching { response.body().close() }
                throw LinkExpired(url, "HTTP $status")
            }
            if (status != 200 && status != 206) {
                runCatching { response.body().close() }
                throw IOException("HTTP $status")
            }
            // 签名过期时 PikPak 常回 200 + 一段 JSON/HTML 错误页，不能当成文件字节写进去
            if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                runCatching { response.body().close() }
                throw LinkExpired(url, contentType.substringBefore(';'))
            }
            response.body().use { input ->
                var position = from
                val buffer = ByteArray(BUFFER_BYTES)
                while (position <= to) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (status == 200 && position != 0L) throw IOException("上游忽略 Range 返回整个文件")
                    var done = 0
                    val chunk = ByteBuffer.wrap(buffer, 0, read)
                    while (chunk.hasRemaining()) {
                        val written = channel.write(chunk, position + done)
                        if (written <= 0) throw IOException("写盘没有进展")
                        done += written
                    }
                    position += read
                    bytesRead += read
                    onBytes(read)
                }
                if (position <= to) throw IOException("上游提前结束：写到 $position，目标 $to")
            }
        } finally {
            metrics.recordRequest(
                lane, panelIndex, status, headersMs,
                (System.nanoTime() - startedNs) / 1_000_000, bytesRead,
            )
        }
    }

    /** 500 毫秒报一次进度；速度按 3 秒滑动窗口算，避免瞬时值抖动。 */
    private suspend fun progressLoop(
        book: ProgressBook,
        totalBytes: Long,
        degraded: AtomicBoolean,
        onProgress: suspend (DirectDownloadProgress) -> Unit,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
        channel: FileChannel,
    ) {
        val window = ArrayDeque<Pair<Long, Long>>()
        val origin = System.nanoTime()
        window.addLast(origin to book.total())
        var lastCheckpoint = origin
        while (currentCoroutineContext().isActive) {
            delay(PROGRESS_INTERVAL_MS)
            val now = System.nanoTime()
            val total = book.total()
            window.addLast(now to total)
            while (window.size > 2 && now - window.first().first > SPEED_WINDOW_MS * 1_000_000L) window.removeFirst()
            val (firstMark, firstBytes) = window.first()
            val elapsedNs = (now - firstMark).coerceAtLeast(1L)
            val speed = ((total - firstBytes).coerceAtLeast(0L) * 1_000_000_000L) / elapsedNs
            onProgress(DirectDownloadProgress(total, totalBytes, speed, book.segments(), degraded.get()))
            if ((now - lastCheckpoint) / 1_000_000L >= CHECKPOINT_INTERVAL_MS) {
                lastCheckpoint = now
                // force 让续传点在进程被 kill 时也真的落盘，而不是还留在页缓存里
                runCatching { channel.force(false) }
                onCheckpoint(book.spans())
            }
        }
    }

    override fun close() {
        runCatching { client.close() }
    }

    private fun planPanels(totalBytes: Long, requested: Int): List<Panel> {
        // 小文件不值得切段：每段至少 1 MiB，段数不超过配置
        val count = minOf(requested.toLong(), (totalBytes + MIN_PANEL_BYTES - 1) / MIN_PANEL_BYTES)
            .coerceAtLeast(1L).toInt()
        val size = totalBytes / count
        return (0 until count).map { index ->
            val start = index.toLong() * size
            val end = if (index == count - 1) totalBytes - 1 else start + size - 1
            Panel(index, start, end)
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = 800L shl (attempt - 1).coerceIn(0, 4)
        return base.coerceAtMost(15_000L) + Random.nextLong(0, 300)
    }
}

/** 各段已落盘的字节数；所有计数都是并发的分段协程在改，用原子类型。 */
private class ProgressBook(private val panels: List<Panel>, completed: List<DownloadedSpan>) {
    private val written = AtomicLongArray(panels.size)
    private val active = AtomicIntegerArray(panels.size)
    private val total = AtomicLong(0)

    init {
        for (panel in panels) {
            val offset = resumeOffset(panel, completed)
            val bytes = offset - panel.start
            written[panel.index] = bytes
            total.addAndGet(bytes)
        }
    }

    fun written(index: Int): Long = written[index]

    fun add(index: Int, bytes: Int) {
        written.addAndGet(index, bytes.toLong())
        total.addAndGet(bytes.toLong())
    }

    fun setActive(index: Int, value: Boolean) = active.set(index, if (value) 1 else 0)

    fun total(): Long = total.get()

    fun missing(): Long = panels.sumOf { it.size - written[it.index] }

    fun spans(): List<DownloadedSpan> = panels.mapNotNull { panel ->
        val bytes = written[panel.index]
        if (bytes <= 0) null else DownloadedSpan(panel.start, panel.start + bytes - 1)
    }

    fun segments(): List<DownloadSegmentInfo> = panels.map { panel ->
        DownloadSegmentInfo(panel.index, panel.start, panel.end, written[panel.index], active[panel.index] == 1)
    }
}

/**
 * 只读诊断指标：默认关闭（[tuning] 里的 `downloadDiagnostics`），关闭时所有记录方法直接返回，
 * 不改变下载路径，也不额外分配集合。
 */
private class DownloadMetrics(private val enabled: Boolean) {
    private class Request(
        val lane: String,
        val panel: Int,
        val status: Int,
        val headersMs: Long,
        val totalMs: Long,
        val bytes: Long,
    )

    private class PanelRun(
        val lane: String,
        val index: Int,
        val totalBytes: Long,
        val doneBytes: Long,
        val elapsedMs: Long,
    )

    private val requests = ConcurrentLinkedQueue<Request>()
    private val panelRuns = ConcurrentLinkedQueue<PanelRun>()
    private val budgetWaitTotalMs = AtomicLong()
    private val budgetWaitCount = AtomicLong()

    fun recordBudgetWait(ms: Long) {
        if (!enabled) return
        budgetWaitTotalMs.addAndGet(ms)
        budgetWaitCount.incrementAndGet()
    }

    fun recordRequest(lane: String, panel: Int, status: Int, headersMs: Long, totalMs: Long, bytes: Long) {
        if (!enabled) return
        requests += Request(lane, panel, status, headersMs, totalMs, bytes)
    }

    fun recordPanel(lane: String, index: Int, totalBytes: Long, doneBytes: Long, elapsedMs: Long) {
        if (!enabled) return
        panelRuns += PanelRun(lane, index, totalBytes, doneBytes, elapsedMs)
    }

    fun logSummary(fileName: String, book: ProgressBook, budget: ConnectionBudget, elapsedMs: Long) {
        if (!enabled) return
        val all = requests.toList()
        val ok = all.filter { it.status == 200 || it.status == 206 }
        val rejected = all.filter { it.status != 200 && it.status != 206 }.groupingBy { it.status }.eachCount()
        val avgHeadersMs = ok.map { it.headersMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L
        val bytes = ok.sumOf { it.bytes }
        val busyMs = ok.sumOf { it.totalMs }.coerceAtLeast(1L)
        val perConnectionBytesPerSec = bytes * 1_000L / busyMs
        val waits = budgetWaitCount.get()
        val avgWaitMs = if (waits == 0L) 0L else budgetWaitTotalMs.get() / waits
        PikoLog.i(
            TAG,
            "下载诊断[$fileName]：耗时 $elapsedMs ms，HTTP ${all.size} 次（成功 ${ok.size}，" +
                "503 ${rejected[503] ?: 0}，其他异常状态 ${rejected.filterKeys { it != 503 }}），" +
                "平均首字节 $avgHeadersMs ms，单连接均速 $perConnectionBytesPerSec B/s，" +
                "预算等待 $waits 次/平均 $avgWaitMs ms，峰值 ${budget.peak}/${budget.max}，" +
                "已落盘 ${book.total()} 字节",
        )
        panelRuns.maxByOrNull { it.elapsedMs }?.let { slowest ->
            PikoLog.i(
                TAG,
                "下载诊断[$fileName]：最慢分段 ${slowest.lane}#${slowest.index}，" +
                    "${slowest.doneBytes}/${slowest.totalBytes} 字节，用时 ${slowest.elapsedMs} ms",
            )
        }
    }
}

/** 从 [completed] 里算出这一段的续传位置：只认从段首连成一片的前缀，中间有洞就从洞里重下。 */
private fun resumeOffset(panel: Panel, completed: List<DownloadedSpan>): Long {
    var cursor = panel.start
    for (span in completed.sortedBy { it.startByte }) {
        if (span.endByte < cursor) continue
        if (span.startByte > cursor) break
        cursor = maxOf(cursor, span.endByte + 1)
    }
    return minOf(cursor, panel.end + 1)
}

private class UrlHolder(
    private val fileId: String,
    private val provider: suspend (String) -> String,
    initial: String,
    limit: Int = PER_URL_CONNECTIONS,
) {
    /** 同一签名直链的连接上限；主、第二 lane 各一个，互不占用。 */
    val gate = Semaphore(limit)

    @Volatile
    private var url = initial
    private val lock = Mutex()

    fun current(): String = url

    /** 同一时刻只有第一条 403 会真的重取，其余分段拿到的是已经换好的新直链。 */
    suspend fun refresh(stale: String): String = lock.withLock {
        if (url == stale) {
            url = provider(fileId)
            PikoLog.i(TAG, "已重新取直链：文件=$fileId")
        }
        url
    }
}

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error == null) {
            continuation.resumeWith(Result.success(value))
        } else {
            continuation.resumeWith(Result.failure((error as? CompletionException)?.cause ?: error))
        }
    }
    continuation.invokeOnCancellation {
        runCatching { cancel(true) }
    }
}
