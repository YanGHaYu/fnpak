package dev.piko.server

import java.nio.file.Path

/**
 * 服务端启动参数。优先级：命令行 > 环境变量 > 默认值。
 *
 * - 端口：`--port` / `--port=18080` > `PORT` > [DEFAULT_PORT]
 * - 监听地址：`--host` > [DEFAULT_HOST]。默认只监听回环：不带任何配置启动时局域网里打不开，
 *   要对外监听必须显式 `--host 0.0.0.0`，且必须同时提供访问口令。
 * - 访问口令：`--access-token` > `PIKO_SERVER_TOKEN`。未提供时，回环监听会生成一份随机口令
 *   （见 `AccessControl`），非回环监听直接拒绝启动。
 * - 数据根目录：系统属性 `piko.home`，由 fpk 的 cmd/main 注入 `$TRIM_PKGVAR`；未给时退回 `~/.piko`
 * - 下载目录：`TRIM_DATA_SHARE_PATHS`（冒号分隔取第一项）> `-Dpiko.downloadDir` > 系统临时目录下的 `piko-downloads`
 *
 * 只读这些参数，不解析配置文件：fpk 场景下端口由 manifest 的 `service_port` 决定，没有第二份配置的必要。
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val home: Path,
    val downloadDir: Path,
    /** 命令行或环境变量显式给出的访问口令；null 表示没给。不要写进日志。 */
    val accessToken: String?,
) {
    /** 回环地址才允许不带口令启动。 */
    val isLoopback: Boolean get() = isLoopbackHost(host)

    companion object {
        const val DEFAULT_PORT = 18080
        const val DEFAULT_HOST = "127.0.0.1"
        const val VERSION = "0.2.0-m2"

        fun from(args: Array<String>): ServerConfig {
            var host = DEFAULT_HOST
            var port = envPort() ?: DEFAULT_PORT
            var token = System.getenv("PIKO_SERVER_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
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
                    }
                    arg.startsWith("--access-token=") -> token =
                        arg.substringAfter('=').trim().takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("--access-token 需要一个非空口令")
                }
                index++
            }
            require(port in 1..65535) { "端口超出范围：$port" }

            val loopback = isLoopbackHost(host)
            if (!loopback && token == null) {
                // 暴露到局域网却没有口令：直接拒绝启动，而不是悄悄地无鉴权监听
                throw IllegalArgumentException(
                    "拒绝启动：--host $host 会把服务暴露到局域网，必须同时提供访问口令" +
                        "（--access-token 或环境变量 PIKO_SERVER_TOKEN）。" +
                        "只想本机使用就不要加 --host。",
                )
            }
            val home = System.getProperty("piko.home")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".piko")
            return ServerConfig(
                host = host,
                port = port,
                home = home.toAbsolutePath().normalize(),
                downloadDir = resolveDownloadDir(),
                accessToken = token,
            )
        }

        private fun envPort(): Int? = System.getenv("PORT")?.toIntOrNull()

        private fun isLoopbackHost(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]" ||
                host == "0:0:0:0:0:0:0:1"

        /** 冒号分隔取第一项；Windows 上单字母盘符（`C:`）不算有效路径，退回下一个来源。 */
        private fun resolveDownloadDir(): Path {
            val fromShares = System.getenv("TRIM_DATA_SHARE_PATHS")
                ?.split(':')
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() && (it.length > 1 || it != "/") }
                ?.takeIf { it.length > 2 }
            val fromProperty = System.getProperty("piko.downloadDir")?.takeIf { it.isNotBlank() }
            val chosen = fromShares ?: fromProperty
            val path = if (chosen != null) Path.of(chosen)
            else Path.of(System.getProperty("java.io.tmpdir"), "piko-downloads")
            return path.toAbsolutePath().normalize()
        }
    }
}
