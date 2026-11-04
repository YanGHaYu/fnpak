package dev.piko.server.download

import dev.piko.shared.download.PikoDownloadStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `PikoDownloadStorage` 的 JVM 实现，语义与 `:desktopApp` 的 `DesktopPikoDownloadStorage` 一致：
 * 普通目录写入即最终位置，`commit` 原样返回。
 *
 * 之所以在 server 侧再写一份而不是复用桌面实现：那个类在 `:desktopApp` 里，依赖 Compose/skiko，
 * 服务端不允许把这些拖进来。这里只有目录与文件的搬运，不涉及任何下载调度逻辑。
 */
class ServerDownloadStorage(
    private val cacheDirectory: File,
    private val directoryProvider: () -> File,
) : PikoDownloadStorage {
    private val directory: File get() = directoryProvider()

    /** 暂存在下载目录之外，用户看到的目录大小不含未完成的块。 */
    override suspend fun cacheTarget(name: String): String = withContext(Dispatchers.IO) {
        cacheDirectory.mkdirs()
        cacheDirectory.resolve("$name.data").absolutePath
    }

    override fun pathFor(fileName: String): String = directory.resolve(fileName).absolutePath

    override suspend fun downloadTarget(fileName: String): String = withContext(Dispatchers.IO) {
        val target = directory.resolve(fileName)
        target.parentFile?.mkdirs()
        target.absolutePath
    }

    override suspend fun commit(fileName: String, downloadedPath: String): String = downloadedPath

    override suspend fun existingLength(fileName: String): Long = withContext(Dispatchers.IO) {
        directory.resolve(fileName).takeIf { it.isFile }?.length() ?: 0L
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        directory.resolve(fileName).exists()
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).takeIf { it.isAbsolute }?.delete() == true || directory.resolve(path).delete()
    }

    override suspend fun locate(fileName: String): String? = withContext(Dispatchers.IO) {
        directory.resolve(fileName).takeIf { it.exists() }?.absolutePath
    }

    override suspend fun pruneEmptyFolders(folder: String) = withContext(Dispatchers.IO) {
        val root = directory.resolve(folder)
        if (root.isDirectory) root.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
    }
}
