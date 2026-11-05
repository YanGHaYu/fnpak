package dev.piko.server.download

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.server.DownloadGroupItem
import dev.piko.server.ServiceGraph
import java.io.File
import kotlin.math.roundToLong

/** 单文件任务的虚拟分组 ID；父任务为空串时不与真实批次混淆。 */
fun singleGroupId(taskId: String): String = "single:$taskId"

/** 任务所属的父任务 ID；单文件给 `single:<taskId>`，前端据此展开子项。 */
fun DownloadTask.effectiveGroupId(): String =
    parentTaskId.ifBlank { singleGroupId(taskId) }

/**
 * 父任务汇总：优先显示还在扫描的文件夹（`listings`），其余按任务表里的 `parentTaskId` 聚合。
 * 进度按总字节汇总，扫描期用已发现的文件数与字节数。
 */
fun ServiceGraph.downloadGroups(): List<DownloadGroupItem> {
    val scanning = downloadCoordinator.listings.value.values.map { listing ->
        DownloadGroupItem(
            id = listing.batch.id,
            kind = if (listing.batch.isFolder) "folder" else "files",
            name = listing.batch.folderName,
            rootFolderId = listing.batch.sourceFolderId.orEmpty(),
            status = if (listing.error != null) "FAILED" else "SCANNING",
            childCount = listing.filesFound,
            completedCount = 0,
            bytesTotal = listing.bytesFound,
            bytesDone = 0,
            percent = 0.0,
            speedBytesPerSec = 0,
            error = listing.error,
            createdAtMs = listing.createdAtMs,
            path = if (listing.batch.isFolder) File(downloadStorage.rootPath(), listing.batch.folderName).path else downloadStorage.rootPath(),
        )
    }

    val byGroup = downloadCoordinator.tasks.value.values.groupBy { it.effectiveGroupId() }
    val taskGroups = byGroup.map { (groupId, tasks) -> groupOf(groupId, tasks) }
    return (scanning + taskGroups).sortedByDescending { it.createdAtMs }
}

private fun ServiceGraph.groupOf(groupId: String, tasks: List<DownloadTask>): DownloadGroupItem {
    val first = tasks.minByOrNull { it.createdAtMs } ?: tasks.first()
    val batch = first.batch
    val isBatch = tasks.any { it.parentTaskId.isNotBlank() }
    val kind = when {
        batch?.isFolder == true -> "folder"
        isBatch -> "files"
        else -> "single"
    }
    val name = when {
        batch != null -> batch.folderName
        else -> first.displayName
    }
    val total = tasks.sumOf { it.totalBytes }
    val done = tasks.sumOf { it.downloadedBytes }
    val percent = when {
        total > 0 -> (done.toDouble() / total.toDouble() * 1000).roundToLong() / 10.0
        else -> (tasks.map { it.progress.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0) * 100
    }.let { (it * 10).roundToLong() / 10.0 }
    val status = groupStatus(tasks, downloadGroupRegistry.isCancelled(groupId))
    val path = when {
        batch?.isFolder == true -> File(downloadStorage.rootPath(), batch.folderName).path
        kind == "single" -> first.destinationPath.ifBlank { File(downloadStorage.rootPath(), first.fileName).path }
        else -> downloadStorage.rootPath()
    }
    return DownloadGroupItem(
        id = groupId,
        kind = kind,
        name = name,
        rootFolderId = first.rootFolderId,
        status = status,
        childCount = tasks.size,
        completedCount = tasks.count { it.status == DownloadStatus.COMPLETED },
        bytesTotal = total,
        bytesDone = done,
        percent = percent,
        speedBytesPerSec = tasks.sumOf { it.speedBytesPerSec },
        error = tasks.firstNotNullOfOrNull { it.errorMessage }?.takeIf { status == "FAILED" },
        createdAtMs = first.createdAtMs,
        path = path,
    )
}

private fun groupStatus(tasks: List<DownloadTask>, cancelled: Boolean): String {
    if (tasks.all { it.status == DownloadStatus.COMPLETED }) return "COMPLETED"
    if (cancelled) return "CANCELLED"
    if (tasks.any { it.status == DownloadStatus.DOWNLOADING }) return "DOWNLOADING"
    if (tasks.any { it.status == DownloadStatus.PENDING }) return "QUEUED"
    if (tasks.any { it.status == DownloadStatus.FAILED }) return "FAILED"
    if (tasks.any { it.status == DownloadStatus.PAUSED }) return "PAUSED"
    return "QUEUED"
}
