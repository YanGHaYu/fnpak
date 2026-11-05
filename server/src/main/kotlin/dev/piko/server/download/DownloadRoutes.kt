package dev.piko.server.download

import dev.piko.download.DownloadTask
import dev.piko.server.BatchDownloadRequest
import dev.piko.server.BatchDownloadResponse
import dev.piko.server.DownloadItem
import dev.piko.server.DownloadListResponse
import dev.piko.server.DownloadRecordDeleteFailure
import dev.piko.server.DownloadRecordDeleteRequest
import dev.piko.server.DownloadRecordDeleteResponse
import dev.piko.server.FolderDownloadRequest
import dev.piko.server.FolderDownloadResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.SkippedFile
import dev.piko.server.StartDownloadRequest
import dev.piko.server.drive.requireLogin
import dev.piko.server.respondError
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val batchSequence = AtomicLong()

/**
 * 本机下载：单文件、多选批次、整个文件夹、父任务暂停/继续/取消。
 *
 * 排队、递归扫描、并发与落盘都在 `:shared` 的 `PikoDownloadCoordinator` 里；这里只做 DTO 转换、
 * 参数校验和「父任务 => 子项」的聚合。取消父任务走 `pauseBatch`（保留已下载文件），
 * 不用会删文件的 `cancelBatch`。
 */
fun Route.downloadRoutes(graph: ServiceGraph) {
    route("/api/downloads") {
        get {
            call.respond(graph.downloadListResponse())
        }

        /** 按云盘文件 ID 发起单个下载：先取文件详情拿到大小与哈希，再入队。 */
        post {
            if (graph.requireLogin(call)) return@post
            val fileId = runCatching { call.receive<StartDownloadRequest>() }.getOrNull()
                ?.fileId?.trim().orEmpty()
            if (fileId.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_file_id", "fileId 不能为空")
            }
            graph.driveRepository.getFileDetail(fileId).fold(
                onSuccess = { detail ->
                    if (detail.trashed) {
                        return@fold call.respondError(HttpStatusCode.BadRequest, "file_trashed", "文件在回收站里，不能下载")
                    }
                    if (detail.kind == FileKind.FOLDER) {
                        return@fold call.respondError(
                            HttpStatusCode.BadRequest,
                            "not_a_file",
                            "这是文件夹，请用「下载文件夹」",
                        )
                    }
                    graph.downloadCoordinator.enqueue(detail.toFileStat())
                    // 入队是异步的（要先等任务表恢复），这里回当下的列表，前端靠 SSE 跟进
                    call.respond(HttpStatusCode.Accepted, graph.downloadListResponse(fileId))
                },
                onFailure = {
                    call.respondError(HttpStatusCode.BadGateway, "file_detail_failed", it.message ?: "取文件详情失败")
                },
            )
        }

        /** 多选文件：逐个入队但共享同一个父任务（批次），进度按批次合并。 */
        post("/batch") {
            if (graph.requireLogin(call)) return@post
            val ids = runCatching { call.receive<BatchDownloadRequest>() }.getOrNull()
                ?.fileIds?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            if (ids.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_file_ids", "fileIds 不能为空")
            }
            val skipped = mutableListOf<SkippedFile>()
            val semaphore = Semaphore(4)
            val details = coroutineScope {
                ids.map { id ->
                    id to async { semaphore.withPermit { graph.driveRepository.getFileDetail(id) } }
                }.map { (id, job) -> id to job.await() }
            }
            val files = details.mapNotNull { (id, result) ->
                result.fold(
                    onSuccess = { detail ->
                        when {
                            detail.trashed -> {
                                skipped += SkippedFile(id, "在回收站里")
                                null
                            }
                            detail.kind == FileKind.FOLDER -> {
                                skipped += SkippedFile(id, "这是文件夹，请用「下载文件夹」")
                                null
                            }
                            else -> detail.toFileStat()
                        }
                    },
                    onFailure = {
                        skipped += SkippedFile(id, it.message ?: "取文件详情失败")
                        null
                    },
                )
            }
            if (files.isEmpty()) {
                return@post call.respond(
                    BatchDownloadResponse(childCount = 0, skipped = skipped),
                )
            }
            // 多选时传入固定批次 ID，回包能立刻给出父任务；单个文件不建批次
            val groupId = if (files.size > 1) "batch@${System.currentTimeMillis()}-${batchSequence.incrementAndGet()}" else ""
            graph.downloadCoordinator.enqueueFiles(files, batchId = groupId.takeIf { it.isNotEmpty() })
            call.respond(
                BatchDownloadResponse(
                    groupId = groupId,
                    childCount = files.size,
                    bytesTotal = files.sumOf { it.sizeBytes },
                    skipped = skipped,
                ),
            )
        }

        /** 整个文件夹：服务端递归分页列出全部文件，汇总后才入队；扫描期回 SCANNING。 */
        post("/folder") {
            if (graph.requireLogin(call)) return@post
            val folderId = runCatching { call.receive<FolderDownloadRequest>() }.getOrNull()
                ?.folderId?.trim().orEmpty()
            if (folderId.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_folder_id", "folderId 不能为空")
            }
            graph.driveRepository.getFileDetail(folderId).fold(
                onSuccess = { detail ->
                    if (detail.trashed) {
                        return@fold call.respondError(HttpStatusCode.BadRequest, "folder_trashed", "文件夹在回收站里，不能下载")
                    }
                    if (detail.kind != FileKind.FOLDER) {
                        return@fold call.respondError(HttpStatusCode.BadRequest, "not_a_folder", "这个 ID 不是文件夹")
                    }
                    graph.downloadCoordinator.enqueueFolders(listOf(detail.toFolderStat()), graph.serverFolderSource)
                    val listing = graph.downloadCoordinator.listings.value.values
                        .firstOrNull { it.batch.sourceFolderId == detail.id }
                    if (listing == null) {
                        return@fold call.respondError(
                            HttpStatusCode.Conflict,
                            "folder_already_queued",
                            "这个文件夹已经在下载或扫描中",
                        )
                    }
                    call.respond(
                        FolderDownloadResponse(
                            groupId = listing.batch.id,
                            phase = "SCANNING",
                            childCount = listing.filesFound,
                            bytesTotal = listing.bytesFound,
                        ),
                    )
                },
                onFailure = {
                    call.respondError(HttpStatusCode.BadGateway, "folder_detail_failed", it.message ?: "取文件夹详情失败")
                },
            )
        }

        // 父任务操作：扫描中的文件夹、多文件批次、单文件任务都能用同一套入口
        post("/groups/{id}/pause") {
            val id = call.parameters["id"].orEmpty()
            when {
                id.isEmpty() -> return@post call.respondError(HttpStatusCode.BadRequest, "missing_group_id", "缺少父任务 ID")
                graph.downloadCoordinator.listings.value.containsKey(id) -> graph.downloadCoordinator.dismissListing(id)
                id.startsWith("single:") -> graph.downloadCoordinator.pauseDownload(id.removePrefix("single:"))
                else -> graph.downloadCoordinator.pauseBatch(id)
            }
            call.respond(graph.downloadListResponse())
        }

        post("/groups/{id}/resume") {
            val id = call.parameters["id"].orEmpty()
            if (id.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_group_id", "缺少父任务 ID")
            }
            graph.downloadGroupRegistry.clearCancelled(id)
            when {
                graph.downloadCoordinator.listings.value.containsKey(id) -> graph.downloadCoordinator.retryListing(id)
                id.startsWith("single:") -> graph.downloadCoordinator.startDownload(id.removePrefix("single:"))
                else -> graph.downloadCoordinator.resumeBatch(id)
            }
            call.respond(graph.downloadListResponse())
        }

        /** 取消父任务：子项全部停下，**已下载的文件保留**（不调用会删文件的 cancelBatch）。 */
        post("/groups/{id}/cancel") {
            val id = call.parameters["id"].orEmpty()
            if (id.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_group_id", "缺少父任务 ID")
            }
            when {
                graph.downloadCoordinator.listings.value.containsKey(id) -> {
                    graph.downloadCoordinator.dismissListing(id)
                }
                id.startsWith("single:") -> {
                    graph.downloadCoordinator.pauseDownload(id.removePrefix("single:"))
                    graph.downloadGroupRegistry.markCancelled(id)
                }
                else -> {
                    graph.downloadCoordinator.pauseBatch(id)
                    graph.downloadGroupRegistry.markCancelled(id)
                }
            }
            call.respond(graph.downloadListResponse())
        }

        /**
         * 删除本机下载任务**记录**：只清任务表与进度，磁盘文件一个都不删。
         * 进行中的任务会先停下协程，已写入的分块/临时文件保留（不是 cancelBatch）。
         */
        post("/records/delete") {
            val ids = runCatching { call.receive<DownloadRecordDeleteRequest>() }.getOrNull()
                ?.ids?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            if (ids.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_group_ids", "ids 不能为空")
            }
            val deleted = mutableListOf<String>()
            val failed = mutableListOf<DownloadRecordDeleteFailure>()
            var removedTasks = 0
            for (id in ids) {
                when {
                    graph.downloadCoordinator.listings.value.containsKey(id) -> {
                        graph.downloadCoordinator.dismissListing(id)
                        deleted += id
                    }
                    id.startsWith("single:") -> {
                        val taskId = id.removePrefix("single:")
                        val removed = graph.downloadCoordinator.removeTaskRecords(listOf(taskId))
                        if (removed.isEmpty()) {
                            failed += DownloadRecordDeleteFailure(id, "任务记录不存在")
                        } else {
                            removedTasks += removed.size
                            deleted += id
                        }
                    }
                    else -> {
                        val taskIds = graph.downloadCoordinator.tasks.value.values
                            .filter { it.parentTaskId == id }
                            .map { it.taskId }
                        if (taskIds.isEmpty()) {
                            failed += DownloadRecordDeleteFailure(id, "任务记录不存在")
                        } else {
                            removedTasks += graph.downloadCoordinator.removeTaskRecords(taskIds).size
                            deleted += id
                        }
                    }
                }
                graph.downloadGroupRegistry.clearCancelled(id)
            }
            call.respond(
                DownloadRecordDeleteResponse(
                    deleted = deleted,
                    failed = failed,
                    removedTasks = removedTasks,
                ),
            )
        }

        post("/{id}/pause") {
            graph.downloadCoordinator.pauseDownload(call.parameters["id"].orEmpty())
            call.respond(graph.downloadListResponse())
        }

        post("/{id}/resume") {
            graph.downloadCoordinator.startDownload(call.parameters["id"].orEmpty())
            call.respond(graph.downloadListResponse())
        }

        post("/{id}/cancel") {
            graph.downloadCoordinator.cancelDownload(call.parameters["id"].orEmpty())
            call.respond(graph.downloadListResponse())
        }
    }
}

internal fun ServiceGraph.downloadListResponse(fileId: String? = null): DownloadListResponse {
    val tasks = downloadCoordinator.tasks.value.values
        .filter { if (fileId == null) true else it.fileId == fileId }
        .sortedBy { it.createdAtMs }
    return DownloadListResponse(tasks.map { it.toItem() }, downloadGroups())
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
    parentTaskId = parentTaskId,
    groupId = effectiveGroupId(),
    rootFolderId = rootFolderId,
    batchName = batch?.folderName.orEmpty(),
    batchIsFolder = batch?.isFolder == true,
)

private fun io.github.nihildigit.pikpak.FileDetail.toFileStat() = FileStat(
    id = id,
    name = name,
    hash = hash,
    size = sizeBytes.toString(),
    parentId = parentId,
    kind = kind,
    phase = TaskPhase.COMPLETE,
)

private fun io.github.nihildigit.pikpak.FileDetail.toFolderStat() = FileStat(
    id = id,
    name = name,
    hash = hash,
    size = sizeBytes.toString(),
    parentId = parentId,
    kind = FileKind.FOLDER,
    phase = TaskPhase.COMPLETE,
)
