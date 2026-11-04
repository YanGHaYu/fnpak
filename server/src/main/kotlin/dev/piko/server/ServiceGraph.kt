package dev.piko.server

import dev.piko.server.auth.CaptchaChallengeStore
import dev.piko.server.auth.LoginAttemptLimiter
import dev.piko.server.auth.PikPakCaptchaClient
import dev.piko.server.download.ServerDownloadStorage
import dev.piko.server.download.ServerPreferences
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
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("piko-server"))

    val sessionStore: DesktopSessionStore = DesktopSessionStore(config.home)

    val clientManager: PikoClientManager = PikoClientManager(sessionStore, scope)

    /** 登录限流与验证码挑战：只在本进程内，重启后清零。 */
    val loginLimiter = LoginAttemptLimiter()

    val captchaChallenges = CaptchaChallengeStore()

    /** 直接向 PikPak 申请风控验证码令牌；昵称与密码都不经过它。 */
    val captchaClient = PikPakCaptchaClient(config.home)

    val driveRepository: PikoDriveRepository = PikoDriveRepository(clientManager)

    val instantRepository: InstantMagnetRepository = InstantMagnetRepository(clientManager)

    val taskRepository: TaskRepository = TaskRepository(clientManager, driveRepository, instantRepository)

    private val preferences = ServerPreferences(config.home) { config.downloadDir }

    private val cacheStore = FilePikoCacheStore(config.home.resolve("cache").toString())

    private val downloadStorage = ServerDownloadStorage(
        cacheDirectory = config.home.resolve("download-cache").toFile(),
        directoryProvider = { config.downloadDir.toFile() },
    )

    val downloadCoordinator = PikoDownloadCoordinator(
        clientProvider = clientManager,
        preferences = preferences,
        storage = downloadStorage,
        scope = scope,
        cacheStore = cacheStore,
    )

    val downloadDirectory: File get() = config.downloadDir.toFile()

    val isAuthenticated: Boolean get() = clientManager.currentClient.value != null

    val isInitializing: Boolean get() = clientManager.isInitializing.value

    val uptimeSeconds: Long get() = (System.currentTimeMillis() - startedAtMillis) / 1000

    override fun close() {
        PikoLog.i("Server", "服务端退出，暂停下载并保存任务表")
        // 先暂停再落盘：进程被停掉时任务表里留着「下载中」，重启后会当成仍在跑却没有协程
        downloadCoordinator.pauseAll()
        runBlocking { withTimeoutOrNull(3_000) { runCatching { downloadCoordinator.flushPersistence() } } }
        runCatching { clientManager.currentClient.value?.close() }
        runCatching { captchaClient.close() }
        scope.cancel()
    }
}
