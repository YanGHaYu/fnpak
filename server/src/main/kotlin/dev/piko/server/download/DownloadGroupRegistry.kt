package dev.piko.server.download

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「取消父任务」与「暂停」的区别：暂停还能继续，取消表示用户不要这一批了，但**保留已下载的文件**。
 * 任务表里的子项只会变成 PAUSED，父任务的状态由这份标记补成 CANCELLED。标记落盘，重启后仍然显示取消。
 */
@Serializable
private data class CancelledGroups(val cancelled: List<String> = emptyList())

class DownloadGroupRegistry(private val home: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: Path = home.resolve("download-groups.json")
    private var cancelled: Set<String> = load()

    @Synchronized
    fun isCancelled(groupId: String): Boolean = groupId in cancelled

    @Synchronized
    fun markCancelled(groupId: String) {
        if (groupId.isBlank() || groupId in cancelled) return
        cancelled = cancelled + groupId
        save()
    }

    @Synchronized
    fun clearCancelled(groupId: String) {
        if (groupId !in cancelled) return
        cancelled = cancelled - groupId
        save()
    }

    private fun load(): Set<String> = runCatching {
        if (!Files.exists(file)) return@runCatching emptySet()
        json.decodeFromString<CancelledGroups>(Files.readString(file)).cancelled.toSet()
    }.onFailure {
        System.err.println("[W] Server: download-groups.json 解析失败，按空处理：${it.message}")
    }.getOrDefault(emptySet())

    private fun save() {
        runCatching {
            Files.createDirectories(home)
            val temp = file.resolveSibling("download-groups.json.tmp")
            Files.writeString(
                temp,
                json.encodeToString(CancelledGroups.serializer(), CancelledGroups(cancelled.sorted())) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            runCatching { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        }.onFailure {
            System.err.println("[W] Server: 写入 download-groups.json 失败：${it.message}")
        }
    }
}
