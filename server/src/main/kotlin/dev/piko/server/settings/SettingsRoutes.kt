package dev.piko.server.settings

import dev.piko.server.AccessTokenSettings
import dev.piko.server.ChangeAccessTokenRequest
import dev.piko.server.DownloadDirRequest
import dev.piko.server.DownloadDirResponse
import dev.piko.server.ServerConfig
import dev.piko.server.ServiceGraph
import dev.piko.server.ShareInfo
import dev.piko.server.SharesResponse
import dev.piko.server.SettingsResponse
import dev.piko.server.access.AccessControl
import dev.piko.server.respondError
import dev.piko.server.share.ShareDiscovery
import dev.piko.server.share.ShareEntry
import dev.piko.shared.log.PikoLog
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.SecureRandom

private const val TAG = "Settings"

/**
 * 设置页的四个接口，全部在访问门之后（[dev.piko.server.access.accessGuard] 已统一拦 /api）。
 *
 * 两条边界写死在这里，避免前端绕过：
 * 1. 口令要么由启动参数托管（页面 409），要么就是数据目录里那份文件，页面改完立刻原子写回。
 * 2. 下载目录只能落在「已授权目录」下面（`config/resource` 声明 + 应用设置里授权过的）：
 *    禁止绝对路径、禁止 `..`、禁止符号链接逃逸。
 */
fun Route.settingsRoutes(config: ServerConfig, access: AccessControl, graph: ServiceGraph) {
    get("/api/settings") {
        call.respond(
            SettingsResponse(
                version = ServerConfig.VERSION,
                home = config.home.toString(),
                accessToken = AccessTokenSettings(
                    fingerprint = access.fingerprint,
                    managedExternally = access.managedExternally,
                    source = if (access.managedExternally) {
                        "启动参数或环境变量（页面不可改）"
                    } else {
                        "数据目录 access-token 文件（可在页面修改）"
                    },
                    filePath = access.tokenFilePath,
                ),
                downloadDir = config.activeDownloadDir.toString(),
            ),
        )
    }

    get("/api/settings/shares") {
        val snapshot = ShareDiscovery.snapshot(config)
        call.respond(
            SharesResponse(
                shares = snapshot.entries.map { describeShare(it) },
                live = snapshot.liveQuery,
                note = snapshot.note,
            ),
        )
    }

    post("/api/settings/access-token") {
        if (access.managedExternally) {
            call.respondError(
                HttpStatusCode.Conflict,
                "token_managed_externally",
                "口令由启动参数或环境变量管理（${access.tokenFilePath} 不会被读取），页面不可改；" +
                    "请去掉 --access-token / PIKO_SERVER_TOKEN 后重启，再在页面里设置",
            )
            return@post
        }
        val body = runCatching { call.receive<ChangeAccessTokenRequest>() }.getOrNull()
        AccessControl.validatePasscode(body?.newPasscode.orEmpty())?.let { problem ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_passcode", problem)
            return@post
        }
        val keepSession = call.request.cookies[AccessControl.COOKIE_NAME]
        val fingerprint = try {
            access.changePasscode(body!!.newPasscode, keepSession)
        } catch (error: Exception) {
            call.respondError(
                HttpStatusCode.InternalServerError,
                "token_write_failed",
                "写入口令文件失败：${error::class.simpleName}: ${error.message}",
            )
            return@post
        }
        PikoLog.w(
            TAG,
            "口令已更新：来源=${call.remoteIp()}，新指纹=${fingerprint}，其余会话已失效",
        )
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/settings/download-dir") {
        val body = runCatching { call.receive<DownloadDirRequest>() }.getOrNull()
            ?: run {
                call.respondError(HttpStatusCode.BadRequest, "invalid_body", "请求体需要 shareRoot 与 subPath")
                return@post
            }
        val snapshot = ShareDiscovery.snapshot(config)
        if (snapshot.entries.isEmpty()) {
            call.respondError(
                HttpStatusCode.Conflict,
                "no_share_declared",
                "没有可用的授权目录：请到 飞牛「应用设置 → 访问权限」里点「+ 添加」授权一个文件夹；" +
                    "若刚添加，重启应用后即可在此选择",
            )
            return@post
        }
        val root = snapshot.entries.map { it.path }.firstOrNull { it.toString() == normalize(body.shareRoot) }
            ?: run {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "unknown_share",
                    "shareRoot 必须是当前已授权的目录之一：" + snapshot.entries.joinToString("、") { it.path.toString() },
                )
                return@post
            }
        val target = resolveUnderShare(root, body.subPath).getOrElse { error ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_path", error.message ?: "路径不合法")
            return@post
        }
        probeWrite(target)?.let { reason ->
            call.respondError(HttpStatusCode.BadRequest, "not_writable", "目标目录不可写：$reason")
            return@post
        }
        if (body.dryRun) {
            PikoLog.i(TAG, "下载目录测试：$target 可写（未保存）")
            call.respond(DownloadDirResponse(downloadDir = target.toString()))
            return@post
        }
        runCatching { ServerSettingsFile.save(config.home, ServerSettings(downloadDir = target.toString())) }
            .onFailure { error ->
                call.respondError(
                    HttpStatusCode.InternalServerError,
                    "settings_write_failed",
                    "写入 settings.json 失败：${error::class.simpleName}: ${error.message}",
                )
                return@post
            }
        graph.applyDownloadDir(target)
        PikoLog.w(
            TAG,
            "下载目录已更新：来源=${call.remoteIp()}，目录=$target（进行中的任务继续用各自旧目录）",
        )
        call.respond(DownloadDirResponse(downloadDir = target.toString()))
    }
}

private fun ApplicationCall.remoteIp(): String = runCatching { request.origin.remoteAddress }.getOrDefault("unknown")

private fun normalize(raw: String): String =
    runCatching { Path.of(raw.trim()).toAbsolutePath().normalize().toString() }.getOrDefault(raw.trim())

private fun describeShare(entry: ShareEntry): ShareInfo {
    val reason = probeWrite(entry.path)
    return ShareInfo(
        path = entry.path.toString(),
        writable = reason == null,
        source = entry.source,
        reason = reason,
    )
}

/** 就地在共享根下建一个随机小文件再删掉，判断真实可写（比 Files.isWritable 可靠）。 */
private fun probeWrite(directory: Path): String? {
    val name = ".piko-write-test-${SecureRandom().nextInt(1_000_000)}"
    val probe = directory.resolve(name)
    return try {
        Files.writeString(probe, "ok")
        Files.deleteIfExists(probe)
        null
    } catch (error: IOException) {
        "${error::class.simpleName}: ${error.message ?: directory.toString()}"
    } catch (error: SecurityException) {
        "SecurityException: ${error.message ?: directory.toString()}"
    }
}

/**
 * 把用户给的相对路径解析到共享根之下：
 * 绝对路径、`..`、已存在的符号链接、以及 realpath 后的越界，全部拒绝并给出中文原因。
 */
private fun resolveUnderShare(root: Path, subPath: String): Result<Path> = runCatching {
    val raw = subPath.trim().replace('\\', '/')
    if (raw.startsWith("/")) throw IllegalArgumentException("不允许绝对路径：$raw")
    if (raw.contains('\u0000')) throw IllegalArgumentException("路径里含非法字符")
    val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) throw IllegalArgumentException("不允许包含 ..：$raw")

    val realRoot = root.toRealPath()
    var current = realRoot
    for (segment in segments) {
        val next = current.resolve(segment)
        if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(next)) throw IllegalArgumentException("路径里的 $segment 是符号链接，拒绝穿越")
            if (!Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("路径里的 $segment 不是目录")
        } else {
            Files.createDirectories(next)
        }
        current = next.toRealPath()
        if (!current.startsWith(realRoot)) throw IllegalArgumentException("路径越出共享根：$current")
    }
    current
}
