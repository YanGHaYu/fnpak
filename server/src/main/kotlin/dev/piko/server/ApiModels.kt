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
    /** 当前生效的下载目录，便于在 NAS 上直接确认设置是否落实。 */
    val downloadDir: String,
)

// ---------- 设置页 ----------

/** 口令信息：只暴露指纹（sha256 前 8 位），任何情况下都不回明文。 */
@Serializable
data class AccessTokenSettings(
    val fingerprint: String,
    /** true 表示口令来自 --access-token / PIKO_SERVER_TOKEN，页面不可改。 */
    val managedExternally: Boolean,
    val source: String,
    val filePath: String,
)

@Serializable
data class SettingsResponse(
    val version: String,
    val home: String,
    val accessToken: AccessTokenSettings,
    val downloadDir: String,
)

@Serializable
data class ShareInfo(
    val path: String,
    val writable: Boolean,
    /** `declared`：config/resource 声明的共享根；`authorized`：应用设置→访问权限里授权的目录。 */
    val source: String,
    val reason: String? = null,
)

@Serializable
data class SharesResponse(
    val shares: List<ShareInfo>,
    /** 本次是否通过 fnOS 授权接口实时查询；false 时新加的授权目录要重启应用才可见。 */
    val live: Boolean = false,
    val note: String = "",
)

@Serializable
data class ChangeAccessTokenRequest(val newPasscode: String = "")

@Serializable
data class DownloadDirRequest(
    val shareRoot: String = "",
    val subPath: String = "",
    /** true 表示只做校验 + 建目录 + 试写，不持久化（页面「测试写入」用）。 */
    val dryRun: Boolean = false,
)

@Serializable
data class DownloadDirResponse(val downloadDir: String)

@Serializable
data class AuthStateResponse(
    val authenticated: Boolean,
    val account: String? = null,
    val displayName: String? = null,
    val initializing: Boolean,
    /** 仅在同一账号上一次登录被要求验证码时有意义；前端主要按登录接口的 401 分支处理。 */
    val needsCaptcha: Boolean = false,
)

@Serializable
data class TokenImportRequest(
    val account: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
)

// ---- 账号密码登录与验证码 ----

@Serializable
data class LoginRequest(
    val account: String = "",
    val password: String = "",
    /** 由验证码初始化接口下发；一次性使用，过期或已用过都会被拒绝。 */
    val challengeId: String = "",
    /** PikPak 当前的验证码是风控令牌而非图片，本字段保留给未来图片挑战，当前不使用。 */
    val captchaAnswer: String = "",
)

@Serializable
data class CaptchaInitRequest(val account: String = "")

@Serializable
data class CaptchaInitResponse(
    val challengeId: String,
    val expiresInSec: Long,
    /** `risk_token`：PikPak 下发的是不可读的风控令牌，没有图片；保留字段以便未来切换为图片挑战。 */
    val captchaType: String = "risk_token",
    /** 有图片挑战时是 `data:image/...;base64,...`；当前为 null。 */
    val imageDataUrl: String? = null,
    val note: String = "",
)

/** 登录需要验证码时的 401 响应：既带统一错误体，也带新的挑战供前端重试。 */
@Serializable
data class CaptchaRequiredResponse(
    val error: ErrorDetail,
    val challengeId: String,
    val expiresInSec: Long,
    val captchaType: String = "risk_token",
    val imageDataUrl: String? = null,
    val note: String = "",
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

// ---- 访问口令 ----

@Serializable
data class AccessLoginRequest(val passcode: String = "")

@Serializable
data class AccessStateResponse(val required: Boolean, val authenticated: Boolean)

// ---- M2 链接解析与离线任务 ----

@Serializable
data class LinkParseRequest(val link: String = "", val passCode: String = "")

@Serializable
data class LinkFileItem(
    val name: String,
    val sizeBytes: Long,
    /** 内容哈希；有它才能秒传/借出取流，直链探测通常为空。 */
    val gcid: String? = null,
    val instantReady: Boolean = false,
)

@Serializable
data class LinkParseResponse(
    /** `magnet` / `share` / `http`，前端据此提示后续能不能直接下载。 */
    val kind: String,
    val name: String,
    /** 分享里的子目录超过展开上限，文件列表不完整。 */
    val truncated: Boolean = false,
    /** 分享的提取码令牌，交给保存步骤使用；不含用户口令。 */
    val passCodeToken: String? = null,
    val files: List<LinkFileItem>,
)

@Serializable
data class LinkSaveRequest(val link: String = "", val parentId: String = "")

@Serializable
data class LinkSaveResponse(
    /** 排进离线队列时的任务 ID；秒传直接完成时为空。 */
    val taskId: String? = null,
    val instantComplete: Boolean = false,
    /** 离线提交即完成时的产出的文件 ID。 */
    val fileId: String? = null,
)

@Serializable
data class OfflineTaskItem(
    val id: String,
    val phase: String,
    /** 服务端给的百分比，0..100。 */
    val progress: Int,
    val fileId: String = "",
    val fileSize: Long = 0,
    val message: String = "",
    val sourceUrl: String = "",
)

@Serializable
data class OfflineTaskListResponse(val items: List<OfflineTaskItem>, val nextPageToken: String = "")

// ---- 浏览器播放 ----

@Serializable
data class MediaInfoResponse(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val fileExtension: String,
    /** `redirect`：302 到 PikPak 签名直链；`proxy`：服务端字节流转发。 */
    val mode: String,
    /** 为什么选这条路，例如 `direct_probe_http_200` / `direct_probe_http_403`。 */
    val modeReason: String,
    /** 当前实现两条路都接受单段 Range。 */
    val rangeSupported: Boolean = true,
)

// ---- 本机下载 ----

@Serializable
data class DownloadItem(
    val id: String,
    val fileName: String,
    val fileId: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** 0..100，一位小数。 */
    val percent: Double,
    val speedBytesPerSec: Long,
    val status: String,
    val error: String? = null,
    val path: String = "",
)

@Serializable
data class DownloadListResponse(val items: List<DownloadItem>)

@Serializable
data class StartDownloadRequest(val fileId: String = "")

// ---- SSE 事件 ----

@Serializable
data class DownloadProgressEvent(
    val id: String,
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** 0..100。 */
    val percent: Double,
    val speedBytesPerSec: Long,
    val status: String,
)

@Serializable
data class TaskProgressEvent(
    val id: String,
    val phase: String,
    /** 0..100。 */
    val progress: Int,
    val fileId: String = "",
    val message: String = "",
)


/** 统一错误体 `{"error":{"code","message"}}`，前端按 code 分支，不看文案。 */
suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ErrorResponse(ErrorDetail(code, message)))
}
