package dev.piko.server.web

import dev.piko.server.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// 只托管单页的三个文件，白名单写死：不做目录映射，也就没有路径穿越问题。
private val ASSETS = mapOf(
    "index.html" to ContentType.Text.Html,
    "app.js" to ContentType.Text.JavaScript,
    "app.css" to ContentType.Text.CSS,
)

/**
 * 静态页。资源打在 server 的 jar 里（`resources/web`），installDist 之后无需额外部署目录。
 * M1 的页面只做「导入令牌 → 列目录」，不引前端框架、不依赖 CDN，NAS 离线也能打开。
 */
fun Route.staticRoutes() {
    get("/") { call.respondAsset("index.html") }
    for (name in ASSETS.keys) {
        get("/$name") { call.respondAsset(name) }
    }
}

private suspend fun ApplicationCall.respondAsset(name: String) {
    val bytes = StaticAssets::class.java.classLoader.getResourceAsStream("web/$name")?.use { it.readBytes() }
    if (bytes == null) {
        respondError(HttpStatusCode.NotFound, "asset_missing", "打包产物里缺少 web/$name")
        return
    }
    respondBytes(bytes, ASSETS.getValue(name))
}

private object StaticAssets
