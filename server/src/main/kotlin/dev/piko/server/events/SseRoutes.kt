package dev.piko.server.events

import dev.piko.server.DownloadGroupRemovedEvent
import dev.piko.server.DownloadProgressEvent
import dev.piko.server.ServiceGraph
import dev.piko.server.TaskProgressEvent
import dev.piko.server.TaskRemovedEvent
import dev.piko.server.download.downloadGroups
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.math.roundToLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val HEARTBEAT_SECONDS = 15L
private const val TASK_POLL_SECONDS = 3L
private val json = Json { encodeDefaults = true }

/**
 * SSE 通道：`download.progress` + `task.progress` + 心跳。
 *
 * 多个来源（下载任务 StateFlow、离线任务轮询、心跳）都写进同一个 [Channel]，由单协程顺序写，
 * 避免并发写同一 Writer。格式严格 `event: <name>\ndata: <json>\n\n`；注释行只用于保活。
 *
 * 客户端断开时调用被取消，[coroutineScope] 连带取消子协程，不留下轮询任务；重连后先推当前快照。
 */
fun Route.sseRoutes(graph: ServiceGraph) {
    get("/api/events") {
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            write(": connected\n\n")
            flush()
            coroutineScope {
                val events = Channel<String>(Channel.UNLIMITED)
                launch { streamDownloads(graph, events) }
                launch { streamGroups(graph, events) }
                launch { streamTaskRemovals(graph, events) }
                launch { streamTasks(graph, events) }
                launch {
                    while (true) {
                        delay(HEARTBEAT_SECONDS * 1000)
                        events.send(": heartbeat ${System.currentTimeMillis()}\n\n")
                    }
                }
                for (event in events) {
                    write(event)
                    flush()
                }
            }
        }
    }
}

/** 本机下载：按任务 ID 去抖，同一任务同一进度只发一次。 */
private suspend fun streamDownloads(graph: ServiceGraph, out: SendChannel<String>) {
    val sent = mutableMapOf<String, String>()
    graph.downloadCoordinator.tasks.collect { tasks ->
        for (task in tasks.values) {
            val stamp = "${task.downloadedBytes}/${task.totalBytes}/${task.status}/${task.speedBytesPerSec}/${task.segments?.hashCode()}"
            if (sent[task.taskId] == stamp) continue
            sent[task.taskId] = stamp
            val event = DownloadProgressEvent(
                id = task.taskId,
                fileName = task.displayName,
                bytesDone = task.downloadedBytes,
                bytesTotal = task.totalBytes,
                percent = (task.progress.toDouble() * 1000).roundToLong() / 10.0,
                speedBytesPerSec = task.speedBytesPerSec,
                status = task.status.name,
                segments = task.segments,
            )
            out.send("event: download.progress\ndata: ${json.encodeToString(event)}\n\n")
        }
    }
}

/** 父任务汇总：扫描期与下载期都按同一个批次 ID 推送，前端把一行进度条合并显示。 */
private suspend fun streamGroups(graph: ServiceGraph, out: SendChannel<String>) {
    val sent = mutableMapOf<String, String>()
    combine(graph.downloadCoordinator.tasks, graph.downloadCoordinator.listings) { _, _ -> Unit }
        .collect {
            val current = graph.downloadGroups()
            val currentIds = current.mapTo(mutableSetOf()) { it.id }
            // 任务表里已经消失的父任务要明确推 removed，否则前端留着幽灵进度条
            for (id in sent.keys.toList()) {
                if (id !in currentIds) {
                    sent.remove(id)
                    out.send("event: download.group.removed\ndata: ${json.encodeToString(DownloadGroupRemovedEvent(id))}\n\n")
                }
            }
            for (group in current) {
                val stamp = "${group.status}/${group.childCount}/${group.completedCount}/${group.bytesDone}/${group.bytesTotal}/${group.percent}"
                if (sent[group.id] == stamp) continue
                sent[group.id] = stamp
                out.send("event: download.group\ndata: ${json.encodeToString(group)}\n\n")
            }
        }
}

/** 删除离线任务后由路由发出，前端据此把行从列表里移除。 */
private suspend fun streamTaskRemovals(graph: ServiceGraph, out: SendChannel<String>) {
    graph.taskRemovalEvents.collect { id ->
        out.send("event: task.removed\ndata: ${json.encodeToString(TaskRemovedEvent(id))}\n\n")
    }
}

/** 离线任务：仓库没有 Flow，按固定间隔轮询，只在 phase/progress 变化时推送。 */
private suspend fun streamTasks(graph: ServiceGraph, out: SendChannel<String>) {
    val sent = mutableMapOf<String, String>()
    while (true) {
        if (graph.isAuthenticated) {
            graph.taskRepository.getTasks().getOrNull()?.tasks?.forEach { task ->
                val stamp = "${task.phase}/${task.progress}"
                if (sent[task.id] == stamp) return@forEach
                sent[task.id] = stamp
                val event = TaskProgressEvent(
                    id = task.id,
                    phase = task.phase,
                    progress = task.progress,
                    fileId = task.fileId,
                    message = task.message.orEmpty(),
                )
                out.send("event: task.progress\ndata: ${json.encodeToString(event)}\n\n")
            }
        }
        delay(TASK_POLL_SECONDS * 1000)
    }
}
