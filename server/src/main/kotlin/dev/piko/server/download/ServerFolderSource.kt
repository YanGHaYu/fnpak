package dev.piko.server.download

import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.download.DownloadFolderSource
import io.github.nihildigit.pikpak.FileStat

/**
 * 服务端的文件夹递归来源：`listBrowsable` 由 SDK 翻页取全，再按名称排序。
 * 不做今日额度拦截（`remainingDailyDownload = null`），否则大目录会停在「等用户确认」，
 * 与「点一下就入队」的服务端语义不符。
 */
class ServerFolderSource(private val driveRepository: PikoDriveRepository) : DownloadFolderSource {
    override suspend fun list(folderId: String): List<FileStat> =
        driveRepository.listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
            .filterNot { LocalLocation.isLocal(it.id) }

    override suspend fun remainingDailyDownload(): Long? = null
}
