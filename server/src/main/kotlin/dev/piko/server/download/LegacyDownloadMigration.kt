package dev.piko.server.download

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 旧任务迁移结果，供启动日志说明「保留了多少、重下多少」。 */
data class LegacyMigrationResult(
    val total: Int = 0,
    val kept: Int = 0,
    val reset: Int = 0,
    val completed: Int = 0,
)

/**
 * 把旧版 `download-tasks.json` 迁移到 SDK 稀疏缓存语义。
 *
 * 为什么不能直接沿用旧的 `downloadedBytes`：旧版 `SegmentDownloader` 是多段乱序写盘的，
 * 文件中间可能有空洞；SDK 的 `importPrefix` 只能安全导入「从 0 开始的连续前缀」。因此：
 * - `segmented = true` 的任务一律重置为 0，并写一条用户可见的提示（不静默丢进度）；
 * - 顺序下载（`segmented = false`）且已有字节的任务保留进度，交给 SDK 的 `importPrefix` 导入；
 * - 已完成任务不动。
 *
 * 必须在 `PikoDownloadCoordinator` 读取任务表之前调用。
 */
object LegacyDownloadMigration {
    private const val FILE_NAME = "download-tasks.json"
    private const val RESET_NOTE = "旧版分段进度无法迁移到新版传输层，已从头下载"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(DownloadTask.serializer())

    fun migrate(home: Path): LegacyMigrationResult {
        val file = home.resolve(FILE_NAME)
        if (!Files.exists(file)) return LegacyMigrationResult()
        val text = runCatching { Files.readString(file) }.getOrNull()
            ?: return LegacyMigrationResult()
        if (text.isBlank()) return LegacyMigrationResult()
        val tasks = runCatching { json.decodeFromString(serializer, text) }.getOrNull()
            ?: return LegacyMigrationResult()

        var kept = 0
        var reset = 0
        var completed = 0
        var changed = false
        val migrated = tasks.map { task ->
            when {
                task.status == DownloadStatus.COMPLETED -> {
                    completed++
                    task
                }
                task.segmented -> {
                    reset++
                    changed = true
                    task.copy(
                        status = if (task.status == DownloadStatus.DOWNLOADING) {
                            DownloadStatus.PAUSED
                        } else {
                            task.status
                        },
                        segmented = false,
                        sparseCache = false,
                        completedRanges = emptyList(),
                        downloadedBytes = 0L,
                        speedBytesPerSec = 0L,
                        segments = null,
                        errorMessage = RESET_NOTE,
                    )
                }
                else -> {
                    if (task.downloadedBytes > 0L) kept++
                    if (task.sparseCache) {
                        changed = true
                        task.copy(sparseCache = false)
                    } else {
                        task
                    }
                }
            }
        }
        if (changed) {
            runCatching { writeAtomically(file, json.encodeToString(serializer, migrated) + "\n") }
        }
        return LegacyMigrationResult(
            total = tasks.size,
            kept = kept,
            reset = reset,
            completed = completed,
        )
    }

    private fun writeAtomically(file: Path, content: String) {
        val temp = file.resolveSibling("$FILE_NAME.tmp")
        Files.writeString(temp, content)
        runCatching {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
