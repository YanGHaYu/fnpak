package dev.piko.server.auth

import dev.piko.server.AuthStateResponse
import dev.piko.server.CaptchaInitRequest
import dev.piko.server.CaptchaInitResponse
import dev.piko.server.CaptchaRequiredResponse
import dev.piko.server.ErrorDetail
import dev.piko.server.LoginRequest
import dev.piko.server.ServiceGraph
import dev.piko.server.TokenImportRequest
import dev.piko.server.respondError
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.PikPakException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "AuthRoutes"

/** PikPak 的验证码是服务端风控令牌，不含图片；前端据此不显示图片框。 */
private const val NO_IMAGE_NOTE =
    "PikPak 本次下发的是风控令牌（约 5 分钟有效），没有图片验证码；登录失败可直接点刷新重试，或改用导入令牌。"

/**
 * 认证相关路由：登录态查询、令牌导入、账号密码登录与验证码初始化、退出。
 *
 * 账号密码登录走 SDK 的 `PikoClientManager.login`：它内部的 `AuthApi` 会在 signin 前自动调用
 * `/v1/shield/captcha/init` 并带上 `X-Captcha-Token`，遇到验证码错误还会自动刷新令牌重试一次。
 * 因此服务端不自己拼 signin 请求，只补上浏览器需要的那部分：挑战编号、TTL 与错误码映射。
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
                        "服务端拒绝了这个令牌，请确认 accessToken 与账号一致",
                    )
                },
            )
        }

        /**
         * 验证码初始化：真实调用 PikPak 的 `captcha/init`，把不可读的令牌留在服务端，只回挑战编号与 TTL。
         * 上游当前不提供图片，`imageDataUrl` 为 null；字段保留给未来的图片挑战。
         */
        post("/captcha/init") {
            val body = runCatching { call.receive<CaptchaInitRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "bad_request", "请求体必须是 JSON")
            val account = body.account.trim()
            if (account.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_field", "account 不能为空")
            }
            if (!isPlausibleAccount(account)) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_account_format",
                    "账号格式不像邮箱或手机号，请检查后再试",
                )
            }
            if (call.rejectIfRateLimited(graph, account)) return@post

            graph.captchaClient.init(account).fold(
                onSuccess = { token ->
                    val ttl = token.expiresInSec.coerceIn(30L, 3600L)
                    val challengeId = graph.captchaChallenges.issue(account, token.value, ttl)
                    PikoLog.i(TAG, "已下发验证码挑战，账号=${maskAccount(account)}，有效 $ttl 秒")
                    call.respond(
                        CaptchaInitResponse(
                            challengeId = challengeId,
                            expiresInSec = ttl,
                            note = NO_IMAGE_NOTE,
                        ),
                    )
                },
                onFailure = {
                    // 不把上游异常文本透给浏览器，也不回 500：验证码拿不到属于上游不可用
                    PikoLog.w(TAG, "验证码初始化失败，账号=${maskAccount(account)}，${it::class.simpleName}")
                    call.respondError(
                        HttpStatusCode.BadGateway,
                        "captcha_unavailable",
                        "无法向 PikPak 申请验证码，请稍后重试",
                    )
                },
            )
        }

        /**
         * 账号密码登录。成功返回登录态并已落盘；需要验证码时 401 `captcha_required` 带新挑战；
         * 账号或密码错误统一 `invalid_credentials`（区分于验证码与上游故障）。
         */
        post("/login") {
            val body = runCatching { call.receive<LoginRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "bad_request", "请求体必须是 JSON")
            val account = body.account.trim()
            val password = body.password
            if (account.isEmpty() || password.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_field", "account 与 password 都不能为空")
            }
            if (!isPlausibleAccount(account)) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_account_format",
                    "账号格式不像邮箱或手机号，请检查后再试",
                )
            }
            if (password.length > 256) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_password", "密码长度异常")
            }
            if (call.rejectIfRateLimited(graph, account)) return@post

            if (body.challengeId.isNotBlank()) {
                when (graph.captchaChallenges.consume(body.challengeId, account)) {
                    is CaptchaChallengeStore.ConsumeResult.Accepted -> Unit
                    CaptchaChallengeStore.ConsumeResult.Expired ->
                        return@post call.respondError(
                            HttpStatusCode.BadRequest,
                            "captcha_expired",
                            "验证码已过期，请刷新后重试",
                        )
                    CaptchaChallengeStore.ConsumeResult.AccountMismatch,
                    CaptchaChallengeStore.ConsumeResult.NotFound -> return@post call.respondError(
                        HttpStatusCode.BadRequest,
                        "captcha_invalid",
                        "验证码挑战无效或已使用，请刷新后重试",
                    )
                }
            }

            graph.clientManager.login(account) { password }.fold(
                onSuccess = {
                    PikoLog.i(TAG, "账号密码登录成功，账号=${maskAccount(account)}")
                    call.respond(
                        AuthStateResponse(
                            authenticated = true,
                            account = account,
                            displayName = graph.clientManager.accounts.value.find(account)?.displayName,
                            initializing = false,
                        ),
                    )
                },
                onFailure = { error -> call.respondLoginFailure(graph, account, error) },
            )
        }

        post("/logout") {
            // logout 会清空会话文件与账号列表；等它做完再回，浏览器下次查 state 必然为 false
            graph.clientManager.logout().join()
            PikoLog.i(TAG, "已退出登录并清除本地会话")
            call.respondText("", status = HttpStatusCode.NoContent)
        }
    }
}

/** 登录失败映射：上游异常只取错误码与类别，原始文案与口令一律不出服务端。 */
private suspend fun ApplicationCall.respondLoginFailure(graph: ServiceGraph, account: String, error: Throwable) {
    if (error is PikPakException) {
        when {
            error.isCaptchaRequired -> {
                // 先向 PikPak 重新申请一次令牌；申请失败也给一个短 TTL 的占位挑战，让前端能重试
                val token = graph.captchaClient.init(account).getOrNull()
                val ttl = token?.expiresInSec?.coerceIn(30L, 600L) ?: 300L
                val challengeId = graph.captchaChallenges.issue(account, token?.value.orEmpty(), ttl)
                PikoLog.w(TAG, "登录需要验证码，账号=${maskAccount(account)}，已下发新挑战")
                respond(
                    HttpStatusCode.Unauthorized,
                    CaptchaRequiredResponse(
                        error = ErrorDetail("captcha_required", "PikPak 要求完成验证码，请刷新后重试"),
                        challengeId = challengeId,
                        expiresInSec = ttl,
                        note = NO_IMAGE_NOTE,
                    ),
                )
            }
            error.errorCode == INVALID_ACCOUNT_OR_PASSWORD ||
                error.errorMessage == "invalid_account_or_password" -> {
                PikoLog.w(TAG, "账号或密码错误，账号=${maskAccount(account)}")
                respondError(HttpStatusCode.Unauthorized, "invalid_credentials", "账号或密码错误")
            }
            else -> {
                PikoLog.w(TAG, "登录被服务端拒绝，账号=${maskAccount(account)}，错误码=${error.errorCode}")
                respondError(
                    HttpStatusCode.BadGateway,
                    "login_rejected",
                    "PikPak 拒绝了登录（错误码 ${error.errorCode}）",
                )
            }
        }
        return
    }
    PikoLog.w(TAG, "登录遇到异常，账号=${maskAccount(account)}，${error::class.simpleName}")
    respondError(HttpStatusCode.BadGateway, "upstream_unavailable", "无法连接 PikPak，请稍后重试")
}

/** 同一 IP 与同一账号各自 5 次/分钟；超限回 429 并给 Retry-After。 */
private suspend fun ApplicationCall.rejectIfRateLimited(graph: ServiceGraph, account: String): Boolean {
    val ipWait = graph.loginLimiter.acquire("ip:" + request.origin.remoteAddress)
    val wait = ipWait ?: graph.loginLimiter.acquire("account:" + account.lowercase())
    if (wait == null) return false
    PikoLog.w(TAG, "登录尝试触发限流，账号=${maskAccount(account)}，IP=${request.origin.remoteAddress}，等待 $wait 秒")
    response.headers.append(HttpHeaders.RetryAfter, wait.toString())
    respondError(
        HttpStatusCode.TooManyRequests,
        "too_many_requests",
        "尝试过于频繁，请 $wait 秒后再试",
    )
    return true
}

private const val INVALID_ACCOUNT_OR_PASSWORD = 4022
