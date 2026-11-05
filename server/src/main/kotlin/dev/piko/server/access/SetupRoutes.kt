package dev.piko.server.access

import dev.piko.server.SetupAccessTokenRequest
import dev.piko.server.SetupAccessTokenResponse
import dev.piko.server.SetupStateResponse
import dev.piko.server.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * 首次启动设置口令向导。两个接口都在 [accessGuard] 的白名单里（否则前端拿不到状态、也提交不了口令）。
 *
 * - `GET /api/setup/state`：免口令，前端据此决定显示向导还是口令卡；
 * - `POST /api/setup/access-token`：仅在无口令状态 + 启动窗口内允许；成功后直接建立会话并写 Cookie。
 */
fun Route.setupRoutes(access: AccessControl, setup: AccessSetup) {
    route("/api/setup") {
        get("/state") {
            call.respond(
                SetupStateResponse(
                    needsSetup = access.needsSetup,
                    reason = setup.reason,
                    setupWindowOpen = if (access.needsSetup) access.setupWindowOpen() else true,
                    setupWindowMinutes = access.setupWindowMinutes,
                ),
            )
        }

        post("/access-token") {
            if (!access.needsSetup) {
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "setup_already_completed",
                    "访问口令已设置，无需再次进入首次设置；如需更换口令请登录后到 /settings 修改",
                )
            }
            if (!access.setupWindowOpen()) {
                return@post call.respondError(
                    HttpStatusCode.Forbidden,
                    "setup_window_closed",
                    "首次设置窗口已关闭（服务启动后 ${access.setupWindowMinutes} 分钟内有效）。" +
                        "请用命令行重置：删除数据目录下的 access-token 文件（若存在）后重启服务，即可重新进入设置向导。",
                )
            }
            val body = runCatching { call.receive<SetupAccessTokenRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "bad_request", "请求体必须是 JSON")
            val passcode = body.passcode.trim()
            val confirm = body.confirmPasscode.trim()
            if (confirm.isNotEmpty() && passcode != confirm) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_passcode", "两次输入的口令不一致")
            }
            AccessControl.validatePasscode(passcode)?.let { problem ->
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_passcode", problem)
            }
            val fingerprint = try {
                access.completeSetup(
                    raw = passcode,
                    sourceIp = call.request.origin.remoteAddress,
                    userAgent = call.request.headers["User-Agent"],
                )
            } catch (error: Exception) {
                return@post call.respondError(
                    HttpStatusCode.InternalServerError,
                    "token_write_failed",
                    "写入口令文件失败：${error::class.simpleName}: ${error.message}",
                )
            }
            val token = access.issueSession()
            call.setSessionCookie(token, AccessControl.SESSION_TTL_SECONDS)
            call.respond(SetupAccessTokenResponse(ok = true, fingerprint = fingerprint, needsSetup = false))
        }
    }
}
