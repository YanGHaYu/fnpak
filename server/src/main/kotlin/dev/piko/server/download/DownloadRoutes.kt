package dev.piko.server.download

import dev.piko.data.repository.FileNameSanitizer
import dev.piko.download.DownloadTask
import dev.piko.server.BatchDownloadRequest
import dev.piko.server.BatchDownloadResponse
import dev.piko.server.DownloadItem
import dev.piko.server.DownloadListResponse
import dev.piko.server.DownloadRecordDeleteFailure
import dev.piko.server.DownloadRecordDeleteRequest
import dev.piko.server.DownloadRecordDeleteResponse
import dev.piko.server.DownloadRetryResponse
import dev.piko.server.FolderDownloadRequest
import dev.piko.server.FolderDownloadResponse
import dev.piko.server.MixedDownloadRequest
import dev.piko.server.MixedDownloadResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.SkippedFile
import dev.piko.server.StartDownloadRequest
import dev.piko.server.drive.requireLogin
import dev.piko.server.respondError
import dev.piko.shared.download.PlannedFile
import dev.piko.shared.download.planFolderDownload
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        /**
         * 同一目录内混合选择：文件直接入队，文件夹递归展开后并进**同一个批次**。
         * 扫描期立即返回 `SCANNING` 与已发现的文件数；完成后同一 `groupId` 变成任务表里的父任务。
         */
        post("/mixed") {
            if (graph.requireLogin(call)) return@post
            val body = runCatching { call.receive<MixedDownloadRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "bad_request", "请求体必须是 JSON")
            val ids = body.ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ids.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_ids", "ids 不能为空")
            }
            val semaphore = Semaphore(4)
            val details = coroutineScope {
                ids.map { id -> id to async { semaphore.withPermit { graph.driveRepository.getFileDetail(id) } } }
                    .map { (id, job) -> id to job.await() }
            }
            val skipped = mutableListOf<SkippedFile>()
            val folders = mutableListOf<FileStat>()
            val files = mutableListOf<FileStat>()
            details.forEach { (id, result) ->
                result.fold(
                    onSuccess = { detail ->
                        when {
                            detail.trashed -> skipped += SkippedFile(id, "在回收站里")
                            detail.kind == FileKind.FOLDER -> folders += detail.toFolderStat()
                            else -> files += detail.toFileStat()
                        }
                    },
                    onFailure = { skipped += SkippedFile(id, it.message ?: "取文件详情失败") },
                )
            }
            if (folders.isEmpty() && files.isEmpty()) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "no_downloadable_items",
                    "没有可下载的文件或文件夹" + if (skipped.isNotEmpty()) "（${skipped.size} 项被跳过）" else "",
                )
            }

            val now = System.currentTimeMillis()
            val groupId = "mixed@$now-${batchSequence.incrementAndGet()}"
            val batchName = buildString {
                if (folders.isNotEmpty()) append("${folders.size} 个文件夹")
                if (folders.isNotEmpty() && files.isNotEmpty()) append(" + ")
                if (files.isNotEmpty()) append("${files.size} 个文件")
            }
            val taken = mutableSetOf<String>()
            val folderRoots = folders.map {
                it.id to uniqueDownloadPath(FileNameSanitizer.sanitizeFolderName(it.name), taken, false)
            }
            val plannedFiles = files.map {
                PlannedFile(it, uniqueDownloadPath(FileNameSanitizer.sanitize(it.name), taken, true))
            }
            val directBytes = plannedFiles.sumOf { it.file.sizeBytes }
            val state = MixedScanState(
                id = groupId,
                name = batchName,
                createdAtMs = now,
                folderCount = folders.size,
                fileCount = files.size,
                filesFound = files.size,
                bytesFound = directBytes,
            )
            graph.mixedScanRegistry.add(state)
            val job: Job = graph.scope.launch {
                try {
                    val planned = mutableListOf<PlannedFile>()
                    var folderFiles = 0
                    var folderBytes = 0L
                    for ((folderId, rootName) in folderRoots) {
                        val folder = folders.first { it.id == folderId }
                        val part = planFolderDownload(folder, graph.serverFolderSource, rootName) { found, bytes ->
                            graph.mixedScanRegistry.update(groupId) {
                                it.copy(
                                    filesFound = files.size + folderFiles + found,
                                    bytesFound = directBytes + folderBytes + bytes,
                                )
                            }
                        }
                        planned += part
                        folderFiles += part.size
                        folderBytes += part.sumOf { it.file.sizeBytes }
                        graph.mixedScanRegistry.update(groupId) {
                            it.copy(filesFound = files.size + folderFiles, bytesFound = directBytes + folderBytes)
                        }
                    }
                    planned += plannedFiles
                    graph.downloadCoordinator.enqueuePlannedBatch(
                        batchId = groupId,
                        batchName = batchName,
                        files = planned,
                        maxHeight = body.maxHeight,
                    )
                    graph.mixedScanRegistry.finish(groupId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    graph.mixedScanRegistry.update(groupId) {
                        it.copy(error = error.message ?: error::class.simpleName ?: "扫描失败")
                    }
                }
            }
            graph.mixedScanRegistry.attach(groupId, job)
            call.respond(
                MixedDownloadResponse(
                    groupId = groupId,
                    folderCount = folders.size,
                    fileCount = files.size,
                    childCount = files.size,
                    bytesTotal = directBytes,
                ),
            )
        }

        // 父任务操作：扫描中的文件夹、多文件批次、单文件任务都能用同一套入口
        post("/groups/{id}/pause") {
            val id = call.parameters["id"].orEmpty()
            when {
                id.isEmpty() -> return@post call.respondError(HttpStatusCode.BadRequest, "missing_group_id", "缺少父任务 ID")
                graph.mixedScanRegistry.contains(id) -> graph.mixedScanRegistry.cancel(id)
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

        /**
         * 批量重试父任务里**只处于失败**的子项：已完成的、正在下载/排队的、用户暂停的一律不动。
         * `single:<taskId>` 也能用；返回每个失败项的结果与可读原因。
         */
        post("/groups/{id}/retry") {
            if (graph.requireLogin(call)) return@post
            val id = call.parameters["id"].orEmpty()
            if (id.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_group_id", "缺少父任务 ID")
            }
            graph.downloadDirProblem()?.let { problem ->
                return@post call.respondError(HttpStatusCode.Conflict, "download_dir_unusable", problem)
            }
            val outcomes = graph.downloadCoordinator.retryFailedInGroup(id)
            val retried = outcomes.filter { it.accepted }.map { it.taskId }
            val skipped = outcomes.filterNot { it.accepted }
                .map { SkippedFile(it.taskId, it.reason ?: "无法重试") }
            if (retried.isEmpty()) {
                val known = if (id.startsWith("single:")) {
                    graph.downloadCoordinator.tasks.value.containsKey(id.removePrefix("single:"))
                } else {
                    graph.downloadCoordinator.tasks.value.values.any { it.batch?.id == id || it.parentTaskId == id }
                }
                val scanning = graph.downloadCoordinator.listings.value.containsKey(id) ||
                    graph.mixedScanRegistry.scans.value.containsKey(id)
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    when {
                        known -> "nothing_to_retry"
                        scanning -> "group_not_ready"
                        else -> "group_not_found"
                    },
                    when {
                        known -> skipped.firstOrNull()?.reason ?: "没有处于失败状态的子项"
                        scanning -> "云端目录仍在扫描，暂时无法重试"
                        else -> "任务记录不存在"
                    },
                )
            }
            val list = graph.downloadListResponse()
            call.respond(
                DownloadRetryResponse(
                    retried = retried,
                    skipped = skipped,
                    message = "已重试 ${retried.size} 个失败项，只补齐各自未完成的区间",
                    items = list.items,
                    groups = list.groups,
                ),
            )
        }

        /** 取消父任务：子项全部停下，**已下载的文件保留**（不调用会删文件的 cancelBatch）。 */
        post("/groups/{id}/cancel") {
            val id = call.parameters["id"].orEmpty()
            if (id.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_group_id", "缺少父任务 ID")
            }
            when {
                graph.mixedScanRegistry.contains(id) -> {
                    graph.mixedScanRegistry.cancel(id)
                    graph.downloadGroupRegistry.markCancelled(id)
                }
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
                    graph.mixedScanRegistry.contains(id) -> {
                        graph.mixedScanRegistry.cancel(id)
                        deleted += id
                    }
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

        /**
         * 单条重试（行内 `⋯` 的「重试」）：只接受失败/中断的任务。
         * 已完成、正在下载或排队中的直接 409 + 可读原因，不产生重复任务。
         */
        post("/{id}/retry") {
            if (graph.requireLogin(call)) return@post
            val id = call.parameters["id"].orEmpty()
            if (id.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_task_id", "缺少任务 ID")
            }
            graph.downloadDirProblem()?.let { problem ->
                return@post call.respondError(HttpStatusCode.Conflict, "download_dir_unusable", problem)
            }
            val outcome = graph.downloadCoordinator.retryTask(id)
            if (!outcome.accepted) {
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "retry_rejected",
                    outcome.reason ?: "该任务当前无法重试",
                )
            }
            val list = graph.downloadListResponse()
            call.respond(
                DownloadRetryResponse(
                    retried = listOf(id),
                    message = "已重新开始，只补齐未完成的区间",
                    items = list.items,
                    groups = list.groups,
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

/**
 * 重试前的落盘体检：目录不存在 / 不可写 / 磁盘满都回可读错误，
 * 而不是等下载协程抛出裸异常后只在日志里看得见。
 */
private fun ServiceGraph.downloadDirProblem(): String? {
    val dir = downloadDirectory
    return when {
        !dir.exists() -> "下载目录不存在：${dir.path}"
        !dir.canWrite() -> "下载目录不可写：${dir.path}"
        dir.totalSpace > 0 && dir.usableSpace <= 0L -> "下载磁盘空间不足：${dir.path}"
        else -> null
    }
}

private fun uniqueDownloadPath(raw: String, taken: MutableSet<String>, keepExtension: Boolean): String {
    if (taken.add(raw.lowercase())) return raw
    val base = if (keepExtension) raw.substringBeforeLast('.', raw) else raw
    val extension = raw.removePrefix(base)
    var index = 2
    while (true) {
        val candidate = "$base ($index)$extension"
        if (taken.add(candidate.lowercase())) return candidate
        index++
    }
}

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
