package dev.piko.server.drive

import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/** 缩略图磁盘缓存的可观测计数；`GET /api/drive/thumbnail/stats` 原样返回。 */
@Serializable
data class ThumbnailStats(
    val hits: Long,
    val misses: Long,
    val upstreamOk: Long,
    val upstreamFailures: Long,
    val evictions: Long,
    val writeFailures: Long,
    val cachedEntries: Long,
    val cachedBytes: Long,
    val linkEntries: Int,
    // 重启后内存直链表为空：按 fileId 查详情 + 重列父目录的兜底次数与成功数
    val coldLookups: Long,
    val recovered: Long,
    val negativeEntries: Int,
    val maxBytes: Long,
    val maxEntries: Int,
)

/**
 * 缩略图代理 + 磁盘缓存。
 *
 * 上游地址来自列表响应 `FileStat.thumbnailLink`（SDK 的 `listFilesPaged` 固定带
 * `thumbnail_size=SIZE_SMALL`），按 fileId 记住直链；直链过期/被拒时用父目录重新列一次刷新。
 * 浏览器永远只访问同源接口，签名直链不会出现在前端。
 *
 * 缓存放在 `$fnpak.home/thumbnails`，每个文件 `${sha256(fileId)}.thumb`，命中时刷新 mtime 形成
 * 「最近使用」序；写入前后按总字节数（默认 128 MiB）与条目数（默认 4096）淘汰最旧项。
 * 缩略图按 fileId 不可变，因此浏览器侧可以长缓存 + ETag 协商。
 *
 * 进程重启后内存直链表为空，此时先按 fileId 查一次文件详情拿 parentId，再重列该父目录取回
 * `thumbnail_link`；查不到就短时间记入负缓存（默认 60s）并回 404 走占位，避免反复打第三方。
 * 兜底（详情 + 重列）合计限 2 并发，且父目录列表 30s 内复用：重启后的首批缩略图只多打一次列表。
 */
class ThumbnailService(
    // 只依赖「按父目录列一页」的能力：直链过期或重启后兜底时用它刷新
    private val listFolder: suspend (parentId: String) -> List<FileStat>?,
    // 按 fileId 查一次详情（只为拿 parentId；SDK 的 FileDetail 不含 thumbnail_link）
    private val fileDetail: suspend (fileId: String) -> FileDetail? = { null },
    private val directory: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : AutoCloseable {

    private data class LinkEntry(val url: String, val parentId: String)

    private data class CachedListing(val expiresAt: Long, val files: List<FileStat>)

    internal class Thumbnail(val bytes: ByteArray, val contentType: String)

    private val links = ConcurrentHashMap<String, LinkEntry>()
    // 负缓存 / 父目录列表缓存都有 TTL，失败不会长期粘住
    private val negativeUntil = ConcurrentHashMap<String, Long>()
    private val listingCache = ConcurrentHashMap<String, CachedListing>()
    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val upstreamOk = AtomicLong()
    private val upstreamFailures = AtomicLong()
    private val evictions = AtomicLong()
    private val writeFailures = AtomicLong()
    private val coldLookups = AtomicLong()
    private val recovered = AtomicLong()
    private val cachedBytes = AtomicLong()
    private val cachedEntries = AtomicLong()
    private val scanned = AtomicBoolean(false)
    private val evictLock = Any()
    // 与下载的 ConnectionBudget 无关：缩略图单独限 4 条上游连接，避免和下载互相饿死
    private val upstreamSlots = Semaphore(UPSTREAM_PARALLELISM)
    // 冷启动兜底再收窄到 2 并发，防止一次进入大目录就把详情接口打满
    private val recoverySlots = Semaphore(RECOVERY_PARALLELISM)

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 15_000
        }
    }

    init {
        runCatching { Files.createDirectories(directory) }
            .onFailure { PikoLog.w(TAG, "无法创建缩略图缓存目录 $directory：${it.message}") }
        ensureScanned()
        PikoLog.i(
            TAG,
            "缩略图缓存：$directory（上限 ${maxBytes / 1024 / 1024} MiB / $maxEntries 项，当前 " +
                "${cachedEntries.get()} 项 ${cachedBytes.get() / 1024} KiB）",
        )
    }

    /** 列表/搜索返回时调用：把 fileId → 签名直链记在内存里，供缩略图请求使用。 */
    fun remember(files: List<FileStat>) {
        if (files.isEmpty()) return
        for (file in files) {
            val link = file.thumbnailLink
            if (link.isBlank()) {
                links.remove(file.id)
            } else {
                links[file.id] = LinkEntry(link, file.parentId)
            }
        }
    }

    fun stats(): ThumbnailStats {
        ensureScanned()
        return ThumbnailStats(
            hits = hits.get(),
            misses = misses.get(),
            upstreamOk = upstreamOk.get(),
            upstreamFailures = upstreamFailures.get(),
            evictions = evictions.get(),
            writeFailures = writeFailures.get(),
            cachedEntries = cachedEntries.get(),
            cachedBytes = cachedBytes.get(),
            linkEntries = links.size,
            coldLookups = coldLookups.get(),
            recovered = recovered.get(),
            negativeEntries = negativeUntil.size,
            maxBytes = maxBytes,
            maxEntries = maxEntries,
        )
    }

    /** 直接写 HTTP 响应：命中原样回图，缺图回 404 交给前端占位。 */
    suspend fun respond(call: ApplicationCall, fileId: String) {
        val thumbnail = load(fileId)
        if (thumbnail == null) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val etag = "\"${sha256Hex(fileId).take(16)}-${thumbnail.bytes.size}\""
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (ifNoneMatch != null && ifNoneMatch.split(',').any { it.trim().let { tag -> tag == etag || tag == "W/$etag" } }) {
            call.respond(HttpStatusCode.NotModified)
            return
        }
        call.respondBytes(thumbnail.bytes, ContentType.parse(thumbnail.contentType))
    }

    internal suspend fun load(fileId: String): Thumbnail? {
        if (fileId.isBlank()) return null
        val path = pathFor(fileId)
        readCached(path)?.let {
            hits.incrementAndGet()
            return it
        }
        misses.incrementAndGet()
        return upstreamSlots.withPermit {
            // 等锁期间可能已被其他请求落盘，二次确认避免重复拉取
            readCached(path)?.let {
                hits.incrementAndGet()
                return@withPermit it
            }
            fetch(fileId, path)
        }
    }

    private suspend fun fetch(fileId: String, path: Path): Thumbnail? {
        var url = linkFor(fileId, forceRefresh = false) ?: return fail(fileId, "没有可用的直链（未在最近一次目录列表中出现）")
        var response = request(url)
        if (response == null || !response.status.isSuccess()) {
            // 直链可能过期或被拒：丢掉后按父目录重列一次，只有拿到不同地址才重试
            val refreshed = linkFor(fileId, forceRefresh = true)
            if (refreshed != null && refreshed != url) {
                url = refreshed
                response = request(url)
            }
        }
        if (response == null || !response.status.isSuccess()) {
            return fail(fileId, "上游返回 ${response?.status?.value ?: "请求失败"}")
        }
        val bytes = readLimited(response) ?: return fail(fileId, "响应为空或超过 ${MAX_THUMB_BYTES / 1024 / 1024} MiB")
        val contentType = sniff(bytes) ?: return fail(fileId, "响应不是可识别的图片格式")
        store(path, bytes)
        upstreamOk.incrementAndGet()
        return Thumbnail(bytes, contentType)
    }

    private fun fail(fileId: String, reason: String): Thumbnail? {
        upstreamFailures.incrementAndGet()
        PikoLog.w(TAG, "缩略图获取失败（$fileId）：$reason")
        return null
    }

    private suspend fun request(url: String): HttpResponse? = runCatching {
        client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "image/avif,image/webp,image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5")
            header(HttpHeaders.Referrer, "https://mypikpak.com/")
        }
    }.onFailure { PikoLog.w(TAG, "拉取缩略图失败：${it.message}") }.getOrNull()

    /**
     * 解析直链：优先用列表缓存的地址；强制刷新时用缓存的 parentId 重列一次父目录。
     * 完全没有记录（服务重启后的第一批请求）时走 [recoverLink]：详情拿 parentId + 重列父目录。
     */
    private suspend fun linkFor(fileId: String, forceRefresh: Boolean): String? {
        val cached = links[fileId]
        if (!forceRefresh && cached != null && cached.url.isNotBlank()) return cached.url
        val parentId = cached?.parentId
        if (parentId != null) {
            val page = reList(parentId, fresh = forceRefresh) ?: return null
            remember(page)
            return links[fileId]?.url?.takeIf { it.isNotBlank() }
        }
        // 刷新场景下没有 parentId 可用，重试没有意义；冷启动才允许查详情兜底
        if (forceRefresh) return null
        return recoverLink(fileId)
    }

    /**
     * 冷启动兜底：详情只用来拿 parentId（SDK 的 FileDetail 没有 thumbnail_link），
     * 再重列父目录拿回直链。并发限 [RECOVERY_PARALLELISM]，失败进 60s 负缓存，
     * 避免缩略图缺失时反复请求第三方。
     */
    private suspend fun recoverLink(fileId: String): String? {
        val until = negativeUntil[fileId]
        if (until != null && until > System.currentTimeMillis()) return null
        return recoverySlots.withPermit {
            links[fileId]?.url?.takeIf { it.isNotBlank() }?.let { return@withPermit it }
            coldLookups.incrementAndGet()
            val parentId = runCatching { fileDetail(fileId) }
                .onFailure { PikoLog.w(TAG, "缩略图兜底查详情失败（$fileId）：${it.message}") }
                .getOrNull()
                ?.parentId
                ?.takeIf { it.isNotBlank() }
            if (parentId == null) {
                markNegative(fileId)
                return@withPermit null
            }
            val page = reList(parentId, fresh = false)
            if (page == null) {
                markNegative(fileId)
                return@withPermit null
            }
            remember(page)
            val link = links[fileId]?.url?.takeIf { it.isNotBlank() }
            if (link == null) {
                markNegative(fileId)
                return@withPermit null
            }
            recovered.incrementAndGet()
            link
        }
    }

    /** 重列父目录；非强制刷新时 30s 内复用同一页，重启后整屏缩略图只换一次列表。 */
    private suspend fun reList(parentId: String, fresh: Boolean): List<FileStat>? {
        val now = System.currentTimeMillis()
        if (!fresh) {
            listingCache[parentId]?.takeIf { it.expiresAt > now }?.let { return it.files }
        }
        val files = listFolder(parentId) ?: return null
        if (listingCache.size >= LISTING_CACHE_ENTRIES) listingCache.clear()
        listingCache[parentId] = CachedListing(now + LISTING_TTL_MILLIS, files)
        return files
    }

    private fun markNegative(fileId: String) {
        val now = System.currentTimeMillis()
        if (negativeUntil.size >= NEGATIVE_CACHE_ENTRIES) negativeUntil.clear()
        negativeUntil[fileId] = now + NEGATIVE_TTL_MILLIS
    }

    private fun readCached(path: Path): Thumbnail? {
        if (!Files.isRegularFile(path)) return null
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
        val contentType = sniff(bytes)
        if (contentType == null) {
            // 半截文件/非图片：删掉当未命中，下一次重新拉
            runCatching { Files.deleteIfExists(path) }
            cachedEntries.decrementAndGet()
            cachedBytes.addAndGet(-bytes.size.toLong())
            return null
        }
        runCatching { Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis())) }
        return Thumbnail(bytes, contentType)
    }

    private fun store(path: Path, bytes: ByteArray) {
        runCatching {
            Files.createDirectories(directory)
            val existed = Files.isRegularFile(path)
            val temp = Files.createTempFile(directory, ".thumb-", ".tmp")
            try {
                Files.write(temp, bytes)
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
            if (!existed) {
                cachedEntries.incrementAndGet()
                cachedBytes.addAndGet(bytes.size.toLong())
            }
            evictIfNeeded()
        }.onFailure {
            writeFailures.incrementAndGet()
            PikoLog.w(TAG, "写入缩略图缓存失败：${it.message}")
        }
    }

    private fun evictIfNeeded() {
        if (cachedBytes.get() <= maxBytes && cachedEntries.get() <= maxEntries) return
        synchronized(evictLock) {
            if (cachedBytes.get() <= maxBytes && cachedEntries.get() <= maxEntries) return
            val entries = mutableListOf<Triple<Path, Long, Long>>()
            runCatching {
                Files.newDirectoryStream(directory, "*.thumb").use { stream ->
                    for (path in stream) {
                        val size = runCatching { Files.size(path) }.getOrDefault(0L)
                        val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
                        entries += Triple(path, size, modified)
                    }
                }
            }
            var total = entries.sumOf { it.second }
            var count = entries.size.toLong()
            // mtime 命中时会被刷新，所以按 mtime 升序删就是 LRU
            entries.sortBy { it.third }
            for ((path, size, _) in entries) {
                if (total <= maxBytes && count <= maxEntries) break
                if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) {
                    total -= size
                    count -= 1
                    evictions.incrementAndGet()
                }
            }
            cachedBytes.set(total)
            cachedEntries.set(count)
        }
    }

    private fun ensureScanned() {
        if (!scanned.compareAndSet(false, true)) return
        var total = 0L
        var count = 0L
        runCatching {
            Files.newDirectoryStream(directory, "*.thumb").use { stream ->
                for (path in stream) {
                    total += runCatching { Files.size(path) }.getOrDefault(0L)
                    count += 1
                }
            }
        }
        cachedBytes.set(total)
        cachedEntries.set(count)
    }

    private fun pathFor(fileId: String): Path = directory.resolve("${sha256Hex(fileId)}.thumb")

    override fun close() {
        runCatching { client.close() }
    }

    private companion object {
        const val TAG = "Thumbnail"
        const val DEFAULT_MAX_BYTES = 128L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 4096
        const val MAX_THUMB_BYTES = 3 * 1024 * 1024
        const val UPSTREAM_PARALLELISM = 4
        const val RECOVERY_PARALLELISM = 2
        const val NEGATIVE_TTL_MILLIS = 60_000L
        const val NEGATIVE_CACHE_ENTRIES = 2048
        const val LISTING_TTL_MILLIS = 30_000L
        const val LISTING_CACHE_ENTRIES = 64
        const val CACHE_CONTROL = "private, max-age=31536000, immutable"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36 FNPak/1.0"

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun sniff(bytes: ByteArray): String? {
            if (bytes.size < 12) return null
            fun at(index: Int) = bytes[index].toInt() and 0xFF
            if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "image/jpeg"
            if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "image/png"
            if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) return "image/gif"
            if (at(0) == 0x42 && at(1) == 0x4D) return "image/bmp"
            if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
            ) {
                return "image/webp"
            }
            if (at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70) {
                val brand = String(bytes, 8, 4, Charsets.US_ASCII)
                if (brand.startsWith("avif") || brand.startsWith("avis")) return "image/avif"
            }
            return null
        }

        suspend fun readLimited(response: HttpResponse): ByteArray? {
            val channel = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > MAX_THUMB_BYTES) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray().takeIf { it.isNotEmpty() }
        }
    }
}
