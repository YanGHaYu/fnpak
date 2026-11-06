package dev.piko.server.archive

import dev.piko.server.ServiceGraph
import dev.piko.server.respondError
import dev.piko.server.drive.requireLogin
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.ArchiveRepository
import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.ArchiveEntry
import io.github.nihildigit.pikpak.ArchivePasswordException
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class ArchiveListRequest(
    val fileId: String = "",
    val password: String = "",
    /** 包内子目录路径，空为顶层；用于逐层浏览（前端首期只用顶层）。 */
    val path: String = "",
)

@Serializable
data class ArchiveEntryDto(
    val name: String,
    val path: String,
    val sizeBytes: Long = 0,
    val isFolder: Boolean = false,
    val mimeType: String? = null,
)

@Serializable
data class ArchiveListingDto(
    val name: String,
    val gcid: String,
    val totalSizeBytes: Long,
    val fileCount: Int,
    val folderCount: Int,
    val entries: List<ArchiveEntryDto>,
)

@Serializable
data class ArchiveExtractRequest(
    val fileId: String = "",
    val password: String = "",
    val paths: List<String> = emptyList(),
)

@Serializable
data class ArchiveTaskCreated(val taskId: String)

/**
 * 解压任务状态。`status`：queued / running / done / failed。
 * 密码错误时 `errorCode=unzip_password_incorrect`，前端据此回到密码输入。
 */
@Serializable
data class ArchiveTaskState(
    val taskId: String,
    val fileId: String,
    val name: String,
    val status: String,
    val progress: Int = 0,
    val outputFileId: String? = null,
    val outputName: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

/** 服务端串行提交 + 500ms 轮询 ×120（60s），与桌面端 ArchiveExtractSession 的口径一致。 */
class ArchiveJobRegistry(
    private val repository: ArchiveRepository,
    private val scope: CoroutineScope,
) {
    private class Job(val taskId: String, val fileId: String, val name: String) {
        @Volatile var status: String = "queued"
        @Volatile var progress: Int = 0
        @Volatile var outputFileId: String? = null
        @Volatile var outputName: String? = null
        @Volatile var errorCode: String? = null
        @Volatile var message: String? = null
        @Volatile var updatedAt: Long = System.currentTimeMillis()

        fun snapshot(): ArchiveTaskState = ArchiveTaskState(
            taskId = taskId,
            fileId = fileId,
            name = name,
            status = status,
            progress = progress,
            outputFileId = outputFileId,
            outputName = outputName,
            errorCode = errorCode,
            message = message,
        )
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val submitMutex = Mutex()

    fun snapshot(taskId: String): ArchiveTaskState? = jobs[taskId]?.snapshot()

    fun submit(fileId: String, name: String, gcid: String, password: String, paths: List<String>): String {
        prune()
        val taskId = UUID.randomUUID().toString()
        val job = Job(taskId, fileId, name)
        jobs[taskId] = job
        scope.launch { run(job, gcid, password, paths) }
        return taskId
    }

    /** 只保留 15 分钟内的任务，避免反复解压把内存里的状态堆满。 */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - JOB_TTL_MILLIS
        jobs.entries.removeIf { (_, job) -> job.updatedAt < cutoff }
    }

    private suspend fun run(job: Job, gcid: String, password: String, paths: List<String>) {
        job.status = "running"
        job.updatedAt = System.currentTimeMillis()
        // 提交阶段串行：并发 decompress 会被 PikPak 以「系统繁忙」拒绝
        val started = submitMutex.withLock {
            repository.start(FileStat(id = job.fileId, name = job.name, hash = gcid), password, paths)
        }
        val task = started.getOrElse { error ->
            fail(job, error)
            return
        }
        job.progress = 0
        repeat(POLL_ATTEMPTS) {
            val progress = repository.progress(task.taskId).getOrElse { error ->
                fail(job, error)
                return
            }
            job.progress = progress.progress.coerceIn(0, 100)
            job.updatedAt = System.currentTimeMillis()
            when (progress.phase) {
                TaskPhase.COMPLETE -> {
                    job.status = "done"
                    job.progress = 100
                    job.outputFileId = progress.fileId.takeIf { it.isNotEmpty() }
                    job.outputName = job.name.substringBeforeLast('.', job.name)
                    job.updatedAt = System.currentTimeMillis()
                    PikoLog.i(TAG, "解压任务 ${job.fileId} 完成，产物 ${job.outputFileId ?: "未知"}")
                    return
                }
                TaskPhase.ERROR -> {
                    val cause = repository.failureCause(task.taskId)
                    fail(job, null, cause)
                    return
                }
            }
            delay(POLL_INTERVAL)
        }
        fail(job, null, null, code = "extract_timeout", message = "解压超时（60 秒），请稍后重试")
    }

    private fun fail(job: Job, error: Throwable?, cause: String? = null, code: String? = null, message: String? = null) {
        val mapped = if (error != null) mapError(error) else mapResultCode(cause) ?: ("archive_extract_failed" to "解压失败，请稍后重试")
        job.status = "failed"
        job.errorCode = code ?: mapped.first
        job.message = message ?: mapped.second
        job.updatedAt = System.currentTimeMillis()
        // 原始异常只进日志，不回给前端（可能包含接口响应细节）
        error?.let { PikoLog.w(TAG, "解压任务 ${job.fileId} 失败：${it.message ?: it::class.simpleName}") }
        cause?.let { PikoLog.w(TAG, "解压任务 ${job.fileId} 云端失败原因：$it") }
    }

    private fun mapError(error: Throwable): Pair<String, String> {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        if (chain.any { it is ArchivePasswordException }) {
            val incorrect = chain.filterIsInstance<ArchivePasswordException>().any { it.incorrect }
            return if (incorrect) "unzip_password_incorrect" to "解压密码不正确" else "unzip_password_required" to "该压缩包已加密，请输入密码"
        }
        val api = chain.filterIsInstance<PikPakException>().firstOrNull()
        val reason = api?.errorMessage ?: error.message
        return mapResultCode(reason) ?: ("archive_extract_failed" to "解压失败，请稍后重试")
    }

    private fun mapResultCode(reason: String?): Pair<String, String>? {
        val text = reason.orEmpty()
        return when {
            text.isBlank() -> null
            text.contains(ArchiveRepository.INVALID_PASSWORD, true) || text.contains("E_INVALID_PASSWORD", true) ->
                "unzip_password_incorrect" to "解压密码不正确"
            text.contains(ArchiveRepository.INVALID_FORMAT, true) || text.contains("INVALID_FILE_FORMAT", true) ->
                "unsupported_format" to "无法识别压缩包格式，文件可能已损坏或为分卷压缩包"
            text.contains("NEED_MORE_QUOTA", true) -> "need_more_quota" to "解压额度不足"
            text.contains("space_not_enough", true) -> "space_not_enough" to "网盘空间不足"
            else -> null
        }
    }

    companion object {
        private const val TAG = "Archive"
        private const val POLL_ATTEMPTS = 120
        private const val JOB_TTL_MILLIS = 15 * 60 * 1000L
        private val POLL_INTERVAL = 500.milliseconds
    }
}

fun Route.archiveRoutes(graph: ServiceGraph) {
    route("/api/archive") {
        post("/list") {
            if (graph.requireLogin(call)) return@post
            val body = call.receiveSafely<ArchiveListRequest>() ?: return@post
            if (body.fileId.isBlank()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_file_id", "缺少文件 ID")
            }
            val detail = graph.driveRepository.getFileDetail(body.fileId).getOrElse { error ->
                PikoLog.w("Archive", "读取压缩包 ${body.fileId} 详情失败：${error.message}")
                return@post call.respondError(HttpStatusCode.BadGateway, "archive_detail_failed", "无法读取压缩包信息，请稍后重试")
            }
            val gcid = detail.hash
            if (gcid.isBlank()) {
                return@post call.respondError(HttpStatusCode.Conflict, "file_verifying", "文件正在校验，请稍后重试")
            }
            val listing = graph.archiveRepository
                .list(ArchiveLocation(archiveId = body.fileId, gcid = gcid, path = body.path), body.password)
                .getOrElse { error -> return@post call.respondArchiveFailure(error, "archive_list_failed") }
            call.respond(
                ArchiveListingDto(
                    name = detail.name,
                    gcid = gcid,
                    totalSizeBytes = listing.files.sumOf { it.entrySize() },
                    fileCount = listing.files.count { !it.isFolder },
                    folderCount = listing.files.count { it.isFolder },
                    entries = listing.files.map { it.toDto() },
                ),
            )
        }

        post("/extract") {
            if (graph.requireLogin(call)) return@post
            val body = call.receiveSafely<ArchiveExtractRequest>() ?: return@post
            if (body.fileId.isBlank()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_file_id", "缺少文件 ID")
            }
            val detail = graph.driveRepository.getFileDetail(body.fileId).getOrElse { error ->
                PikoLog.w("Archive", "读取压缩包 ${body.fileId} 详情失败：${error.message}")
                return@post call.respondError(HttpStatusCode.BadGateway, "archive_detail_failed", "无法读取压缩包信息，请稍后重试")
            }
            if (detail.kind != "file" || !isExtractableArchive(detail.name) || isArchiveVolume(detail.name)) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "unsupported_format",
                    "只支持单卷 zip/rar/7z；分卷与 tar 暂不支持在线解压",
                )
            }
            val gcid = detail.hash
            if (gcid.isBlank()) {
                return@post call.respondError(HttpStatusCode.Conflict, "file_verifying", "文件正在校验，请稍后重试")
            }
            val taskId = graph.archiveJobs.submit(body.fileId, detail.name, gcid, body.password, body.paths)
            call.respond(HttpStatusCode.Accepted, ArchiveTaskCreated(taskId))
        }

        get("/tasks/{id}") {
            if (graph.requireLogin(call)) return@get
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, "missing_task_id", "缺少任务 ID")
            }
            val snapshot = graph.archiveJobs.snapshot(id)
                ?: return@get call.respondError(HttpStatusCode.NotFound, "archive_task_not_found", "解压任务不存在或已过期")
            call.respond(snapshot)
        }
    }
}

/** 请求体解析失败统一 400，避免 Ktor 默认 500。 */
private suspend inline fun <reified T : Any> ApplicationCall.receiveSafely(): T? = try {
    receive<T>()
} catch (_: Exception) {
    respondError(HttpStatusCode.BadRequest, "invalid_body", "请求参数格式不正确")
    null
}

private suspend fun ApplicationCall.respondArchiveFailure(error: Throwable, fallbackCode: String) {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val password = chain.filterIsInstance<ArchivePasswordException>().firstOrNull()
    if (password != null) {
        return if (password.incorrect) {
            respondError(HttpStatusCode.BadRequest, "unzip_password_incorrect", "解压密码不正确")
        } else {
            respondError(HttpStatusCode.BadRequest, "unzip_password_required", "该压缩包已加密，请输入密码")
        }
    }
    val api = chain.filterIsInstance<PikPakException>().firstOrNull()
    val reason = api?.errorMessage ?: error.message
    val mapped = when {
        reason.isNullOrBlank() -> null
        reason.contains(ArchiveRepository.INVALID_FORMAT, true) || reason.contains("INVALID_FILE_FORMAT", true) ->
            "unsupported_format" to "无法识别压缩包格式，文件可能已损坏或为分卷压缩包"
        reason.contains("NEED_MORE_QUOTA", true) -> "need_more_quota" to "解压额度不足"
        reason.contains("space_not_enough", true) -> "space_not_enough" to "网盘空间不足"
        else -> null
    }
    val (code, message) = mapped ?: (fallbackCode to "读取压缩包失败，请稍后重试")
    PikoLog.w("Archive", "列压缩包目录失败（$code）：${error.message ?: error::class.simpleName}")
    respondError(HttpStatusCode.BadGateway, code, message)
}

private fun ArchiveEntry.entrySize(): Long = if (isFolder) 0 else sizeBytes

private fun ArchiveEntry.toDto(): ArchiveEntryDto {
    val text = path.ifBlank { name }
    return ArchiveEntryDto(
        name = name.ifBlank { text.substringAfterLast('/').ifEmpty { text } },
        path = text,
        sizeBytes = entrySize(),
        isFolder = isFolder,
        mimeType = mimeType.takeIf { it.isNotBlank() },
    )
}
