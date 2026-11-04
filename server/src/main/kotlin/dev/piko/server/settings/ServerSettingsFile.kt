package dev.piko.server.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 运行期可改的服务端设置。口令不进这里：它单独放在 0600 的 `access-token` 文件。 */
@Serializable
data class ServerSettings(
    val downloadDir: String? = null,
)

/**
 * `settings.json` 的唯一读写口：先写 `.tmp` 再原子改名，避免半截文件。
 * 解析失败按「没有设置」处理，启动时回落到默认下载目录。
 */
object ServerSettingsFile {
    private const val FILE_NAME = "settings.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun file(home: Path): Path = home.resolve(FILE_NAME)

    fun load(home: Path): ServerSettings? {
        val file = file(home)
        if (!Files.exists(file)) return null
        return runCatching {
            json.decodeFromString<ServerSettings>(Files.readString(file))
        }.onFailure {
            System.err.println("[W] Server: settings.json 解析失败，按默认值启动：${it.message}")
        }.getOrNull()
    }

    fun save(home: Path, settings: ServerSettings) {
        Files.createDirectories(home)
        val target = file(home)
        val temp = target.resolveSibling("$FILE_NAME.tmp")
        Files.writeString(
            temp,
            json.encodeToString(ServerSettings.serializer(), settings) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--")) }
        runCatching { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .onFailure { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
    }
}
