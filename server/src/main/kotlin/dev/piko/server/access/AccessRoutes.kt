package dev.piko.server.access

import dev.piko.server.AccessLoginRequest
import dev.piko.server.AccessStateResponse
import dev.piko.server.ErrorDetail
import dev.piko.server.ErrorResponse
import dev.piko.server.access.AccessControl.Companion.COOKIE_NAME
import dev.piko.server.access.AccessControl.Companion.SESSION_TTL_SECONDS
import dev.piko.server.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * 口令登录。会话只放内存：进程重启即全部失效，用户重新输入一次即可。
 *
 * 失败的路径回同一个错误码，不区分「空口令」与「口令错」，也不做延时——挡的是同网段随手打开的人，
 * 真要防暴力猜解得靠反代那层的限流。
 */
fun Route.accessRoutes(access: AccessControl) {
    route("/api/access") {
        get("/state") {
            call.respond(
                AccessStateResponse(
                    required = true,
                    authenticated = access.isValidSession(call.request.cookies[COOKIE_NAME]),
                ),
            )
        }

        post("/login") {
            val body = runCatching { call.receive<AccessLoginRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("bad_request", "请求体必须是 JSON")))
            if (!access.verify(body.passcode)) {
                return@post call.respondError(HttpStatusCode.Unauthorized, "access_denied", "访问口令不正确")
            }
            val token = access.issueSession()
            call.setSessionCookie(token, SESSION_TTL_SECONDS)
            call.respond(AccessStateResponse(required = true, authenticated = true))
        }

        post("/logout") {
            access.revoke(call.request.cookies[COOKIE_NAME])
            call.setSessionCookie("", 0)
            call.respondText("", status = HttpStatusCode.NoContent)
        }
    }
}

/**
 * 会话 Cookie：`HttpOnly` 防脚本读取，`SameSite=Lax` 防跨站带上；
 * 不加 `Secure`——局域网里是明文 http，加了反而登录不上，TLS 由反代提供。
 *
 * 直接拼 Set-Cookie 而不是用 Ktor 的 Cookie 对象：后者会给每个值补一个 `$x-enc=…`
 * 扩展属性，浏览器虽然忽略，但抓包时容易让人误以为多了个开关。
 */
private fun ApplicationCall.setSessionCookie(value: String, maxAgeSeconds: Int) {
    response.headers.append(
        HttpHeaders.SetCookie,
        "$COOKIE_NAME=$value; Max-Age=$maxAgeSeconds; Path=/; HttpOnly; SameSite=Lax",
    )
}
