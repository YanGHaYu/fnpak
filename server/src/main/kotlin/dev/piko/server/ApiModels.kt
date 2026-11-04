package dev.piko.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

// HTTP 层的响应模型。刻意不复用 SDK 的 FileStat/FileDetail：那些类字段多且带签名直链，
// 直接序列化会把凭据与用不上的字段一起吐给浏览器。

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val version: String,
    val home: String,
    val authenticated: Boolean,
    val initializing: Boolean,
    val uptimeSeconds: Long,
)

@Serializable
data class AuthStateResponse(
    val authenticated: Boolean,
    val account: String? = null,
    val displayName: String? = null,
    val initializing: Boolean,
    /** M1 固定 false：还没有验证码登录，只能导入令牌。 */
    val needsCaptcha: Boolean = false,
)

@Serializable
data class TokenImportRequest(
    val account: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
)

@Serializable
data class FileItem(
    val id: String,
    val parentId: String,
    val name: String,
    val kind: String,
    val sizeBytes: Long,
    val isFolder: Boolean,
    val isVideo: Boolean,
    val durationMs: Long? = null,
    val hash: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class FileListResponse(
    val items: List<FileItem>,
    val nextPageToken: String = "",
)

@Serializable
data class FileDetailResponse(
    val id: String,
    val parentId: String,
    val name: String,
    val sizeBytes: Long,
    val hash: String? = null,
    val mimeType: String? = null,
    val trashed: Boolean = false,
    val durationMs: Long? = null,
)

@Serializable
data class ErrorResponse(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String)

/** 统一错误体 `{"error":{"code","message"}}`，前端按 code 分支，不看文案。 */
suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ErrorResponse(ErrorDetail(code, message)))
}
