package dev.piko.server.media

import dev.piko.server.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * 媒体路由占位。M3 才实现真正的字节流，M1 只保证路径与错误码稳定，前端可以先接上。
 *
 * 后续二选一（见 IMPLEMENTATION_PLAN §3.6）：
 * 1. 服务端转发：用 `PikoMediaRepository` 拿到字节源，自己写 RangeResponder 输出 206/416。
 *    最可控，也避开了 `PikoMediaProxy` 只绑回环地址的问题。
 * 2. 直链 302：`PikoDriveRepository.originalImageUrl` / `FileDetail.downloadUrl` 拿签名直链后重定向。
 *    省带宽，但把签名 URL 交给浏览器，有效期一过就要重新取。
 *
 * 不做的是把 `PreparedPlayback.proxyUrl` 直接吐给前端：那是一次性的 `127.0.0.1` 会话，局域网取不到。
 */
fun Route.mediaRoutes() {
    route("/api/media/{id}") {
        get("/info") {
            call.respondError(HttpStatusCode.NotImplemented, "not_implemented", "媒体信息尚未实现（M3）")
        }
        // HEAD 与 GET 走同一分支：Ktor 对 HEAD 会自动去掉响应体
        get("/stream") {
            call.respondError(HttpStatusCode.NotImplemented, "not_implemented", "媒体流尚未实现（M3，将支持 Range/206）")
        }
    }
}
