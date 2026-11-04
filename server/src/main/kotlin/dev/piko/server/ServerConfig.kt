package dev.piko.server

import java.nio.file.Path

/**
 * 服务端启动参数。优先级：命令行 > 环境变量 > 默认值。
 *
 * - 端口：`--port` / `--port=18080` > `PORT` > [DEFAULT_PORT]
 * - 监听地址：`--host` / `--host=0.0.0.0` > [DEFAULT_HOST]（NAS 上必须能被局域网访问，不能只听回环）
 * - 数据根目录：系统属性 `piko.home`，由 fpk 的 cmd/main 注入 `$TRIM_PKGVAR`；未给时退回 `~/.piko`
 *
 * 只读这些参数，不解析配置文件：fpk 场景下端口由 manifest 的 `service_port` 决定，没有第二份配置的必要。
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val home: Path,
) {
    companion object {
        const val DEFAULT_PORT = 18080
        const val DEFAULT_HOST = "0.0.0.0"
        const val VERSION = "0.1.0-m1"

        fun from(args: Array<String>): ServerConfig {
            var host = DEFAULT_HOST
            var port = envPort() ?: DEFAULT_PORT
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
                }
                index++
            }
            require(port in 1..65535) { "端口超出范围：$port" }
            val home = System.getProperty("piko.home")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".piko")
            return ServerConfig(host = host, port = port, home = home.toAbsolutePath().normalize())
        }

        private fun envPort(): Int? = System.getenv("PORT")?.toIntOrNull()
    }
}
