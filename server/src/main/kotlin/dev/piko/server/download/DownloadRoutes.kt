package dev.piko.server.download

import dev.piko.download.DownloadTask
import dev.piko.server.DownloadItem
import dev.piko.server.DownloadListResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.drive.requireLogin
import dev.piko.server.respondError
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.math.roundToLong

/**
 * 本机下载：列表、发起、暂停/继续/取消。
 *
 * 全部转给 `PikoDownloadCoordinator`，这里只做 DTO 转换与参数校验；排队、并发、稀疏暂存、
 * 续传与落盘都在 `:shared` 里，服务端不重复实现。
 */
fun Route.downloadRoutes(graph: ServiceGraph) {
    route("/api/downloads") {
        get {
            call.respond(DownloadListResponse(graph.downloadList()))
        }

        /** 按云盘文件 ID 发起下载：先取文件详情拿到大小与哈希，再入队。 */
        post {
            if (graph.requireLogin(call)) return@post
            val fileId = runCatching { call.receive<dev.piko.server.StartDownloadRequest>() }.getOrNull()
                ?.fileId?.trim().orEmpty()
            if (fileId.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_file_id", "fileId 不能为空")
            }
            graph.driveRepository.getFileDetail(fileId).fold(
                onSuccess = { detail ->
                    if (detail.trashed) {
                        return@fold call.respondError(HttpStatusCode.BadRequest, "file_trashed", "文件在回收站里，不能下载")
                    }
                    val file = FileStat(
                        id = detail.id,
                        name = detail.name,
                        hash = detail.hash,
                        size = detail.sizeBytes.toString(),
                        parentId = detail.parentId,
                        phase = TaskPhase.COMPLETE,
                    )
                    graph.downloadCoordinator.enqueue(file)
                    // 入队是异步的（要在恢复完任务表之后），这里回当下的列表，前端靠 SSE 跟进
                    call.respond(HttpStatusCode.Accepted, DownloadListResponse(graph.downloadList(fileId)))
                },
                onFailure = {
                    call.respondError(HttpStatusCode.BadGateway, "file_detail_failed", it.message ?: "取文件详情失败")
                },
            )
        }

        post("/{id}/pause") {
            graph.downloadCoordinator.pauseDownload(call.parameters["id"].orEmpty())
            call.respond(DownloadListResponse(graph.downloadList()))
        }

        post("/{id}/resume") {
            graph.downloadCoordinator.startDownload(call.parameters["id"].orEmpty())
            call.respond(DownloadListResponse(graph.downloadList()))
        }

        post("/{id}/cancel") {
            graph.downloadCoordinator.cancelDownload(call.parameters["id"].orEmpty())
            call.respond(DownloadListResponse(graph.downloadList()))
        }
    }
}

private fun ServiceGraph.downloadList(fileId: String? = null): List<DownloadItem> {
    val tasks = downloadCoordinator.tasks.value.values
        .filter { if (fileId == null) true else it.fileId == fileId }
        .sortedBy { it.createdAtMs }
    return tasks.map { it.toItem() }
}

private fun DownloadTask.toItem() = DownloadItem(
    id = taskId,
    fileName = displayName,
    fileId = fileId,
    bytesDone = downloadedBytes,
    bytesTotal = totalBytes,
    percent = (progress.toDouble() * 1000).roundToLong() / 10.0,
    speedBytesPerSec = speedBytesPerSec,
    status = status.name,
    error = errorMessage,
    path = destinationPath,
)
