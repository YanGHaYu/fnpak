package dev.piko.shared.download

import dev.piko.download.DownloadSegmentInfo
import dev.piko.download.DownloadedSpan

/** 分段下载的实时快照：进度、速率与各分段状态。 */
data class DirectDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val segments: List<DownloadSegmentInfo>,
    /** 已有分段彻底失败，剩余部分改由单条连接续传。 */
    val degraded: Boolean = false,
)

/**
 * 整文件下载的落盘实现，由平台注入。服务端给的是「按字节切段、同一直链并发 Range 拉取」的实现
 * （见 `:server` 的 `SegmentDownloader`）；桌面端不注入，仍走 SDK 的稀疏暂存。
 *
 * 约定：
 * 1. 调用前 [targetPath] 处的文件可能已存在且长度为 [totalBytes]（预分配），[completedRanges] 是可信的已落盘区间；
 * 2. 实现必须只写 [targetPath] 这一个文件，不能再叠加第二套写盘逻辑；
 * 3. [onProgress] 约 500 毫秒一次，[onCheckpoint] 约 5 秒一次且取消时也会被调一次，用于落盘续传点；
 * 4. 返回即代表 [totalBytes] 个字节全部落盘（实现内部会校验覆盖），否则抛异常。
 */
interface PikoDirectDownloader {
    val name: String

    suspend fun download(
        fileId: String,
        fileName: String,
        targetPath: String,
        totalBytes: Long,
        completedRanges: List<DownloadedSpan>,
        onProgress: suspend (DirectDownloadProgress) -> Unit,
        onCheckpoint: suspend (List<DownloadedSpan>) -> Unit,
    )
}
