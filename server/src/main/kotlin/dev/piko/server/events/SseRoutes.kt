package dev.piko.server.events

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.delay

private const val HEARTBEAT_SECONDS = 15L

/**
 * SSE 通道。M1 只发心跳：SSE 代理（nginx / fnOS 的网关）会在空闲一段时间后掐断连接，
 * 定期发注释行能保活，也让前端知道服务端还活着。
 *
 * M2 起在这里挂 `download.progress` / `task.progress` / `notice` 事件；用 `PikoDownloadCoordinator`
 * 与 `TaskRepository` 的 Flow 汇总后按 1~2 秒节流推送，不要每字节一条。
 *
 * 这里不用 `ktor-server-sse` 插件：M1 只有心跳，手写 `text/event-stream` 不依赖插件 API 版本差异；
 * 依赖已声明，M2 接类型化事件时直接换成 `sse(...)`。
 */
fun Route.sseRoutes() {
    get("/api/events") {
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            // 注释行（以冒号开头）是合法 SSE，EventSource 会忽略内容，仅用于保活
            write(": connected\n\n")
            flush()
            while (true) {
                delay(HEARTBEAT_SECONDS * 1000)
                write(": heartbeat ${System.currentTimeMillis()}\n\n")
                flush()
            }
        }
    }
}
