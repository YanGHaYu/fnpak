package dev.piko.server.tasks

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.sourceUrl
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.TaskPhase
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 一个离线任务在列表里要显示的名字、类型与来源。 */
data class ResolvedTaskName(
    val name: String,
    val source: String,
    val type: String,
    val folderChildCount: Int = 0,
    val folderChildCountTruncated: Boolean = false,
)

/**
 * 离线任务名字解析。优先级固定：
 * `file_name` → 终态任务按 `fileId` 查详情 → `params.display_name` → 磁力 `dn=` → `MAGNET <hash8>`。
 *
 * 详情只为「终态且 file_name 为空」的任务查，避免给排队中的任务发无效请求；按 `fileId` 缓存，
 * 一次列表最多 4 个并发。文件夹任务的子项数只取第一页（100 条），还有下一页就标 truncated，不谎报全量。
 */
class TaskNameResolver(private val driveRepository: PikoDriveRepository) {
    private data class FileMeta(
        val name: String,
        val folder: Boolean,
        val childCount: Int = 0,
        val childCountTruncated: Boolean = false,
    )

    private val cache = ConcurrentHashMap<String, FileMeta>()
    private val semaphore = Semaphore(4)

    suspend fun resolve(task: DriveTask): ResolvedTaskName {
        val meta = if (task.phase in TaskPhase.TERMINAL && task.fileId.isNotBlank()) lookup(task.fileId) else null
        val fileName = task.fileName?.takeIf { it.isNotBlank() }
        val type = when {
            meta?.folder == true -> "folder"
            meta != null -> "file"
            task.fileId.isNotBlank() -> "file"
            else -> "unknown"
        }
        if (fileName != null) {
            return ResolvedTaskName(
                name = fileName,
                source = "file_name",
                type = type,
                folderChildCount = meta?.childCount ?: 0,
                folderChildCountTruncated = meta?.childCountTruncated ?: false,
            )
        }
        if (meta?.name?.isNotBlank() == true) {
            return ResolvedTaskName(
                name = meta.name,
                source = "file_detail",
                type = type,
                folderChildCount = meta.childCount,
                folderChildCountTruncated = meta.childCountTruncated,
            )
        }
        val display = task.params["display_name"]?.takeIf { it.isNotBlank() }
        if (display != null) {
            return ResolvedTaskName(display, "display_name", type)
        }
        magnetDisplayName(task.sourceUrl.orEmpty())?.let {
            return ResolvedTaskName(it, "magnet_dn", type)
        }
        return ResolvedTaskName("MAGNET ${magnetHash(task)}", "fallback", type)
    }

    private suspend fun lookup(fileId: String): FileMeta? {
        cache[fileId]?.let { return it }
        return semaphore.withPermit {
            cache[fileId] ?: run {
                val detail = driveRepository.getFileDetail(fileId).getOrNull() ?: return@withPermit null
                val folder = detail.kind == FileKind.FOLDER
                val (children, truncated) = if (folder) firstPageCount(fileId) else 0 to false
                FileMeta(detail.name, folder, children, truncated).also { cache[fileId] = it }
            }
        }
    }

    /** 文件夹任务只数第一页：全量递归会把每次列表都拖成一次目录扫描。 */
    private suspend fun firstPageCount(folderId: String): Pair<Int, Boolean> =
        driveRepository.listFiles(folderId, "", PikoFileSortOrder.NAME_ASC).fold(
            onSuccess = { (items, next) -> items.count { !it.trashed } to next.isNotBlank() },
            onFailure = { 0 to false },
        )

    private fun magnetDisplayName(url: String): String? =
        url.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun magnetHash(task: DriveTask): String {
        val match = Regex("urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(task.sourceUrl.orEmpty())
        return (match?.groupValues?.getOrNull(1) ?: task.id).take(8).uppercase()
    }
}
