package dev.piko.server

import dev.piko.server.settings.ServerSettingsFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * 服务端启动参数。优先级：命令行 > 环境变量 > 设置文件 > 默认值。
 *
 * - 端口：`--port` / `--port=18080` > `PORT` > [DEFAULT_PORT]
 * - 监听地址：`--host` > [DEFAULT_HOST]
 * - 访问口令：`--access-token` > `FNPAK_SERVER_TOKEN`（兼容旧名 `PIKO_SERVER_TOKEN`）> 数据目录的 `access-token` 文件 > 无口令状态（首次设置向导）。
 *   前两者算「外部托管」（[accessTokenManagedExternally]），此时页面不允许改口令。
 * - 首次设置窗口：`--setup-window-minutes`（默认 15，0 = 不限），超时后只允许命令行重置。
 * - 数据根目录：系统属性 `fnpak.home`（兼容旧名 `piko.home`），由 fpk 的 cmd/main 注入 `$TRIM_PKGVAR`；未给时退回 `~/.fnpak`
 * - 下载目录：`<home>/settings.json` 里的 `downloadDir`（存在且目录还在）> `TRIM_DATA_SHARE_PATHS` 第一项 + `/downloads`
 *   > `-Dfnpak.downloadDir`（兼容旧名 `piko.downloadDir`）> 系统临时目录下的 `fnpak-downloads`
 * - 共享根：`TRIM_DATA_SHARE_PATHS`（冒号分隔），设置页的下载位置只能落在这些根下面
 *
 * 口令与下载目录都收敛成「文件里说了算」：进程启动时读文件，运行期改了就原子写回文件，
 * 不存在「脚本一份、进程一份」的双写者。
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val home: Path,
    val downloadDir: Path,
    /** 命令行或环境变量显式给出的访问口令；null 表示没给。不要写进日志。 */
    val accessToken: String?,
    /** 口令来自启动参数/环境变量时为真：页面改口令会被 409 拒绝。 */
    val accessTokenManagedExternally: Boolean,
    /** 首次设置口令窗口（分钟）；0 表示不限。默认 15，可用 `--setup-window-minutes` 调整。 */
    val setupWindowMinutes: Int,
    /** 允许写入的共享根：`config/resource` 声明的（TRIM_DATA_SHARE_PATHS）+ 管理员授权的（TRIM_DATA_ACCESSIBLE_PATHS）。 */
    val shares: List<Path>,
    /** 仅 `config/resource` 声明的共享根（安装时由系统创建）。 */
    val declaredShares: List<Path>,
    /** 仅「应用设置 → 访问权限」里由管理员授权的路径（启动时环境变量可见的部分）。 */
    val authorizedShares: List<Path>,
) {
    /** 回环地址标记，仅用于日志与提示。 */
    val isLoopback: Boolean get() = isLoopbackHost(host)

    @Volatile
    private var runtimeDownloadDir: Path = downloadDir

    /** 当前生效的下载目录。设置页切换后立即指向新目录；已在跑的任务仍用它们各自绑定的旧路径。 */
    val activeDownloadDir: Path get() = runtimeDownloadDir

    fun updateDownloadDir(path: Path) {
        runtimeDownloadDir = path
    }

    companion object {
        const val DEFAULT_PORT = 18080
        const val DEFAULT_HOST = "127.0.0.1"
        const val APP_NAME = "FNPak"
        const val VERSION = "0.9.0"

        fun from(args: Array<String>): ServerConfig {
            var host = DEFAULT_HOST
            var port = envPort() ?: DEFAULT_PORT
            var token = (System.getenv("FNPAK_SERVER_TOKEN") ?: System.getenv("PIKO_SERVER_TOKEN"))
                ?.trim()?.takeIf { it.isNotEmpty() }
            var external = token != null
            var setupWindowMinutes = 15
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                when {
                    arg == "--port" -> {
                        index++
                        port = args.getOrNull(index)?.toIntOrNull()
                            ?: throw IllegalArgumentException("--port 需要一个整数")
                    }
                    arg.startsWith("--port=") -> port =
                        arg.substringAfter('=').toIntOrNull() ?: throw IllegalArgumentException("--port 需要一个整数")
                    arg == "--host" -> {
                        index++
                        host = args.getOrNull(index) ?: throw IllegalArgumentException("--host 需要一个地址")
                    }
                    arg.startsWith("--host=") -> host = arg.substringAfter('=')
                    arg == "--access-token" -> {
                        index++
                        token = args.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("--access-token 需要一个非空口令")
                        external = true
                    }
                    arg.startsWith("--access-token=") -> {
                        token = arg.substringAfter('=').trim().takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("--access-token 需要一个非空口令")
                        external = true
                    }
                    arg == "--setup-window-minutes" -> {
                        index++
                        setupWindowMinutes = args.getOrNull(index)?.toIntOrNull()
                            ?: throw IllegalArgumentException("--setup-window-minutes 需要一个整数（分钟，0 = 不限）")
                    }
                    arg.startsWith("--setup-window-minutes=") -> setupWindowMinutes =
                        arg.substringAfter('=').toIntOrNull()
                            ?: throw IllegalArgumentException("--setup-window-minutes 需要一个整数（分钟，0 = 不限）")
                }
                index++
            }
            require(port in 1..65535) { "端口超出范围：$port" }
            require(setupWindowMinutes >= 0) { "--setup-window-minutes 不能为负数：$setupWindowMinutes" }

            // 不再因为「非回环 + 没给口令」而拒绝启动：口令缺失时进入首次设置向导（无口令状态），
            // 不会静默生成文件。这样 fpk 里不需要再有一个「外部注入口令」的脚本角色。
            val home = (System.getProperty("fnpak.home") ?: System.getProperty("piko.home"))
                ?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".fnpak")
            val normalizedHome = home.toAbsolutePath().normalize()
            val declared = envPaths("TRIM_DATA_SHARE_PATHS")
            val authorized = envPaths("TRIM_DATA_ACCESSIBLE_PATHS")
            val shares = (declared + authorized).distinct()
            return ServerConfig(
                host = host,
                port = port,
                home = normalizedHome,
                downloadDir = resolveDownloadDir(normalizedHome, shares),
                accessToken = token,
                accessTokenManagedExternally = external,
                setupWindowMinutes = setupWindowMinutes,
                shares = shares,
                declaredShares = declared,
                authorizedShares = authorized,
            )
        }

        private fun envPort(): Int? = System.getenv("PORT")?.toIntOrNull()

        private fun isLoopbackHost(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]" ||
                host == "0:0:0:0:0:0:0:1"

        /** 环境变量里的路径列表（冒号分隔）；只保留真实存在的目录。 */
        private fun envPaths(name: String): List<Path> =
            System.getenv(name)
                ?.split(':')
                ?.map { it.trim() }
                ?.filter { it.length > 1 }
                ?.map { Path.of(it).toAbsolutePath().normalize() }
                ?.filter { Files.isDirectory(it) }
                ?.distinct()
                .orEmpty()

        private fun resolveDownloadDir(home: Path, shares: List<Path>): Path {
            // 首选「第一个可写」的授权目录：第一个可能只是只读挂载，直接 mkdir 会让启动失败
            shares.firstOrNull()?.takeIf { !Files.isWritable(it) }?.let {
                System.err.println("[W] FNPak: 第一个授权目录不可写：$it，改用下一个可写的目录")
            }
            val writable = shares.firstOrNull { Files.isWritable(it) }
            if (writable == null && shares.isNotEmpty()) {
                System.err.println("[W] FNPak: ${shares.size} 个授权目录都不可写，下载目录回落到 $home/downloads")
            }
            if (shares.isEmpty()) {
                System.err.println("[W] FNPak: 没有共享根或授权目录（TRIM_DATA_SHARE_PATHS / TRIM_DATA_ACCESSIBLE_PATHS 均为空），下载目录回落到 $home/downloads")
            }
            val fallback = writable?.resolve("downloads")
                ?: (System.getProperty("fnpak.downloadDir") ?: System.getProperty("piko.downloadDir"))
                    ?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: home.resolve("downloads")
            val persisted = ServerSettingsFile.load(home)?.downloadDir
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it).toAbsolutePath().normalize() }
            if (persisted == null) return fallback.toAbsolutePath().normalize()
            if (Files.isDirectory(persisted)) return persisted
            System.err.println(
                "[W] FNPak: settings.json 里的下载目录已不存在：$persisted，回落到 ${fallback.toAbsolutePath().normalize()}",
            )
            return fallback.toAbsolutePath().normalize()
        }
    }
}
