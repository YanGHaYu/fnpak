package dev.piko.server.media

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.FileDetail
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.utils.io.readAvailable
import dev.piko.server.MediaInfoResponse
import dev.piko.server.respondError
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Media"

/** 直链探测结果缓存时间：签名直链有时效，判断也不能缓存太久。 */
private const val PROBE_TTL_MILLIS = 60_000L

/** 文件详情缓存时间：Range 请求会很密集，不能每次都打 PikPak 的 getFile。 */
private const val DETAIL_TTL_MILLIS = 90_000L

/** 直链探测与代理转发都用浏览器 UA，尽量贴近真实播放器拿到的响应。 */
private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

sealed interface MediaMode {
    data class Redirect(val reason: String) : MediaMode
    data class Proxy(val reason: String) : MediaMode
}

data class MediaMeta(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val extension: String,
    val directUrl: String,
    val mode: MediaMode,
)

/**
 * 取元数据 + 判断本次走直链还是转发。
 *
 * 直链判断只做一次轻量 HEAD：2xx 且不是 HTML 就当可直接播；否则（403/404、被绑 UA、
 * 返回错误页、探测异常）回落转发。判断按文件缓存 60 秒，避免每个 Range 请求都探测。
 */
class MediaService(private val repository: PikoDriveRepository) : AutoCloseable {
    private data class CachedDetail(val detail: FileDetail, val expiresAt: Long)
    private data class CachedMode(val mode: MediaMode, val expiresAt: Long)

    private val details = ConcurrentHashMap<String, CachedDetail>()
    private val modes = ConcurrentHashMap<String, CachedMode>()

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 45_000
        }
        expectSuccess = false
    }

    suspend fun meta(fileId: String): Result<MediaMeta> = runCatching {
        val detail = detail(fileId)
        val url = detail.downloadUrl?.takeIf { it.isNotBlank() }
            ?: detail.webContentLink?.takeIf { it.isNotBlank() }
            ?: error("PikPak 没有返回可用的下载直链")
        val size = detail.sizeBytes.takeIf { it > 0 } ?: detail.size.toLongOrNull() ?: 0L
        val mime = preferredMimeType(detail)
        MediaMeta(
            id = detail.id,
            name = detail.name,
            sizeBytes = size,
            mimeType = mime,
            extension = detail.fileExtension.ifBlank { detail.name.substringAfterLast('.', "").lowercase() },
            directUrl = url,
            mode = modeOf(fileId, url),
        )
    }.onFailure { PikoLog.w(TAG, "取媒体信息失败，文件=$fileId，${it::class.simpleName}: ${it.message}") }

    /**
     * 输出 `info`：只给模式与元数据，不把签名直链吐给浏览器。
     */
    suspend fun respondInfo(call: ApplicationCall, fileId: String) {
        meta(fileId).fold(
            onSuccess = { meta ->
                val mode = meta.mode
                call.respond(
                    MediaInfoResponse(
                        id = meta.id,
                        name = meta.name,
                        sizeBytes = meta.sizeBytes,
                        mimeType = meta.mimeType,
                        fileExtension = meta.extension,
                        mode = modeName(mode),
                        modeReason = reasonOf(mode),
                        rangeSupported = true,
                    ),
                )
            },
            onFailure = {
                // 异常文本可能带签名直链，不能透给浏览器
                call.respondError(HttpStatusCode.BadGateway, "media_unavailable", "拿不到可用直链，请稍后重试")
            },
        )
    }

    /**
     * 流：先按 Range 做本地校验（越界 416），直链模式 302，转发模式把上游 200/206 连同
     * Content-Range 一起回给浏览器。HEAD 只回头部，不建立上游 GET，避免为一次探测下载整个文件。
     *
     * `?mode=proxy` 可强制走转发：直链被 UA/Referer 绑定时，前端能据此自救。
     */
    suspend fun respondStream(call: ApplicationCall, fileId: String, headOnly: Boolean) {
        val meta = meta(fileId).getOrElse {
            return call.respondError(HttpStatusCode.BadGateway, "media_unavailable", "拿不到可用直链，请稍后重试")
        }

        if (headOnly) {
            // HEAD 不 302，也不建立上游 GET：本地按 Range 算好头部，诊断脚本能直接看到 200/206/416
            val headRange = parseRange(call.request.headers[HttpHeaders.Range], meta.sizeBytes)
            val status = when (headRange) {
                is RangeDecision.Slice -> HttpStatusCode.PartialContent
                RangeDecision.Unsatisfiable -> HttpStatusCode.RequestedRangeNotSatisfiable
                RangeDecision.Full -> HttpStatusCode.OK
            }
            val slice = (headRange as? RangeDecision.Slice)?.range
            call.respond(
                object : OutgoingContent.NoContent() {
                    override val status = status
                    override val contentType = ContentType.parse(meta.mimeType)
                    override val contentLength = when {
                        // 416 没有响应体；不要把整个文件长度写进 Content-Length
                        headRange is RangeDecision.Unsatisfiable -> 0L
                        slice != null -> slice.endInclusive - slice.start + 1
                        meta.sizeBytes > 0 -> meta.sizeBytes
                        else -> null
                    }
                    override val headers = Headers.build {
                        append(HttpHeaders.AcceptRanges, "bytes")
                        append("X-Piko-Media-Mode", modeName(meta.mode))
                        if (slice != null) {
                            append(HttpHeaders.ContentRange, "bytes ${slice.start}-${slice.endInclusive}/${meta.sizeBytes}")
                        }
                        if (headRange is RangeDecision.Unsatisfiable) {
                            append(HttpHeaders.ContentRange, "bytes */${meta.sizeBytes}")
                        }
                    }
                },
            )
            return
        }

        val forcedProxy = call.request.queryParameters["mode"] == "proxy"
        val mode = if (forcedProxy) MediaMode.Proxy("forced_by_query") else meta.mode

        val range = parseRange(call.request.headers[HttpHeaders.Range], meta.sizeBytes)
        if (range is RangeDecision.Unsatisfiable) {
            call.response.header(HttpHeaders.ContentRange, "bytes */${meta.sizeBytes}")
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            call.respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
            return
        }

        if (mode is MediaMode.Redirect) {
            // 302 之后浏览器直接向 PikPak 发 Range；这里不读上游，省带宽
            call.response.header(HttpHeaders.Location, meta.directUrl)
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            call.response.header("X-Piko-Media-Mode", "redirect")
            call.respondText("", status = HttpStatusCode.Found)
            return
        }

        proxyStream(call, meta, range)
    }

    private suspend fun proxyStream(call: ApplicationCall, meta: MediaMeta, range: RangeDecision) {
        val slice = (range as? RangeDecision.Slice)?.range
        val upstreamRange = slice?.let { "bytes=${it.start}-${it.endInclusive}" }
        PikoLog.i(TAG, "转发流：文件=${meta.id}，range=${upstreamRange ?: "full"}")

        try {
            client.prepareGet(meta.directUrl) {
                header(HttpHeaders.UserAgent, BROWSER_UA)
                header(HttpHeaders.Accept, "*/*")
                if (upstreamRange != null) header(HttpHeaders.Range, upstreamRange)
            }.execute { upstream ->
                val status = upstream.status
                if (status.value !in 200..299) {
                    val snippet = runCatching { upstream.bodyAsText().take(300) }.getOrDefault("")
                    PikoLog.w(TAG, "上游拒绝：文件=${meta.id}，HTTP ${status.value}，bodyLen=${snippet.length}")
                    call.respondError(
                        HttpStatusCode.BadGateway,
                        "media_upstream_${status.value}",
                        "PikPak 直链返回 HTTP ${status.value}",
                    )
                    return@execute
                }

                val upstreamLength = upstream.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                // 上游可能忽略 Range 直接给 200：这时如实回 200 全量，不能假称 206
                val finalStatus = if (slice != null && status == HttpStatusCode.PartialContent) {
                    HttpStatusCode.PartialContent
                } else {
                    HttpStatusCode.OK
                }
                val contentType = contentTypeOf(meta, upstream.headers[HttpHeaders.ContentType])

                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                call.response.header("X-Piko-Media-Mode", "proxy")
                // 长度交给 respondOutputStream 一起设置：手动写 Header 会同时产生 chunked，
                // 两个长度来源并存对播放器的 Range 计算不友好
                val declaredLength: Long? = if (finalStatus == HttpStatusCode.PartialContent && slice != null) {
                    call.response.header(HttpHeaders.ContentRange, "bytes ${slice.start}-${slice.endInclusive}/${meta.sizeBytes}")
                    slice.endInclusive - slice.start + 1
                } else {
                    upstreamLength ?: meta.sizeBytes.takeIf { it > 0 }
                }

                call.respondOutputStream(status = finalStatus, contentType = contentType, contentLength = declaredLength) {
                    val channel = upstream.bodyAsChannel()
                    val buffer = ByteArray(16 * 1024)
                    try {
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            write(buffer, 0, read)
                        }
                        flush()
                    } finally {
                        runCatching { channel.cancel(null) }
                    }
                }
            }
        } catch (error: Throwable) {
            // 浏览器拖动进度条会主动断开：写响应失败是正常路径，不是故障
            val disconnected = error is kotlinx.coroutines.CancellationException ||
                error::class.simpleName in setOf("ChannelWriteException", "ClosedWriteChannelException")
            if (disconnected) {
                PikoLog.i(TAG, "客户端断开，已释放上游连接：文件=${meta.id}")
            } else {
                PikoLog.w(TAG, "转发流异常：文件=${meta.id}，${error::class.simpleName}")
                runCatching {
                    call.respondError(HttpStatusCode.BadGateway, "media_stream_failed", "读取 PikPak 直链失败")
                }
            }
        }
    }

    private suspend fun detail(fileId: String): FileDetail {
        val now = System.currentTimeMillis()
        details[fileId]?.takeIf { it.expiresAt > now }?.let { return it.detail }
        val detail = repository.getFileDetail(fileId).getOrThrow()
        details[fileId] = CachedDetail(detail, now + DETAIL_TTL_MILLIS)
        return detail
    }

    private suspend fun modeOf(fileId: String, url: String): MediaMode {
        val now = System.currentTimeMillis()
        modes[fileId]?.takeIf { it.expiresAt > now }?.let { return it.mode }
        val mode = probe(url)
        modes[fileId] = CachedMode(mode, now + PROBE_TTL_MILLIS)
        PikoLog.i(TAG, "直链探测：文件=$fileId，${modeName(mode)}（${reasonOf(mode)}）")
        return mode
    }

    private suspend fun probe(url: String): MediaMode = try {
        val response = client.head(url) {
            header(HttpHeaders.UserAgent, BROWSER_UA)
            header(HttpHeaders.Accept, "*/*")
        }
        val status = response.status.value
        val type = response.headers[HttpHeaders.ContentType].orEmpty()
        when {
            status !in 200..299 -> MediaMode.Proxy("direct_probe_http_$status")
            type.startsWith("text/html") || type.startsWith("application/json") ->
                MediaMode.Proxy("direct_probe_${type.substringBefore(';')}")
            else -> MediaMode.Redirect("direct_probe_http_$status")
        }
    } catch (error: Throwable) {
        MediaMode.Proxy("direct_probe_failed_${error::class.simpleName}")
    }

    override fun close() {
        client.close()
    }

    private fun preferredMimeType(detail: FileDetail): String {
        val declared = detail.mimeType.trim()
        if (declared.isNotEmpty() && !declared.equals("application/octet-stream", ignoreCase = true)) return declared
        return when (detail.fileExtension.lowercase().ifBlank { detail.name.substringAfterLast('.', "").lowercase() }) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun contentTypeOf(meta: MediaMeta, upstream: String?): ContentType {
        // 上游常把直链回成 octet-stream；播放要靠正确的 video/* 类型，优先用详情里的声明
        if (meta.mimeType.startsWith("video/") || meta.mimeType.startsWith("audio/")) {
            return runCatching { ContentType.parse(meta.mimeType) }.getOrDefault(ContentType.Application.OctetStream)
        }
        val fromUpstream = upstream?.substringBefore(';')?.trim().orEmpty()
        if (fromUpstream.startsWith("video/") || fromUpstream.startsWith("audio/")) {
            return runCatching { ContentType.parse(fromUpstream) }.getOrDefault(ContentType.Application.OctetStream)
        }
        return runCatching { ContentType.parse(meta.mimeType) }.getOrDefault(ContentType.Application.OctetStream)
    }

    private fun modeName(mode: MediaMode): String = when (mode) {
        is MediaMode.Redirect -> "redirect"
        is MediaMode.Proxy -> "proxy"
    }

    private fun reasonOf(mode: MediaMode): String = when (mode) {
        is MediaMode.Redirect -> mode.reason
        is MediaMode.Proxy -> mode.reason
    }
}

sealed interface RangeDecision {
    data class Slice(val range: ByteRange) : RangeDecision
    data object Full : RangeDecision
    data object Unsatisfiable : RangeDecision
}

data class ByteRange(val start: Long, val endInclusive: Long)

/**
 * 只支持单段 Range（浏览器播放器实际只发单段）。多段按 RFC 允许忽略 Range 回 200 全量；
 * 越界（start 超过文件尾、end < start、`bytes=-0`）回 416。
 */
internal fun parseRange(header: String?, total: Long): RangeDecision {
    if (header.isNullOrBlank() || total <= 0) return RangeDecision.Full
    val raw = header.trim()
    if (!raw.startsWith("bytes=", ignoreCase = true)) return RangeDecision.Full
    val spec = raw.removePrefix("bytes=").removePrefix("bytes=")
    if (spec.contains(',')) return RangeDecision.Full
    val parts = spec.split('-', limit = 2)
    if (parts.size != 2) return RangeDecision.Full

    val startText = parts[0].trim()
    val endText = parts[1].trim()
    return when {
        startText.isEmpty() -> {
            val suffix = endText.toLongOrNull() ?: return RangeDecision.Full
            when {
                suffix <= 0 -> RangeDecision.Unsatisfiable
                else -> {
                    val start = (total - suffix).coerceAtLeast(0)
                    RangeDecision.Slice(ByteRange(start, total - 1))
                }
            }
        }
        else -> {
            val start = startText.toLongOrNull() ?: return RangeDecision.Full
            val end = if (endText.isEmpty()) total - 1 else (endText.toLongOrNull() ?: return RangeDecision.Full)
            when {
                start >= total -> RangeDecision.Unsatisfiable
                end < start -> RangeDecision.Unsatisfiable
                else -> RangeDecision.Slice(ByteRange(start, end.coerceAtMost(total - 1)))
            }
        }
    }
}
