package dev.piko.server.drive

import dev.piko.server.FileDetailResponse
import dev.piko.server.FileItem
import dev.piko.server.FileListResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.respondError
import dev.piko.shared.data.PikoFileSortOrder
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * 云盘只读路由。全部直接走 `PikoDriveRepository`，不经过 `:ui` 的 `*State`：
 * 那些类是给 Compose 用的，会顺带拉起界面会话。
 */
fun Route.driveRoutes(graph: ServiceGraph) {
    route("/api/drive") {
        // parentId 为空串表示根目录（SDK 的约定），不要传 "/"
        get("/list") {
            if (graph.requireLogin(call)) return@get
            val parentId = call.request.queryParameters["parentId"].orEmpty()
            val pageToken = call.request.queryParameters["pageToken"].orEmpty()
            val sortOrder = sortOrderOf(call.request.queryParameters["sort"])
            graph.driveRepository.listFiles(parentId, pageToken, sortOrder).fold(
                onSuccess = { (items, nextPageToken) ->
                    call.respond(FileListResponse(items.map { it.toItem() }, nextPageToken))
                },
                onFailure = { call.respondError(HttpStatusCode.BadGateway, "drive_list_failed", failureMessage(it)) },
            )
        }

        get("/search") {
            if (graph.requireLogin(call)) return@get
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.isEmpty()) {
                return@get call.respondError(HttpStatusCode.BadRequest, "missing_query", "q 不能为空")
            }
            graph.driveRepository.search(query).fold(
                onSuccess = { files -> call.respond(FileListResponse(files.map { it.toItem() })) },
                onFailure = { call.respondError(HttpStatusCode.BadGateway, "drive_search_failed", failureMessage(it)) },
            )
        }

        get("/files/{id}") {
            if (graph.requireLogin(call)) return@get
            val id = call.parameters["id"].orEmpty()
            if (id.isEmpty()) {
                return@get call.respondError(HttpStatusCode.BadRequest, "missing_id", "缺少文件 ID")
            }
            graph.driveRepository.getFileDetail(id).fold(
                onSuccess = { call.respond(it.toResponse()) },
                onFailure = { call.respondError(HttpStatusCode.BadGateway, "file_detail_failed", failureMessage(it)) },
            )
        }
    }
}

private fun FileStat.toItem(): FileItem = FileItem(
    id = id,
    parentId = parentId,
    name = name,
    kind = kind,
    sizeBytes = sizeBytes,
    isFolder = isFolder,
    // 不引入 mediamp：视频判定只看 MIME 与时长，播放交给浏览器
    isVideo = isVideoLike(mimeType, durationMillis),
    durationMs = durationMillis?.takeIf { it > 0 },
    hash = hash,
    mimeType = mimeType,
)

private fun FileDetail.toResponse(): FileDetailResponse = FileDetailResponse(
    id = id,
    parentId = parentId,
    name = name,
    sizeBytes = sizeBytes,
    hash = hash,
    mimeType = mimeType,
    trashed = trashed,
    // SDK 在 params 里把时长以秒给出
    durationMs = params["duration"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }?.takeIf { it > 0 },
)

private fun isVideoLike(mimeType: String, durationMillis: Long?): Boolean =
    mimeType.startsWith("video/") || (durationMillis != null && durationMillis > 0)

private fun sortOrderOf(raw: String?): PikoFileSortOrder = when (raw?.uppercase()) {
    "NAME", "NAME_ASC" -> PikoFileSortOrder.NAME_ASC
    "NAME_DESC" -> PikoFileSortOrder.NAME_DESC
    "TIME_ASC" -> PikoFileSortOrder.TIME_ASC
    "SIZE_DESC" -> PikoFileSortOrder.SIZE_DESC
    "SIZE_ASC" -> PikoFileSortOrder.SIZE_ASC
    "DURATION_DESC" -> PikoFileSortOrder.DURATION_DESC
    "DURATION_ASC" -> PikoFileSortOrder.DURATION_ASC
    else -> PikoFileSortOrder.TIME_DESC
}

private fun failureMessage(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty().ifEmpty { "未知错误" }

/** 没登录就统一 401，避免各仓库里 `error("Not logged in")` 变成 500。 */
internal suspend fun ServiceGraph.requireLogin(call: io.ktor.server.application.ApplicationCall): Boolean {
    if (isAuthenticated) return false
    call.respondError(HttpStatusCode.Unauthorized, "not_authenticated", "尚未登录，请先导入令牌")
    return true
}
