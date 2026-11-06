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
    val app: String,
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
    /** true 表示口令来自 --access-token / FNPAK_SERVER_TOKEN（兼容旧名 PIKO_SERVER_TOKEN），页面不可改。 */
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
    /** 每个任务的分段数（并发连接数按任务算）。 */
    val segmentCount: Int = 8,
    /** 所有任务共用的连接预算。 */
    val maxConnections: Int = 32,
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

/** 分段下载参数：段数 1–32，全局连接预算 4–64。 */
@Serializable
data class DownloadTuningRequest(
    val segments: Int = 8,
    val maxConnections: Int = 32,
)

/** 保存后的实际生效值；返回 200+JSON 而不是 204，前端可直接把回包当确认。 */
@Serializable
data class DownloadTuningResponse(
    val segments: Int,
    val maxConnections: Int,
)

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
    // 列表页排序用：PikPak createdTime 解析出的 UTC epoch 毫秒，无法解析时为 null
    val createdAtMs: Long? = null,
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

/** 首次设置向导状态。`needsSetup=true` 时前端显示向导而不是口令卡。 */
@Serializable
data class SetupStateResponse(
    val needsSetup: Boolean,
    /** `no_token_file` / `token_file` / `managed_externally`。 */
    val reason: String,
    /** 是否仍在允许设置的时间窗口内（服务启动后的 N 分钟；0 = 不限）。 */
    val setupWindowOpen: Boolean,
    val setupWindowMinutes: Int,
)

@Serializable
data class SetupAccessTokenRequest(val passcode: String = "", val confirmPasscode: String = "")

@Serializable
data class SetupAccessTokenResponse(val ok: Boolean, val fingerprint: String, val needsSetup: Boolean = false)

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
    /** 显示名：按 file_name → 文件详情 → display_name → 磁力 dn → MAGNET+hash 的优先级解析。 */
    val name: String = "",
    /** 名字取自哪里：file_name / file_detail / display_name / magnet_dn / fallback。 */
    val nameSource: String = "fallback",
    /** file / folder / unknown。 */
    val type: String = "unknown",
    /** 文件夹型任务的第一页子项数；非文件夹为 0。 */
    val folderChildCount: Int = 0,
    /** 子项超过一页，只统计到第一页；页面标注「至少」。 */
    val folderChildCountTruncated: Boolean = false,
)

@Serializable
data class OfflineTaskListResponse(val items: List<OfflineTaskItem>, val nextPageToken: String = "")

@Serializable
data class TaskDeleteRequest(val ids: List<String> = emptyList())

@Serializable
data class TaskDeleteFailure(val id: String, val message: String)

/** 删除结果如实回包：成功哪些、失败哪些；不把整批当成功。 */
@Serializable
data class TaskDeleteResponse(
    val deleted: List<String> = emptyList(),
    val failed: List<TaskDeleteFailure> = emptyList(),
    /** 固定说明：删除任务记录不删云端产出文件（SDK `deleteFiles=false`）。 */
    val note: String = "仅删除离线任务记录；云端已完成的文件会保留",
)

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
    /** 父任务（批次）真实 ID；单文件为空串。 */
    val parentTaskId: String = "",
    /** 前端展开子项用的分组 ID：批次 ID，单文件为 `single:<taskId>`。 */
    val groupId: String = "",
    /** 文件夹下载的源文件夹 ID。 */
    val rootFolderId: String = "",
    val batchName: String = "",
    val batchIsFolder: Boolean = false,
)

/** 父任务汇总：一个文件夹或一次多选下载合并成一行，进度按总字节汇总。 */
@Serializable
data class DownloadGroupItem(
    val id: String,
    /** folder / files / single。 */
    val kind: String,
    val name: String,
    val rootFolderId: String = "",
    /** SCANNING / QUEUED / DOWNLOADING / PAUSED / COMPLETED / FAILED / CANCELLED。 */
    val status: String,
    val childCount: Int = 0,
    val completedCount: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val percent: Double = 0.0,
    val speedBytesPerSec: Long = 0,
    val error: String? = null,
    val createdAtMs: Long = 0,
    /** 文件（或文件夹）在本机的落盘目录，删除任务记录时用来提示文件保留在哪。 */
    val path: String = "",
)

@Serializable
data class DownloadListResponse(
    val items: List<DownloadItem>,
    val groups: List<DownloadGroupItem> = emptyList(),
)

/** 重试单个任务或整批失败项的回包；`skipped` 是被拒绝的项与可读原因。 */
@Serializable
data class DownloadRetryResponse(
    val retried: List<String> = emptyList(),
    val skipped: List<SkippedFile> = emptyList(),
    val message: String = "",
    val items: List<DownloadItem> = emptyList(),
    val groups: List<DownloadGroupItem> = emptyList(),
)

@Serializable
data class StartDownloadRequest(val fileId: String = "")

@Serializable
data class BatchDownloadRequest(val fileIds: List<String> = emptyList())

@Serializable
data class SkippedFile(val id: String, val reason: String)

@Serializable
data class BatchDownloadResponse(
    val groupId: String = "",
    val childCount: Int = 0,
    val bytesTotal: Long = 0,
    val skipped: List<SkippedFile> = emptyList(),
)

@Serializable
data class FolderDownloadRequest(val folderId: String = "")

/** 同一目录内混合选择：文件直接入队，文件夹递归展开后并入**同一个**批次。 */
@Serializable
data class MixedDownloadRequest(
    val ids: List<String> = emptyList(),
    val maxHeight: Int? = null,
)

@Serializable
data class MixedDownloadResponse(
    val groupId: String,
    val phase: String = "SCANNING",
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    /** 扫描期只统计直接选中的文件；扫描完成后由 `GET /api/downloads` 给出最终值。 */
    val childCount: Int = 0,
    val bytesTotal: Long = 0,
)

@Serializable
data class FolderDownloadResponse(
    val groupId: String,
    val phase: String = "SCANNING",
    val childCount: Int = 0,
    val bytesTotal: Long = 0,
)

@Serializable
data class TaskRemovedEvent(val id: String)

/** 删除本机下载任务**记录**（不删磁盘文件）。 */
@Serializable
data class DownloadRecordDeleteRequest(val ids: List<String> = emptyList())

@Serializable
data class DownloadRecordDeleteFailure(val id: String, val message: String)

@Serializable
data class DownloadRecordDeleteResponse(
    val deleted: List<String> = emptyList(),
    val failed: List<DownloadRecordDeleteFailure> = emptyList(),
    val removedTasks: Int = 0,
    /** 固定说明：只删记录，不动磁盘文件。 */
    val note: String = "只删除任务记录与进度；磁盘上已下载的文件保留",
)

@Serializable
data class DownloadGroupRemovedEvent(val id: String)

/** 云端批量删除。使用 SDK 的 batchTrash：移入回收站，可恢复，不是永久删除。 */
@Serializable
data class CloudDeleteRequest(val ids: List<String> = emptyList())

@Serializable
data class CloudDeleteFailure(val id: String, val message: String)

@Serializable
data class CloudDeleteResponse(
    val trashed: List<String> = emptyList(),
    val failed: List<CloudDeleteFailure> = emptyList(),
    val mode: String = "trash",
    val note: String = "已移入回收站，可在回收站恢复；不是永久删除",
)

// ---- 回收站 ----

/**
 * 回收站条目。`parentName` 由服务端按 parentId 另查（查不到就为 null，前端显示「原位置不可用」）；
 * `deleteTime` 保留服务端原值，可能是 ISO 8601 也可能是毫秒字符串，交给前端格式化。
 */
@Serializable
data class TrashItem(
    val id: String,
    val name: String,
    val kind: String,
    val isFolder: Boolean,
    val sizeBytes: Long = 0,
    val parentId: String = "",
    val parentName: String? = null,
    val deleteTime: String? = null,
)

@Serializable
data class TrashListResponse(
    val items: List<TrashItem> = emptyList(),
    val nextPageToken: String = "",
    val pageSize: Int = 0,
)

@Serializable
data class TrashActionRequest(val ids: List<String> = emptyList())

/** 逐项结果：失败项前端保留在列表并显示原因，不整批回滚。 */
@Serializable
data class TrashActionResult(
    val id: String,
    val ok: Boolean,
    val message: String? = null,
)

@Serializable
data class TrashActionResponse(
    val action: String,
    val items: List<TrashActionResult> = emptyList(),
    val succeeded: Int = 0,
    val failed: Int = 0,
    val note: String = "",
)

// ---- 重命名 / 移动 / 复制 ----

/** 重命名请求。名称在服务端 trim 后校验：非空、≤255、不能含 `/` 与 `\`。 */
@Serializable
data class RenameFileRequest(val name: String = "")

@Serializable
data class RenameFileResponse(val id: String, val name: String)

/** 移动/复制到 [targetParentId]；空串表示根目录（SDK 的文件 ID 约定）。 */
@Serializable
data class MoveCopyRequest(val ids: List<String> = emptyList(), val targetParentId: String = "")

/**
 * 逐条结果。移动是同步的；复制由 PikPak 服务端按任务执行，成功项带 [taskId]，
 * 可用 `GET /api/drive/tasks/{taskId}` 查询进度。
 */
@Serializable
data class MoveCopyItem(
    val id: String,
    val ok: Boolean,
    val taskId: String? = null,
    val message: String? = null,
)

@Serializable
data class MoveCopyResponse(
    val mode: String,
    val items: List<MoveCopyItem> = emptyList(),
    val succeeded: Int = 0,
    val failed: Int = 0,
    val async: Boolean = false,
    val note: String = "",
)

/** 云端 drive 任务（复制等）的进度快照。 */
@Serializable
data class DriveTaskResponse(
    val id: String,
    val kind: String = "",
    val phase: String = "",
    val progress: Int = 0,
    val message: String = "",
    val fileName: String = "",
    val fileSize: String = "",
)

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
    /**
     * 归属信息：与 `GET /api/downloads` 的 DownloadItem 保持一致。
     * 没有这几个字段时，前端只能把事件当成一条孤立进度，无法按 groupId 归到父任务下，
     * 进行中的批次就会「只显示部分子项，暂停后刷新才全」。
     */
    val fileId: String = "",
    val parentTaskId: String = "",
    val groupId: String = "",
    /** 分段下载时各段的进度；其他下载方式没有这个字段（保持原字段不变，只做新增）。 */
    val segments: List<dev.piko.download.DownloadSegmentInfo>? = null,
    /** 失败原因（HTTP 状态 / 直链失效 / 空间不足等）；成功或进行中为 null。 */
    val error: String? = null,
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
