package dev.piko.server

import dev.piko.server.auth.authRoutes
import dev.piko.server.drive.driveRoutes
import dev.piko.server.events.sseRoutes
import dev.piko.server.media.mediaRoutes
import dev.piko.server.web.staticRoutes
import dev.piko.shared.log.PikoLog
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Files
import kotlinx.serialization.json.Json

private const val TAG = "Server"

/**
 * 入口：解析参数 → 固定数据目录 → 装日志 → 起 Ktor(CIO)。
 *
 * `-Dpiko.home` 必须在任何 `PikoHome` / 会话存储被访问之前设好：`DesktopSessionStore` 的根目录
 * 与日志目录都由它决定，晚了就会在 `~/.piko` 下建出第二份登录态。
 */
fun main(args: Array<String>) {
    val config = ServerConfig.from(args)
    System.setProperty("piko.home", config.home.toString())
    Files.createDirectories(config.home)
    val logDirectory = config.home.resolve("logs")
    Files.createDirectories(logDirectory)
    // 同时回显到 stderr：fpk 的 cmd/main 把标准输出重定向到 $TRIM_TEMP_LOGFILE，出问题时能直接看到
    PikoLog.install(logDirectory.toString()) { level, tag, message, error ->
        val line = "[${level.letter}] $tag: $message"
        if (error == null) System.err.println(line) else System.err.println("$line (${error::class.simpleName}: ${error.message})")
    }
    PikoLog.i(TAG, "启动：home=${config.home}，日志目录=$logDirectory")

    val graph = ServiceGraph(config)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { graph.close() } })

    println("Piko server listening on http://${config.host}:${config.port} (home=${config.home})")
    embeddedServer(CIO, port = config.port, host = config.host) { installServer(graph) }.start(wait = true)
}

fun Application.installServer(graph: ServiceGraph) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }

    routing {
        get("/api/health") {
            call.respond(
                HealthResponse(
                    ok = true,
                    version = ServerConfig.VERSION,
                    home = graph.config.home.toString(),
                    authenticated = graph.isAuthenticated,
                    initializing = graph.isInitializing,
                    uptimeSeconds = graph.uptimeSeconds,
                ),
            )
        }
        authRoutes(graph)
        driveRoutes(graph)
        mediaRoutes()
        sseRoutes()
        staticRoutes()
    }
}
