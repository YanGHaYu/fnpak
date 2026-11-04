package dev.piko.server.auth

import dev.piko.server.AuthStateResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.TokenImportRequest
import dev.piko.server.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * 认证相关路由。M1 只做「令牌导入」：无头机器上没有浏览器窗口，账号密码登录会撞上
 * `/v1/shield/captcha/init`，验证码得在网页里完成——那套流程留到 M2。
 */
fun Route.authRoutes(graph: ServiceGraph) {
    route("/api/auth") {
        get("/state") {
            val account = graph.clientManager.currentClient.value?.account
            call.respond(
                AuthStateResponse(
                    authenticated = account != null,
                    account = account,
                    displayName = account?.let { name -> graph.clientManager.accounts.value.find(name)?.displayName },
                    initializing = graph.isInitializing,
                ),
            )
        }

        /**
         * 导入已有会话，绕开验证码。会话写进 `$piko.home/accounts/`，由 SDK 负责后续刷新。
         * 204 表示导入成功；401 表示服务端拒绝了这个令牌（不是「格式不对」）。
         */
        post("/token") {
            val body = runCatching { call.receive<TokenImportRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "bad_request", "请求体必须是 JSON")
            val account = body.account.trim()
            val accessToken = body.accessToken.trim()
            if (account.isEmpty() || accessToken.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_field", "account 与 accessToken 不能为空")
            }
            graph.clientManager.loginWithToken(account, accessToken, body.refreshToken.trim()).fold(
                // 204：没有响应体。Ktor 3 没有 respond(status) 的单参重载，用空文本显式给出状态
                onSuccess = { call.respondText("", status = HttpStatusCode.NoContent) },
                onFailure = {
                    call.respondError(
                        HttpStatusCode.Unauthorized,
                        "token_rejected",
                        it.message ?: it::class.simpleName ?: "令牌被拒绝",
                    )
                },
            )
        }

        post("/logout") {
            graph.clientManager.logout().join()
            call.respondText("", status = HttpStatusCode.NoContent)
        }

        // TODO(M2)：验证码登录。pikpak-kotlin 是否把挑战图片与校验回调暴露给调用方尚未实测；
        // 可用时在这里加 challenge 暂存（TTL 5 分钟、一次性消费），不可用时保持 501 并引导用户去导入令牌。
        post("/captcha/init") {
            call.respondError(HttpStatusCode.NotImplemented, "not_implemented", "M1 未实现验证码登录，请用 /api/auth/token 导入令牌")
        }
        post("/login") {
            call.respondError(HttpStatusCode.NotImplemented, "not_implemented", "M1 未实现账号密码登录，请用 /api/auth/token 导入令牌")
        }
    }
}
