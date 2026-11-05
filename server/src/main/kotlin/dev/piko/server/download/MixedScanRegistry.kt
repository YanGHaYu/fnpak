package dev.piko.server.download

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 混合下载（选中的文件 + 文件夹）在服务端递归扫描期间的临时状态。
 *
 * 扫描完成后这批会变成任务表里同一个 `parentTaskId` 的一批任务，本项随即移除；
 * 前端在扫描期看到 `SCANNING` 与 `filesFound`，不能用 0 冒充未知的文件夹大小。
 */
data class MixedScanState(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val folderCount: Int,
    val fileCount: Int,
    /** 已发现的文件数（含直接选中的文件）。 */
    val filesFound: Int,
    val bytesFound: Long,
    val error: String? = null,
)

/** 扫描任务句柄与展示状态分开：取消时先停协程，再撤掉这一行。 */
class MixedScanRegistry {
    private val _scans = MutableStateFlow<Map<String, MixedScanState>>(emptyMap())
    val scans: StateFlow<Map<String, MixedScanState>> = _scans.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun add(state: MixedScanState) {
        _scans.update { it + (state.id to state) }
    }

    fun attach(id: String, job: Job) {
        jobs[id] = job
        if (!_scans.value.containsKey(id)) job.cancel()
    }

    fun start(state: MixedScanState, job: Job) {
        jobs[state.id] = job
        _scans.update { it + (state.id to state) }
    }

    fun update(id: String, transform: (MixedScanState) -> MixedScanState) {
        _scans.update { current -> current[id]?.let { current + (id to transform(it)) } ?: current }
    }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        _scans.update { it - id }
    }

    /** 正常结束：只撤状态，不取消正在收尾的协程。 */
    fun finish(id: String) {
        jobs.remove(id)
        _scans.update { it - id }
    }

    fun cancel(id: String): Boolean = jobs.remove(id)?.also { it.cancel() }
        ?.let { _scans.update { scans -> scans - id }; true } ?: false

    fun contains(id: String): Boolean = _scans.value.containsKey(id)
}
