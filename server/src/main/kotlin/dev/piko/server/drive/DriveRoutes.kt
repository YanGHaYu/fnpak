package dev.piko.server.drive

import dev.piko.server.CloudDeleteFailure
import dev.piko.server.CloudDeleteRequest
import dev.piko.server.CloudDeleteResponse
import dev.piko.server.DriveTaskResponse
import dev.piko.server.FileDetailResponse
import dev.piko.server.FileItem
import dev.piko.server.FileListResponse
import dev.piko.server.MoveCopyItem
import dev.piko.server.MoveCopyRequest
import dev.piko.server.MoveCopyResponse
import dev.piko.server.RenameFileRequest
import dev.piko.server.RenameFileResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.TrashActionRequest
import dev.piko.server.TrashActionResult
import dev.piko.server.TrashActionResponse
import dev.piko.server.TrashItem
import dev.piko.server.TrashListResponse
import dev.piko.server.respondError
import dev.piko.shared.data.PikoFileSortOrder
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

        /**
         * 批量删除云端文件/文件夹：走 `PikoDriveRepository.trash` → SDK `batchTrash`，
         * 语义是**移入回收站**，可在回收站恢复，不是永久删除（永久删除是 `batchDelete`，这里不提供）。
         * 逐项调用并逐项返回，失败项前端保留选中以便重试。
         */
        post("/trash") {
            if (graph.requireLogin(call)) return@post
            val ids = runCatching { call.receive<CloudDeleteRequest>() }.getOrNull()
                ?.ids?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            if (ids.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_ids", "ids 不能为空")
            }
            val trashed = mutableListOf<String>()
            val failed = mutableListOf<CloudDeleteFailure>()
            for (id in ids) {
                graph.driveRepository.trash(listOf(id)).fold(
                    onSuccess = { trashed += id },
                    onFailure = { failed += CloudDeleteFailure(id, failureMessage(it)) },
                )
            }
            call.respond(CloudDeleteResponse(trashed = trashed, failed = failed))
        }

        /**
         * 重命名：SDK `rename` 走 `PATCH /drive/v1/files/:id`。
         * 名称先 trim，再校验非空、≤255、不含 `/` 与 `\`；校验失败回 400 `invalid_name`。
         */
        post("/files/{id}/rename") {
            if (graph.requireLogin(call)) return@post
            val id = call.parameters["id"].orEmpty().trim()
            if (id.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_id", "缺少文件 ID")
            }
            val name = runCatching { call.receive<RenameFileRequest>() }.getOrNull()?.name?.trim().orEmpty()
            val invalid = when {
                name.isEmpty() -> "名称不能为空"
                name.length > MAX_NAME_LENGTH -> "名称不能超过 $MAX_NAME_LENGTH 个字符（当前 ${name.length}）"
                name.contains('/') || name.contains('\\') -> "名称不能包含 / 或 \\"
                else -> null
            }
            if (invalid != null) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_name", invalid)
            }
            graph.driveRepository.rename(id, name).fold(
                onSuccess = { call.respond(RenameFileResponse(id, name)) },
                onFailure = { call.respondError(HttpStatusCode.BadGateway, "rename_failed", failureMessage(it)) },
            )
        }

        /**
         * 移动：SDK `batchMove` 走 `POST /drive/v1/files:batchMove`，同步返回。
         * 先做目标校验（自身/子目录 → 400 `invalid_target`），再逐条执行并逐条返回结果。
         */
        post("/move") {
            if (graph.requireLogin(call)) return@post
            val request = runCatching { call.receive<MoveCopyRequest>() }.getOrNull() ?: MoveCopyRequest()
            val ids = request.ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ids.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_ids", "ids 不能为空")
            }
            val target = request.targetParentId.trim()
            validateTarget(graph, ids, target)?.let { problem ->
                return@post call.respondError(problem.status, problem.code, problem.message)
            }
            val items = mutableListOf<MoveCopyItem>()
            for (id in ids) {
                graph.driveRepository.move(listOf(id), target).fold(
                    onSuccess = { items += MoveCopyItem(id, ok = true) },
                    onFailure = { items += MoveCopyItem(id, ok = false, message = failureMessage(it)) },
                )
            }
            val succeeded = items.count { it.ok }
            call.respond(
                MoveCopyResponse(
                    mode = "move",
                    items = items,
                    succeeded = succeeded,
                    failed = items.size - succeeded,
                ),
            )
        }

        /**
         * 复制：SDK `batchCopy` 走 `POST /drive/v1/files:batchCopy`，**服务端按异步任务执行**，
         * 响应里的 `task_id` 就是复制任务；SDK 只返回任务 ID、不轮询。成功项带 taskId，
         * 进度用 `GET /api/drive/tasks/{taskId}` 查。源文件不移动。
         */
        post("/copy") {
            if (graph.requireLogin(call)) return@post
            val request = runCatching { call.receive<MoveCopyRequest>() }.getOrNull() ?: MoveCopyRequest()
            val ids = request.ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ids.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_ids", "ids 不能为空")
            }
            val target = request.targetParentId.trim()
            validateTarget(graph, ids, target)?.let { problem ->
                return@post call.respondError(problem.status, problem.code, problem.message)
            }
            val items = mutableListOf<MoveCopyItem>()
            for (id in ids) {
                graph.driveRepository.copyWithTaskIds(listOf(id), target).fold(
                    onSuccess = { taskIds -> items += MoveCopyItem(id, ok = true, taskId = taskIds.firstOrNull()) },
                    onFailure = { items += MoveCopyItem(id, ok = false, message = failureMessage(it)) },
                )
            }
            val succeeded = items.count { it.ok }
            call.respond(
                MoveCopyResponse(
                    mode = "copy",
                    items = items,
                    succeeded = succeeded,
                    failed = items.size - succeeded,
                    async = true,
                    note = "复制由 PikPak 服务端按任务执行：接口返回表示任务已受理，不保证文件已经出现在目标目录；可用任务 ID 查询进度",
                ),
            )
        }

        /**
         * 回收站列表：SDK `listFilesPaged(parentId = "*", filters = {trashed: {eq: true}})`，
         * 走 `GET /drive/v1/files`，响应带 `nextPageToken`，可以翻页。
         * 服务端只给 parentId，不给原父目录名：这里按唯一 parentId 并发补查（上限 20 个），
         * 查不到就留空，前端显示「原位置不可用」。
         */
        get("/trash/list") {
            if (graph.requireLogin(call)) return@get
            val pageToken = call.request.queryParameters["pageToken"].orEmpty()
            graph.driveRepository.listTrashPage(pageToken).fold(
                onSuccess = { (files, nextPageToken) ->
                    val names = resolveParentNames(graph, files)
                    call.respond(
                        TrashListResponse(
                            items = files.map { it.toTrashItem(names[it.parentId]) },
                            nextPageToken = nextPageToken,
                            pageSize = files.size,
                        ),
                    )
                },
                onFailure = { call.respondError(HttpStatusCode.BadGateway, "trash_list_failed", failureMessage(it)) },
            )
        }

        /**
         * 恢复：SDK `batchUntrash` → `POST /drive/v1/files:batchUntrash`，同步返回、无任务 ID。
         * 恢复文件夹时服务端按整棵子树恢复（回收站保留原层级，恢复即把该文件夹及其内容一起放回原父目录）。
         * 逐项调用并逐项返回，失败项留在回收站列表里。
         */
        post("/trash/restore") {
            if (graph.requireLogin(call)) return@post
            val ids = trashIds(call) ?: return@post
            call.respond(
                runTrashAction("restore", ids, "恢复项仍留在回收站") { id ->
                    graph.driveRepository.restore(listOf(id))
                },
            )
        }

        /**
         * 彻底删除：SDK `batchDelete` → `POST /drive/v1/files:batchDelete`，**永久删除、不可恢复**。
         * 与上方的 `POST /api/drive/trash`（移入回收站）是两回事，前端文案必须写「彻底删除后不可恢复」。
         */
        post("/trash/delete") {
            if (graph.requireLogin(call)) return@post
            val ids = trashIds(call) ?: return@post
            call.respond(
                runTrashAction("delete", ids, "失败项仍留在回收站") { id ->
                    graph.driveRepository.delete(listOf(id))
                },
            )
        }

        /** 查询复制/解压等云端 drive 任务的进度。 */
        get("/tasks/{taskId}") {
            if (graph.requireLogin(call)) return@get
            val taskId = call.parameters["taskId"].orEmpty().trim()
            if (taskId.isEmpty()) {
                return@get call.respondError(HttpStatusCode.BadRequest, "missing_task_id", "缺少任务 ID")
            }
            graph.driveRepository.getDriveTask(taskId).fold(
                onSuccess = { task ->
                    call.respond(
                        DriveTaskResponse(
                            id = task.id,
                            kind = task.kind,
                            phase = task.phase,
                            progress = task.progress,
                            message = task.message,
                            fileName = task.fileName,
                            fileSize = task.fileSize,
                        ),
                    )
                },
                onFailure = { call.respondError(HttpStatusCode.BadGateway, "task_query_failed", failureMessage(it)) },
            )
        }
    }
}

private const val MAX_NAME_LENGTH = 255

private class TargetProblem(val status: HttpStatusCode, val code: String, val message: String)

/**
 * 目标必须是网盘中的文件夹、不在回收站，且不能是所选文件夹自身或其后代。
 * SDK/API 没有「可写」标志：这里只能排除回收站与非文件夹，实际权限由 PikPak 在执行时拒绝。
 */
private suspend fun validateTarget(graph: ServiceGraph, ids: List<String>, targetId: String): TargetProblem? {
    if (ids.contains(targetId)) {
        return TargetProblem(HttpStatusCode.BadRequest, "invalid_target", "不能把所选项目移动到它自身")
    }
    if (targetId.isEmpty()) return null // 根目录
    val detail = graph.driveRepository.getFileDetail(targetId).getOrElse { error ->
        return TargetProblem(HttpStatusCode.BadGateway, "target_lookup_failed", "无法确认目标目录：${failureMessage(error)}")
    }
    if (detail.trashed) {
        return TargetProblem(HttpStatusCode.BadRequest, "invalid_target", "目标目录在回收站，不能作为目标")
    }
    if (detail.kind != FileKind.FOLDER) {
        return TargetProblem(HttpStatusCode.BadRequest, "invalid_target", "目标不是文件夹")
    }
    // 从目标目录沿 parentId 向上走：若碰到任一选中项，说明目标是所选文件夹的子目录。
    // 比向下遍历整棵子树便宜，且不受目录规模影响。
    var cursor = detail.parentId
    var guard = 0
    while (cursor.isNotEmpty()) {
        if (ids.contains(cursor)) {
            return TargetProblem(HttpStatusCode.BadRequest, "invalid_target", "不能把文件夹移动到它自己的子目录")
        }
        if (++guard > MAX_TARGET_DEPTH) {
            return TargetProblem(HttpStatusCode.BadRequest, "invalid_target", "目标目录层级过深，无法完成校验")
        }
        cursor = graph.driveRepository.getFileDetail(cursor).getOrElse { error ->
            return TargetProblem(HttpStatusCode.BadGateway, "target_lookup_failed", "无法确认目标目录的上级：${failureMessage(error)}")
        }.parentId
    }
    return null
}

private const val MAX_TARGET_DEPTH = 64

/** 回收站每条最多补查多少个唯一父目录名：避免一页 100 条都散落在不同目录时打爆请求数。 */
private const val MAX_PARENT_NAME_LOOKUPS = 20

private suspend fun trashIds(call: io.ktor.server.application.ApplicationCall): List<String>? {
    val ids = runCatching { call.receive<TrashActionRequest>() }.getOrNull()
        ?.ids?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
    if (ids.isEmpty()) {
        call.respondError(HttpStatusCode.BadRequest, "missing_ids", "ids 不能为空")
        return null
    }
    return ids
}

/** 逐项执行、逐项返回：一项失败不影响其他项，也不会整批回滚。 */
private suspend fun runTrashAction(
    action: String,
    ids: List<String>,
    note: String,
    op: suspend (String) -> Result<Unit>,
): TrashActionResponse {
    val items = ids.map { id ->
        op(id).fold(
            onSuccess = { TrashActionResult(id, ok = true) },
            onFailure = { TrashActionResult(id, ok = false, message = failureMessage(it)) },
        )
    }
    val succeeded = items.count { it.ok }
    return TrashActionResponse(
        action = action,
        items = items,
        succeeded = succeeded,
        failed = items.size - succeeded,
        note = note,
    )
}

/** parentId → 名称；根目录用固定文案，查不到的父目录留空。 */
private suspend fun resolveParentNames(graph: ServiceGraph, files: List<FileStat>): Map<String, String> = coroutineScope {
    val ids = files.map { it.parentId }.filter { it.isNotEmpty() }.distinct().take(MAX_PARENT_NAME_LOOKUPS)
    val resolved = ids.map { id ->
        async { id to graph.driveRepository.getFileDetail(id).getOrNull()?.name }
    }.awaitAll()
    buildMap {
        put("", "根目录")
        resolved.forEach { (id, name) -> if (name != null) put(id, name) }
    }
}

private fun FileStat.toTrashItem(parentName: String?): TrashItem = TrashItem(
    id = id,
    name = name,
    kind = kind,
    isFolder = isFolder,
    sizeBytes = sizeBytes,
    parentId = parentId,
    parentName = parentName,
    deleteTime = deleteTime,
)

private fun FileStat.toItem(): FileItem {
    // 时长放在 params["duration"]（秒）；SDK 2.5.0 没有 durationMillis 属性
    val durationMs = params["duration"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }?.takeIf { it > 0 }
    return FileItem(
        id = id,
        parentId = parentId,
        name = name,
        kind = kind,
        sizeBytes = sizeBytes,
        isFolder = isFolder,
        // 不引入 mediamp：视频判定只看 MIME 与时长，播放交给浏览器
        isVideo = isVideoLike(mimeType, durationMs),
        durationMs = durationMs,
        hash = hash,
        mimeType = mimeType,
    )
}

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
