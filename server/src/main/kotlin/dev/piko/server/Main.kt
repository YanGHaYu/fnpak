package dev.piko.server

import dev.piko.server.access.AccessControl
import dev.piko.server.access.accessGuard
import dev.piko.server.access.accessRoutes
import dev.piko.server.auth.authRoutes
import dev.piko.server.download.downloadRoutes
import dev.piko.server.drive.driveRoutes
import dev.piko.server.events.sseRoutes
import dev.piko.server.links.linkRoutes
import dev.piko.server.media.mediaRoutes
import dev.piko.server.tasks.taskRoutes
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
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

private const val TAG = "Server"

/**
 * 入口：解析参数 → 固定数据目录 → 装日志 → 口令就位 → 起 Ktor(CIO)。
 *
 * `-Dpiko.home` 必须在任何 `PikoHome` / 会话存储被访问之前设好：`DesktopSessionStore` 的根目录
 * 与日志目录都由它决定，晚了就会在 `~/.piko` 下建出第二份登录态。
 *
 * 口令校验在本进程内完成：没给口令的非回环监听在 [ServerConfig.from] 就被拒绝（退出码 2），
 * 回环监听未给口令则现场生成一份并写进数据目录，只在该次启动打印一次。
 */
fun main(args: Array<String>) {
    val config = try {
        ServerConfig.from(args)
    } catch (error: IllegalArgumentException) {
        // 日志系统还没装，直接写 stderr；退出码 2 便于脚本判定「配置错误」与「运行时失败」
        System.err.println("[E] $TAG: ${error.message}")
        exitProcess(2)
    }
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

    val access = AccessControl.resolve(config)
    access.generatedPasscode?.let { passcode ->
        // 只在这一行出现口令：它是本次随机生成的值，不打印用户就永远进不去
        PikoLog.w(TAG, "未配置访问口令，已随机生成并保存到 ${config.home.resolve("access-token")}：$passcode")
        println("访问口令（本次生成，已保存到 ${config.home.resolve("access-token")}）：$passcode")
    }
    PikoLog.i(TAG, "访问控制：已启用；口令来源=${if (config.accessToken != null) "启动参数或环境变量" else "数据目录 access-token 文件"}")

    Files.createDirectories(config.downloadDir)
    PikoLog.i(TAG, "下载目录：${config.downloadDir}")
    PikoLog.w(TAG, "这道口令只挡同网段随手打开页面的人；对外暴露请交给 fnOS 反向代理 + 强口令")

    val graph = ServiceGraph(config)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { graph.close() } })

    println(
        "Piko server listening on http://${config.host}:${config.port}" +
            " (home=${config.home}, download=${config.downloadDir})",
    )
    embeddedServer(CIO, port = config.port, host = config.host) {
        installServer(graph, access.control)
    }.start(wait = true)
}

fun Application.installServer(graph: ServiceGraph, access: AccessControl) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    // 所有 /api 前缀的请求在路由之前统一校验会话，静态页与资源不受影响
    install(accessGuard(access))

    routing {
        // 存活探针不放账号数据，留在白名单里给监控与页面健康指示灯用
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
        accessRoutes(access)
        authRoutes(graph)
        driveRoutes(graph)
        linkRoutes(graph)
        taskRoutes(graph)
        downloadRoutes(graph)
        mediaRoutes()
        sseRoutes(graph)
        staticRoutes()
    }
}
