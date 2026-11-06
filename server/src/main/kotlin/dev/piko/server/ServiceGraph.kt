package dev.piko.server

import dev.piko.server.auth.CaptchaChallengeStore
import dev.piko.server.auth.LoginAttemptLimiter
import dev.piko.server.auth.PikPakCaptchaClient
import dev.piko.server.download.ConnectionBudget
import dev.piko.server.download.DownloadGroupRegistry
import dev.piko.server.download.DownloadTuning
import dev.piko.server.download.MixedScanRegistry
import dev.piko.server.download.SegmentDownloader
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

    val instantRepository: InstantMagnetRepository = InstantMagnetRepository(clientManager)

    /** 官方在线解压：列目录、提交任务、查询进度，密码只走请求体。 */
    val archiveRepository = dev.piko.shared.data.ArchiveRepository(clientManager)

    val archiveJobs = dev.piko.server.archive.ArchiveJobRegistry(archiveRepository, scope)

    val taskRepository: TaskRepository = TaskRepository(clientManager, driveRepository, instantRepository)

    /** 离线任务名字解析：详情按 fileId 缓存，并发上限 4。 */
    val taskNameResolver = dev.piko.server.tasks.TaskNameResolver(driveRepository)

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

    /** 并发参数：分段数可运行期改；连接预算是全局的，改上限时排队者立即按新上限放行。 */
    val downloadTuning = DownloadTuning(
        segments = settings.segmentCount,
        maxConnections = settings.maxConnections,
        dualSignedUrls = settings.dualSignedUrls,
        downloadDiagnostics = settings.downloadDiagnostics,
    )

    val connectionBudget = ConnectionBudget(settings.maxConnections)

    /** 取一次文件详情并挑出可用直链；同一文件再取一次会拿到另一份签名（同签名时第二 lane 自动放弃）。 */
    private val directUrlProvider: suspend (String) -> String = { fileId ->
        driveRepository.getFileDetail(fileId).getOrThrow().let { detail ->
            detail.downloadUrl?.takeIf { it.isNotBlank() }
                ?: detail.webContentLink?.takeIf { it.isNotBlank() }
                ?: error("PikPak 没有返回可用的下载直链")
        }
    }

    /**
     * 分段直链下载：原画整文件任务全走它，绕开 SDK 的稀疏暂存。直链只在任务开始时取一次，
     * 段内 401/403 时由它自己按 `fileId` 重新取。
     */
    private val directDownloader = SegmentDownloader(
        urlProvider = directUrlProvider,
        secondUrlProvider = directUrlProvider,
        budget = connectionBudget,
        tuning = downloadTuning,
    )

    val downloadStorage = ServerDownloadStorage(
        cacheDirectory = config.home.resolve("download-cache").toFile(),
        directoryProvider = { config.activeDownloadDir.toFile() },
    )

    val downloadCoordinator = PikoDownloadCoordinator(
        clientProvider = clientManager,
        preferences = preferences,
        storage = downloadStorage,
        scope = scope,
        cacheStore = cacheStore,
        directDownloader = directDownloader,
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
        runCatching { directDownloader.close() }
        scope.cancel()
    }
}
