package dev.piko.server

import dev.piko.shared.auth.DesktopSessionStore
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 服务端的依赖容器，替代 `:ui` 的 `PikoServices`。
 *
 * 只装配 `:shared` 里不依赖 Compose 的部分：
 * - [DesktopSessionStore]：Linux 上 `platformSecretVault()` 返回 null，`layeredSecretVault` 自动退化成
 *   0600 的明文文件，正好适配无头机器（没有钥匙串/Secret Service）。
 * - [PikoClientManager]：登录态、SDK client 生命周期、令牌刷新。
 * - [PikoDriveRepository]：目录列表、搜索、文件详情。preferences/cacheStore/treeDatabaseFactory 都不给，
 *   服务端 M1 不需要偏好、本地缓存与 SQLite 文件树（也就避开了 sqlite 的落盘与并发问题）。
 *
 * 播放器、剪贴板、Toast、托盘这些桌面对象一个都不构造。
 */
class ServiceGraph(val config: ServerConfig) : AutoCloseable {
    private val startedAtMillis = System.currentTimeMillis()

    /** 与进程同寿的协程域：登录恢复、重连、各仓库的后台任务都挂在它下面。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("piko-server"))

    val sessionStore: DesktopSessionStore = DesktopSessionStore(config.home)

    val clientManager: PikoClientManager = PikoClientManager(sessionStore, scope)

    val driveRepository: PikoDriveRepository = PikoDriveRepository(clientManager)

    val isAuthenticated: Boolean get() = clientManager.currentClient.value != null

    val isInitializing: Boolean get() = clientManager.isInitializing.value

    val uptimeSeconds: Long get() = (System.currentTimeMillis() - startedAtMillis) / 1000

    override fun close() {
        PikoLog.i("Server", "服务端退出，关闭登录态与后台任务")
        runCatching { clientManager.currentClient.value?.close() }
        scope.cancel()
    }
}
