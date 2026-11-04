package dev.piko.server.media

import dev.piko.server.ServiceGraph
import dev.piko.server.respondError
import dev.piko.server.drive.requireLogin
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route

/**
 * M3 媒体路由：
 * - `info`：元数据 + 本次选路（redirect / proxy）与原因，不吐签名直链；
 * - `stream`：GET/HEAD + 单段 Range。直链模式 302，转发模式服务端字节流转发。
 *
 * HEAD 与 GET 共用分支（Ktor 允许 HEAD 命中 GET 路由），内部按 HTTP 方法区分，HEAD 不读上游字节。
 */
fun Route.mediaRoutes(graph: ServiceGraph) {
    route("/api/media/{id}") {
        get("/info") {
            if (graph.requireLogin(call)) return@get
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, "missing_field", "缺少文件 id")
            }
            graph.mediaService.respondInfo(call, id)
        }
        get("/stream") {
            if (graph.requireLogin(call)) return@get
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, "missing_field", "缺少文件 id")
            }
            graph.mediaService.respondStream(call = call, fileId = id, headOnly = false)
        }
        // Ktor 默认不会把 HEAD 交给 GET 路由；显式注册才能保证 HEAD 只回头部
        head("/stream") {
            if (graph.requireLogin(call)) return@head
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                return@head call.respondError(HttpStatusCode.BadRequest, "missing_field", "缺少文件 id")
            }
            graph.mediaService.respondStream(call = call, fileId = id, headOnly = true)
        }
    }
}
