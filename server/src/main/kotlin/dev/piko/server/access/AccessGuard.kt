package dev.piko.server.access

import dev.piko.server.ErrorDetail
import dev.piko.server.ErrorResponse
import dev.piko.server.access.AccessControl.Companion.COOKIE_NAME
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.respond

/** 不需要会话的路径：存活探针、口令自身的登录/状态查询，以及首次设置向导的两个接口。 */
private val PUBLIC_PATHS = setOf(
    "/api/health",
    "/api/access/login",
    "/api/access/state",
    "/api/setup/state",
    "/api/setup/access-token",
)

/**
 * 中央拦截：所有 `/api` 前缀的请求除白名单外一律要求有效会话 Cookie。
 *
 * 静态页与资源不拦，否则用户连输入口令的页面都打不开。未通过时统一回
 * `401 {"error":{"code":"access_denied","message":"需要访问口令"}}`，前端据此切到口令卡片。
 * 处于无口令状态（首次运行）时，除设置接口外回 `401 {"error":{"code":"setup_required",...}}`，
 * 前端据此切到「首次设置访问口令」页。
 */
fun accessGuard(access: AccessControl) = createApplicationPlugin("AccessGuard") {
    onCall { call ->
        val path = call.request.path()
        if (!path.startsWith("/api/") || path in PUBLIC_PATHS) return@onCall
        if (access.needsSetup) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(
                    ErrorDetail(
                        "setup_required",
                        "尚未设置访问口令，请先在首次设置向导里设置口令。设置接口：POST /api/setup/access-token",
                    ),
                ),
            )
            return@onCall
        }
        if (access.isValidSession(call.request.cookies[COOKIE_NAME])) return@onCall
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("access_denied", "需要访问口令")))
    }
}
