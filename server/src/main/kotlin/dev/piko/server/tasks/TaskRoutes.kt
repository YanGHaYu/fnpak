package dev.piko.server.tasks

import dev.piko.server.OfflineTaskItem
import dev.piko.server.OfflineTaskListResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.TaskDeleteFailure
import dev.piko.server.TaskDeleteRequest
import dev.piko.server.TaskDeleteResponse
import dev.piko.server.drive.requireLogin
import dev.piko.server.respondError
import dev.piko.shared.data.sourceUrl
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

/** 云端离线任务列表 + 批量删除。名称解析见 [TaskNameResolver]。 */
fun Route.taskRoutes(graph: ServiceGraph) {
    route("/api/tasks") {
        get {
            if (graph.requireLogin(call)) return@get
            val pageToken = call.request.queryParameters["pageToken"].orEmpty()
            graph.taskRepository.getTasks(pageToken).fold(
                onSuccess = { page ->
                    val items = coroutineScope {
                        page.tasks.map { task ->
                            async {
                                val resolved = graph.taskNameResolver.resolve(task)
                                OfflineTaskItem(
                                    id = task.id,
                                    phase = task.phase,
                                    progress = task.progress,
                                    fileId = task.fileId,
                                    fileSize = task.fileSize.toLongOrNull() ?: 0L,
                                    message = task.message.orEmpty(),
                                    sourceUrl = task.sourceUrl.orEmpty(),
                                    name = resolved.name,
                                    nameSource = resolved.source,
                                    type = resolved.type,
                                    folderChildCount = resolved.folderChildCount,
                                    folderChildCountTruncated = resolved.folderChildCountTruncated,
                                )
                            }
                        }.awaitAll()
                    }
                    call.respond(OfflineTaskListResponse(items = items, nextPageToken = page.nextPageToken.orEmpty()))
                },
                onFailure = {
                    call.respondError(HttpStatusCode.BadGateway, "tasks_list_failed", it.message ?: "读取离线任务失败")
                },
            )
        }

        /**
         * 批量删除离线任务。SDK 2.5.0 的 `deleteOfflineTasks(..., deleteFiles = false)` 只删任务记录，
         * 云端已完成文件保留；失败如实回 502，不伪造成功。
         */
        post("/delete") {
            if (graph.requireLogin(call)) return@post
            val ids = runCatching { call.receive<TaskDeleteRequest>() }.getOrNull()
                ?.ids?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            if (ids.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_task_ids", "ids 不能为空")
            }
            graph.taskRepository.deleteTasks(ids).fold(
                onSuccess = {
                    ids.forEach { id -> graph.taskRemovalEvents.tryEmit(id) }
                    call.respond(TaskDeleteResponse(deleted = ids))
                },
                onFailure = {
                    call.respondError(
                        HttpStatusCode.BadGateway,
                        "tasks_delete_failed",
                        it.message ?: "删除离线任务失败",
                    )
                },
            )
        }

        // 单条删除走同一条路径，前端只调这一个入口
        post("/{id}/delete") {
            if (graph.requireLogin(call)) return@post
            val id = call.parameters["id"].orEmpty()
            if (id.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_task_id", "缺少任务 ID")
            }
            graph.taskRepository.deleteTasks(listOf(id)).fold(
                onSuccess = {
                    graph.taskRemovalEvents.tryEmit(id)
                    call.respond(TaskDeleteResponse(deleted = listOf(id)))
                },
                onFailure = {
                    call.respond(
                        TaskDeleteResponse(failed = listOf(TaskDeleteFailure(id, it.message ?: "删除失败"))),
                    )
                },
            )
        }
    }
}
