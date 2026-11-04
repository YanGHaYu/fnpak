package dev.piko.server.tasks

import dev.piko.server.OfflineTaskItem
import dev.piko.server.OfflineTaskListResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.drive.requireLogin
import dev.piko.server.respondError
import dev.piko.shared.data.sourceUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** 云端离线任务列表。直接读 `TaskRepository.getTasks()`，与桌面端的传输页同一份数据。 */
fun Route.taskRoutes(graph: ServiceGraph) {
    route("/api/tasks") {
        get {
            if (graph.requireLogin(call)) return@get
            val pageToken = call.request.queryParameters["pageToken"].orEmpty()
            graph.taskRepository.getTasks(pageToken).fold(
                onSuccess = { page ->
                    call.respond(
                        OfflineTaskListResponse(
                            items = page.tasks.map { task ->
                                OfflineTaskItem(
                                    id = task.id,
                                    phase = task.phase,
                                    progress = task.progress,
                                    fileId = task.fileId,
                                    fileSize = task.fileSize.toLongOrNull() ?: 0L,
                                    message = task.message.orEmpty(),
                                    sourceUrl = task.sourceUrl.orEmpty(),
                                )
                            },
                            nextPageToken = page.nextPageToken.orEmpty(),
                        ),
                    )
                },
                onFailure = {
                    call.respondError(HttpStatusCode.BadGateway, "tasks_list_failed", it.message ?: "读取离线任务失败")
                },
            )
        }
    }
}
