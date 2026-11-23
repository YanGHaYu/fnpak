package dev.piko.server

import dev.piko.server.auth.CaptchaChallengeStore
import dev.piko.server.auth.LoginAttemptLimiter
import dev.piko.server.auth.PikPakCaptchaClient
import dev.piko.server.download.DownloadGroupRegistry
import dev.piko.server.download.LegacyDownloadMigration
import dev.piko.server.download.MixedScanRegistry
import dev.piko.server.download.ServerDownloadStorage
import dev.piko.server.download.ServerFolderSource
import dev.piko.server.download.ServerPreferences
import dev.piko.server.settings.ServerSettings
import dev.piko.server.settings.ServerSettingsFile
import dev.piko.shared.auth.DesktopSessionStore
import dev.piko.shared.data.FilePikoCacheStore
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.log.PikoLog
import java.io.File
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 服务端的依赖容器，替代 `:ui` 的 `PikoServices`。
 *
 * 只装配 `:shared` 里不依赖 Compose 的部分：
 * - [DesktopSessionStore]：Linux 上 `platformSecretVault()` 返回 null，`layeredSecretVault` 自动退化成
 *   0600 的明文文件，正好适配无头机器（没有钥匙串/Secret Service）。
 * - [PikoClientManager]：登录态、SDK client 生命周期、令牌刷新。
 * - [PikoDriveRepository]：目录列表、搜索、文件详情。preferences/cacheStore/treeDatabaseFactory 都不给。
 * - [InstantMagnetRepository] / [TaskRepository]：链接解析、离线任务与产出。
 * - [PikoDownloadCoordinator]：本机下载调度。它只依赖三个平台边界（preferences / storage / cacheStore），
 *   这里给的是服务端实现；片段抽取与画质挑档所需的 `segmentDownloader` / `mediaRepository` 留空，
 *   整文件下载不受影响。
 *
 * 播放器、剪贴板、Toast、托盘这些桌面对象一个都不构造。
 */
class ServiceGraph(val config: ServerConfig) : AutoCloseable {
    private val startedAtMillis = System.currentTimeMillis()

    /** 与进程同寿的协程域：登录恢复、重连、下载与各仓库的后台任务都挂在它下面。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("fnpak-server"))

    val sessionStore: DesktopSessionStore = DesktopSessionStore(config.home)

    val clientManager: PikoClientManager = PikoClientManager(sessionStore, scope)

    /** 登录限流与验证码挑战：只在本进程内，重启后清零。 */
    val loginLimiter = LoginAttemptLimiter()

    val captchaChallenges = CaptchaChallengeStore()

    /** 直接向 PikPak 申请风控验证码令牌；昵称与密码都不经过它。 */
    val captchaClient = PikPakCaptchaClient(config.home)

    val driveRepository: PikoDriveRepository = PikoDriveRepository(clientManager)

    /** M3：媒体元数据、直链探测与 Range 字节流转发。 */
    val mediaService = dev.piko.server.media.MediaService(driveRepository)

    /**
     * 目录缩略图代理 + 磁盘缓存（`$home/thumbnails`）。直链来自列表响应的 thumbnail_link，
     * 只在服务端使用；浏览器只访问同源 `/api/drive/files/{id}/thumbnail`。
     * 重启后内存直链表为空，用详情接口拿 parentId 再重列父目录兜底。
     */
    val thumbnailService = dev.piko.server.drive.ThumbnailService(
        listFolder = { parentId -> driveRepository.listFiles(parentId = parentId).getOrNull()?.first },
        fileDetail = { fileId -> driveRepository.getFileDetail(fileId).getOrNull() },
        directory = config.home.resolve("thumbnails"),
    )

    val instantRepository: InstantMagnetRepository = InstantMagnetRepository(clientManager)

    /** 官方在线解压：列目录、提交任务、查询进度，密码只走请求体。 */
    val archiveRepository = dev.piko.shared.data.ArchiveRepository(clientManager)

    val archiveJobs = dev.piko.server.archive.ArchiveJobRegistry(archiveRepository, scope)

    val taskRepository: TaskRepository = TaskRepository(clientManager, driveRepository, instantRepository)

    /** 离线任务名字解析：详情按 fileId 缓存，并发上限 4。 */
    val taskNameResolver = dev.piko.server.tasks.TaskNameResolver(driveRepository)

    /** 并发参数：单个任务 1–8 条连接；账号级 16 条总预算由 SDK 的 PikPakClient 统一管理。 */
    private val preferences = ServerPreferences(config.home) { config.activeDownloadDir }

    private val cacheStore = FilePikoCacheStore(config.home.resolve("cache").toString())

    /** `settings.json` 的运行期副本：下载目录与并发参数都从这里读，写完立即落盘。 */
    @Volatile
    private var settings: ServerSettings = ServerSettingsFile.load(config.home) ?: ServerSettings()

    fun currentSettings(): ServerSettings = settings

    /**
     * 改设置并原子写回。写失败时抛异常，由调用方回 500；内存值不会先于文件生效。
     */
    @Synchronized
    fun updateSettings(transform: (ServerSettings) -> ServerSettings): ServerSettings {
        val next = transform(settings)
        ServerSettingsFile.save(config.home, next)
        settings = next
        return next
    }

    /**
     * 旧版 `download-tasks.json` 里的分段进度迁移。必须在 [downloadCoordinator] 读任务表之前执行：
     * 旧版是乱序写盘的分段文件，不能当作顺序前缀导入 SDK 的稀疏缓存，否则会把空洞当数据。
     */
    private val legacyMigration = LegacyDownloadMigration.migrate(config.home)

    val downloadStorage = ServerDownloadStorage(
        cacheDirectory = config.home.resolve("download-cache").toFile(),
        directoryProvider = { config.activeDownloadDir.toFile() },
    )

    /**
     * 整文件下载不再注入自研分段器：直接走 SDK 的块缓存（`PikoFileCache` / `BlockCache` /
     * `RangeReader`），与播放、缩略图共用同一个 `PikPakClient` 和账号级 16 条连接预算。
     */
    val downloadCoordinator = PikoDownloadCoordinator(
        clientProvider = clientManager,
        preferences = preferences,
        storage = downloadStorage,
        scope = scope,
        cacheStore = cacheStore,
    )

    /** 文件夹递归来源：服务端不按今日额度拦截，扫完直接入队。 */
    val serverFolderSource = ServerFolderSource(driveRepository)

    /** 父任务「取消」标记（与暂停区分），落盘在数据目录，重启后仍显示 CANCELLED。 */
    val downloadGroupRegistry = DownloadGroupRegistry(config.home)
    val mixedScanRegistry = MixedScanRegistry()

    /** 离线任务被删除后推给 SSE，前端据此移除行，不必重拉整页。 */
    val taskRemovalEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)

    init {
        downloadCoordinator.bindDefaultFolderSource(serverFolderSource)
        // 旧版自适应并发/惩罚记忆的落盘文件已经失去意义：删掉，避免下次排查时误读
        val throttleFile = config.home.resolve("download-throttle.json")
        if (runCatching { java.nio.file.Files.deleteIfExists(throttleFile) }.getOrDefault(false)) {
            PikoLog.i("FNPak", "已清理旧版限流惩罚文件 download-throttle.json（自适应并发已移除）")
        }
        preferences.setConcurrentConnections(settings.concurrentConnections)
        PikoLog.i(
            "FNPak",
            "下载传输：SDK 块缓存（单文件 ${settings.concurrentConnections.coerceIn(1, 8)} 条，" +
                "与播放/缩略图共用账号 16 条预算；上限 8 条为上游行为）",
        )
        if (legacyMigration.total > 0) {
            PikoLog.w(
                "FNPak",
                "旧下载任务迁移：保留顺序进度 ${legacyMigration.kept} 个，" +
                    "重置旧分段任务 ${legacyMigration.reset} 个（将从头下载），" +
                    "已完成 ${legacyMigration.completed} 个不受影响",
            )
        }
    }

    /**
     * 设置页修改单文件并发数。只影响新任务；SDK 会把所有使用者的连接汇总到账号 16 条预算里。
     */
    fun applyConcurrentConnections(count: Int) {
        val clamped = count.coerceIn(1, 8)
        preferences.setConcurrentConnections(clamped)
        PikoLog.i(
            "FNPak",
            "下载并发切换为单文件 $clamped 条（与播放/缩略图共用账号 16 条预算，进行中的任务不变）",
        )
    }

    val downloadDirectory: File get() = config.activeDownloadDir.toFile()

    /**
     * 设置页切换下载位置：立刻生效于新任务。已在下载的任务在启动时就把目标文件路径绑死了，
     * 继续写旧目录，不做中途迁移（半途搬移更容易把一份文件写坏）。
     */
    fun applyDownloadDir(path: java.nio.file.Path) {
        config.updateDownloadDir(path)
        PikoLog.i("FNPak", "下载目录切换为 $path（进行中的任务继续用原目录）")
        runCatching { runBlocking { preferences.setDownloadDirPath(path.toString()) } }
    }

    val isAuthenticated: Boolean get() = clientManager.currentClient.value != null

    val isInitializing: Boolean get() = clientManager.isInitializing.value

    val uptimeSeconds: Long get() = (System.currentTimeMillis() - startedAtMillis) / 1000

    override fun close() {
        PikoLog.i("FNPak", "服务端退出，暂停下载并保存任务表")
        // 先暂停再落盘：进程被停掉时任务表里留着「下载中」，重启后会当成仍在跑却没有协程
        downloadCoordinator.pauseAll()
        runBlocking { withTimeoutOrNull(3_000) { runCatching { downloadCoordinator.flushPersistence() } } }
        runCatching { clientManager.currentClient.value?.close() }
        runCatching { captchaClient.close() }
        runCatching { mediaService.close() }
        runCatching { thumbnailService.close() }
        scope.cancel()
    }
}
