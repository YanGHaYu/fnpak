package dev.piko.server.access

import dev.piko.server.ErrorDetail
import dev.piko.server.ErrorResponse
import dev.piko.server.access.AccessControl.Companion.COOKIE_NAME
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.respond

/** 不需要会话的路径：存活探针与口令自身的登录/状态查询。 */
private val PUBLIC_PATHS = setOf("/api/health", "/api/access/login", "/api/access/state")

/**
 * 中央拦截：所有 `/api` 前缀的请求除白名单外一律要求有效会话 Cookie。
 *
 * 静态页与资源不拦，否则用户连输入口令的页面都打不开。未通过时统一回
 * `401 {"error":{"code":"access_denied","message":"需要访问口令"}}`，前端据此切到口令卡片。
 */
fun accessGuard(access: AccessControl) = createApplicationPlugin("AccessGuard") {
    onCall { call ->
        val path = call.request.path()
        if (!path.startsWith("/api/") || path in PUBLIC_PATHS) return@onCall
        if (access.isValidSession(call.request.cookies[COOKIE_NAME])) return@onCall
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("access_denied", "需要访问口令")))
    }
}
