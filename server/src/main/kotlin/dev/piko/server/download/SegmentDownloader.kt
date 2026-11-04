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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "Download"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
private const val BUFFER_BYTES = 64 * 1024
private const val PROGRESS_INTERVAL_MS = 500L
private const val CHECKPOINT_INTERVAL_MS = 5_000L
private const val SPEED_WINDOW_MS = 3_000L
private const val MAX_LINK_REFRESH = 3
private const val MIN_PANEL_BYTES = 1L * 1024 * 1024

/** 运行期可调的下载参数。设置页改完立即用于新任务；已在跑的任务沿用启动时的分段数。 */
class DownloadTuning(
    segments: Int = DEFAULT_SEGMENTS,
    maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
) {
    @Volatile
    var segmentCount: Int = segments.coerceIn(1, 32)
        private set

    @Volatile
    var maxRetries: Int = DEFAULT_MAX_RETRIES
        private set

    fun updateSegments(value: Int) {
        segmentCount = value.coerceIn(1, 32)
    }

    fun updateRetries(value: Int) {
        maxRetries = value.coerceIn(1, 10)
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
class ConnectionBudget(initialLimit: Int) {
    private val lock = Mutex()
    private var active = 0
    private var limit = initialLimit.coerceIn(1, 128)
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    @Volatile
    private var peakActive = 0

    val current: Int get() = active
    val peak: Int get() = peakActive
    val max: Int get() = limit

    suspend fun acquire() {
        val waiter: CompletableDeferred<Unit>? = lock.withLock {
            if (active < limit) {
                active++
                if (active > peakActive) peakActive = active
                null
            } else {
                CompletableDeferred<Unit>().also { waiters.addLast(it) }
            }
        }
        waiter?.await()
    }

    suspend fun release() {
        val next = lock.withLock {
            if (waiters.isEmpty()) {
                if (active > 0) active--
                null
            } else {
                // 名额直接移交，不先减再加：否则两个等待者会同时被放进来
                waiters.removeFirst()
            }
        }
        next?.complete(Unit)
    }

    suspend fun updateLimit(value: Int) {
        lock.withLock {
            limit = value.coerceIn(1, 128)
            while (active < limit && waiters.isNotEmpty()) {
                active++
                if (active > peakActive) peakActive = active
                waiters.removeFirst().complete(Unit)
            }
        }
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
        val panels = planPanels(totalBytes)
        val book = ProgressBook(panels, completedRanges)
        val degraded = AtomicBoolean(false)
        val raf = RandomAccessFile(targetPath, "rw")
        val channel = raf.channel
        val holder = UrlHolder(fileId, urlProvider, urlProvider(fileId))
        try {
            if (raf.length() != totalBytes) raf.setLength(totalBytes)
            PikoLog.i(TAG, "分段下载：$fileName，$totalBytes 字节，${panels.size} 段，已落盘 ${book.total()} 字节，" +
                "连接预算 ${budget.max}")
            try {
                coroutineScope {
                    // 进度协程是常驻循环：分段协程全部结束后必须显式取消，否则 coroutineScope 永远不返回
                    val ticker = launch { progressLoop(book, totalBytes, degraded, onProgress, onCheckpoint, channel) }
                    try {
                        val jobs = panels.map { panel ->
                            launch { downloadPanel(panel, channel, holder, book, onCheckpoint) }
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
                singleConnectionPass(panels, channel, holder, book, onCheckpoint)
            }
            check(book.missing() == 0L) { "仍有 ${book.missing()} 字节未完成" }
            PikoLog.i(TAG, "分段下载收尾：$fileName，覆盖校验通过，峰值连接 ${budget.peak}/${budget.max}")
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
    ) {
        var attempts = 0
        var refreshes = 0
        book.setActive(panel.index, true)
        try {
            while (true) {
                val from = panel.start + book.written(panel.index)
                if (from > panel.end) return
                try {
                    budget.acquire()
                    try {
                        fetchRange(channel, holder, from, panel.end) { bytes -> book.add(panel.index, bytes) }
                    } finally {
                        budget.release()
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
        }
    }

    /** 降级路径：只用一条连接，从各段断点顺序补齐剩余字节。 */
    private suspend fun singleConnectionPass(
        panels: List<Panel>,
        channel: FileChannel,
        holder: UrlHolder,
        book: ProgressBook,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
    ) {
        for (panel in panels) {
            var attempts = 0
            var refreshes = 0
            while (true) {
                val from = panel.start + book.written(panel.index)
                if (from > panel.end) break
                try {
                    budget.acquire()
                    try {
                        book.setActive(panel.index, true)
                        fetchRange(channel, holder, from, panel.end) { bytes -> book.add(panel.index, bytes) }
                    } finally {
                        book.setActive(panel.index, false)
                        budget.release()
                    }
                    onCheckpoint(book.spans())
                } catch (expired: LinkExpired) {
                    if (++refreshes > MAX_LINK_REFRESH) throw expired
                    holder.refresh(expired.url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (++attempts > tuning.maxRetries) throw error
                    val wait = backoffMillis(attempts)
                    PikoLog.w(TAG, "单连接续传第 $attempts 次失败（${error.message}），${wait} ms 后重试")
                    delay(wait)
                }
            }
        }
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
        onBytes: (Int) -> Unit,
    ) {
        val url = holder.current()
        val request = HttpRequest.newBuilder(URI.create(url))
            // 单段最长 60 秒内必须读完；分段下载里一段通常不到这个量级，超时后重试即可
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Range", "bytes=$from-$to")
            .GET()
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).awaitCancellable()
        val status = response.statusCode()
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
                onBytes(read)
            }
            if (position <= to) throw IOException("上游提前结束：写到 $position，目标 $to")
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

    private fun planPanels(totalBytes: Long): List<Panel> {
        // 小文件不值得切段：每段至少 1 MiB，段数不超过配置
        val count = minOf(tuning.segmentCount.toLong(), (totalBytes + MIN_PANEL_BYTES - 1) / MIN_PANEL_BYTES)
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
) {
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
