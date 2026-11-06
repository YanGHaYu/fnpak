package dev.piko.server

import dev.piko.server.access.AccessControl
import dev.piko.server.access.AccessSetup
import dev.piko.server.access.accessGuard
import dev.piko.server.access.accessRoutes
import dev.piko.server.access.setupRoutes
import dev.piko.server.auth.authRoutes
import dev.piko.server.download.downloadRoutes
import dev.piko.server.drive.driveRoutes
import dev.piko.server.events.sseRoutes
import dev.piko.server.links.linkRoutes
import dev.piko.server.archive.archiveRoutes
import dev.piko.server.links.torrentRoutes
import dev.piko.server.media.mediaRoutes
import dev.piko.server.settings.settingsRoutes
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

private const val TAG = "FNPak"

/**
 * 入口：解析参数 → 固定数据目录 → 装日志 → 口令就位 → 起 Ktor(CIO)。
 *
 * `-Dfnpak.home` 必须在任何 `PikoHome` / 会话存储被访问之前设好：`DesktopSessionStore` 的根目录
 * 与日志目录都由它决定，晚了就会在 `~/.fnpak` 下建出第二份登录态。
 *
 * 口令校验在本进程内完成：没给口令时进入「首次设置向导」（无口令状态），由用户在页面设置后落盘；
 * 已有 token 文件或外部注入则直接启用，老用户不受影响。
 */
fun main(args: Array<String>) {
    val config = try {
        ServerConfig.from(args)
    } catch (error: IllegalArgumentException) {
        // 日志系统还没装，直接写 stderr；退出码 2 便于脚本判定「配置错误」与「运行时失败」
        System.err.println("[E] $TAG: ${error.message}")
        exitProcess(2)
    }
    System.setProperty("fnpak.home", config.home.toString())
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
    if (access.needsSetup) {
        PikoLog.w(
            TAG,
            "无口令状态（首次运行）：未找到 ${config.home.resolve("access-token")}，不会自动生成；" +
                "请在页面首次设置向导里设置口令（启动后 ${config.setupWindowMinutes} 分钟内有效，0 = 不限）",
        )
    } else {
        PikoLog.i(
            TAG,
            "访问控制：已启用；口令来源=${if (access.control.managedExternally) "启动参数或环境变量（页面不可改）" else "数据目录 access-token 文件"}；" +
                "指纹 sha256[:8]=${access.control.fingerprint}",
        )
    }

    runCatching { Files.createDirectories(config.activeDownloadDir) }
        .onFailure {
            System.err.println("[W] $TAG: 无法创建下载目录 ${config.activeDownloadDir}：${it.message}；服务继续启动，下载前请到设置页换一个可写目录")
        }
    PikoLog.i(TAG, "下载目录：${config.activeDownloadDir}；目录来源：内置共享根=${config.declaredShares.size} 个，应用设置授权=${config.authorizedShares.size} 个")
    if (config.shares.isEmpty()) {
        PikoLog.w(TAG, "没有内置共享根也没有授权目录，已回落到数据目录下的 downloads；请到 应用设置 → 访问权限 添加文件夹")
    }
    PikoLog.w(TAG, "这道口令只挡同网段随手打开页面的人；对外暴露请交给 fnOS 反向代理 + 强口令")

    val graph = ServiceGraph(config)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { graph.close() } })

    println(
        "${ServerConfig.APP_NAME} server listening on http://${config.host}:${config.port}" +
            " (home=${config.home}, download=${config.activeDownloadDir})",
    )
    embeddedServer(CIO, port = config.port, host = config.host) {
        installServer(graph, access)
    }.start(wait = true)
}

fun Application.installServer(graph: ServiceGraph, setup: AccessSetup) {
    val access = setup.control
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    // 所有 /api 前缀的请求在路由之前统一校验会话，静态页与资源不受影响
    install(accessGuard(access))

    routing {
        // 存活探针不放账号数据，留在白名单里给监控与页面健康指示灯用
        get("/api/health") {
            call.respond(
                HealthResponse(
                    ok = true,
                    app = ServerConfig.APP_NAME,
                    version = ServerConfig.VERSION,
                    home = graph.config.home.toString(),
                    authenticated = graph.isAuthenticated,
                    initializing = graph.isInitializing,
                    uptimeSeconds = graph.uptimeSeconds,
                    downloadDir = graph.config.activeDownloadDir.toString(),
                ),
            )
        }
        accessRoutes(access)
        setupRoutes(access, setup)
        settingsRoutes(graph.config, access, graph)
        authRoutes(graph)
        driveRoutes(graph)
        linkRoutes(graph)
        taskRoutes(graph)
        downloadRoutes(graph)
        mediaRoutes(graph)
        archiveRoutes(graph)
        torrentRoutes(graph)
        sseRoutes(graph)
        staticRoutes()
    }
}
