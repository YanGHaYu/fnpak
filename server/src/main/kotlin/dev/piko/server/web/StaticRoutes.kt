package dev.piko.server.web

import dev.piko.server.respondError
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head

private val CONTENT_TYPES = mapOf(
    "html" to ContentType.Text.Html,
    "js" to ContentType.Text.JavaScript,
    "mjs" to ContentType.Text.JavaScript,
    "css" to ContentType.Text.CSS,
    "json" to ContentType.Application.Json,
    "svg" to ContentType.Image.SVG,
    "png" to ContentType.Image.PNG,
    "webp" to ContentType.Image.WEBP,
    "ico" to ContentType("image", "x-icon"),
    "woff2" to ContentType("font", "woff2"),
    "woff" to ContentType("font", "woff"),
    "ttf" to ContentType("font", "ttf"),
)

/**
 * 托管 Vue 构建产物（`resources/web`），并给单页路由兜底：
 * - `/api/...` 在路由表里先匹配；没匹配到的 /api 路径明确 404，不会被兜底成 index.html
 * - 其余路径先找同名静态文件；找不到且不像文件名（没有扩展名）就回 index.html，交给前端路由
 * - HEAD 与 GET 一并注册（Ktor 默认不给 GET 路由自动装 HEAD），返回同样的状态/类型/长度但不带 body
 *
 * 只按白名单拼资源名，不做目录映射，也不接受含 `..` 的路径。
 */
fun Route.staticRoutes() {
    get("/") { call.respondIndex(head = false) }
    head("/") { call.respondIndex(head = true) }
    get("/{path...}") { call.servePath(head = false) }
    head("/{path...}") { call.servePath(head = true) }
}

private suspend fun ApplicationCall.servePath(head: Boolean) {
    val parts = parameters.getAll("path").orEmpty().filter { it.isNotBlank() }
    if (parts.isEmpty()) {
        respondIndex(head)
        return
    }
    if (parts.first() == "api") {
        respondError(HttpStatusCode.NotFound, "not_found", "接口不存在")
        return
    }
    val relative = parts.joinToString("/")
    if (".." in relative) {
        respondError(HttpStatusCode.NotFound, "not_found", "路径不存在")
        return
    }
    val bytes = readAsset(relative)
    if (bytes != null) {
        respondAsset(relative, bytes, head)
        return
    }
    // 有扩展名说明是在要某个具体文件，缺了就是缺了；没有扩展名当作前端路由
    if ('.' in parts.last()) {
        respondError(HttpStatusCode.NotFound, "asset_missing", "打包产物里缺少 web/$relative")
        return
    }
    respondIndex(head)
}

private suspend fun ApplicationCall.respondIndex(head: Boolean) {
    val bytes = readAsset("index.html")
    if (bytes == null) {
        respondError(HttpStatusCode.NotFound, "asset_missing", "打包产物里缺少 web/index.html")
        return
    }
    // 入口页不缓存，重新打包后立刻生效；带哈希的资源文件才长缓存
    respondCached(bytes, ContentType.Text.Html, "no-cache", head)
}

private suspend fun ApplicationCall.respondAsset(name: String, bytes: ByteArray, head: Boolean) {
    val immutable = name.startsWith("assets/")
    respondCached(
        bytes,
        contentTypeOf(name),
        if (immutable) "public, max-age=31536000, immutable" else "no-cache",
        head,
    )
}

private suspend fun ApplicationCall.respondCached(
    bytes: ByteArray,
    contentType: ContentType,
    cacheControl: String,
    head: Boolean,
) {
    if (!head) {
        response.header(HttpHeaders.CacheControl, cacheControl)
        respondBytes(bytes, contentType)
        return
    }
    // HEAD 只回头，不重放几百 KB 的 body；长度/类型与 GET 保持一致（单份 header，避免 Content-Length 重复）
    respond(object : OutgoingContent.NoContent() {
        override val contentType: ContentType = contentType
        override val contentLength: Long = bytes.size.toLong()
        override val headers: Headers = Headers.build {
            append(HttpHeaders.CacheControl, cacheControl)
        }
    })
}

private fun contentTypeOf(name: String): ContentType {
    val extension = name.substringAfterLast('.', "").lowercase()
    return CONTENT_TYPES[extension] ?: ContentType.Application.OctetStream
}

private fun readAsset(name: String): ByteArray? =
    StaticAssets::class.java.classLoader.getResourceAsStream("web/$name")?.use { it.readBytes() }

private object StaticAssets
