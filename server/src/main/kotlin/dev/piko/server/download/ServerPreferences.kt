package dev.piko.server.download

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.data.auth.SidePanelPrefs
import dev.piko.data.auth.SnailMode
import dev.piko.shared.net.ProxySetting
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * `PikoUserPreferences` 的服务端实现：无头环境没有 DataStore / DataStore Preferences，
 * 需要落盘的只有下载任务表（断点续传靠它），其余设置给进程内默认值即可。
 *
 * 桌面端的实现类在 `:desktopApp`，带 Compose，不能依赖；这里只实现接口，不参与任何下载调度。
 */
class ServerPreferences(
    private val root: Path,
    private val downloadDir: () -> Path,
) : PikoUserPreferences {
    private fun <T> cell(initial: T) = MutableStateFlow(initial)

    // ---- 播放与目录的记忆 ----
    private val playbackPositions = ConcurrentHashMap<String, Long>()
    private val lastFolderSaved = cell<Triple<String, String, String>>(Triple("", "", ""))

    override suspend fun savePlaybackPosition(fileId: String, positionMs: Long) {
        playbackPositions[fileId] = positionMs
    }

    override suspend fun getPlaybackPosition(fileId: String): Long = playbackPositions[fileId] ?: 0L

    override suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String) {
        lastFolderSaved.value = Triple(folderId, folderName, stackSerialized)
    }

    override suspend fun getLastFolder(): Triple<String, String, String> = lastFolderSaved.value

    // ---- 各种开关 ----
    private val spoilerBlur = cell(false)
    override val spoilerBlurFlow: Flow<Boolean> get() = spoilerBlur
    override suspend fun setSpoilerBlurEnabled(enabled: Boolean) { spoilerBlur.value = enabled }

    private val heuristicFilter = cell(true)
    override val heuristicFilterFlow: Flow<Boolean> get() = heuristicFilter
    override suspend fun setHeuristicFilterEnabled(enabled: Boolean) { heuristicFilter.value = enabled }

    private val nameParsing = cell(true)
    override val nameParsingFlow: Flow<Boolean> get() = nameParsing
    override suspend fun setNameParsingEnabled(enabled: Boolean) { nameParsing.value = enabled }

    private val bundleSubtitles = cell(false)
    override val bundleSubtitlesFlow: Flow<Boolean> get() = bundleSubtitles
    override suspend fun setBundleSubtitlesEnabled(enabled: Boolean) { bundleSubtitles.value = enabled }

    private val autoCleanNames = cell(false)
    override val autoCleanNamesFlow: Flow<Boolean> get() = autoCleanNames
    override suspend fun setAutoCleanNamesEnabled(enabled: Boolean) { autoCleanNames.value = enabled }

    private val settingsSync = cell(false)
    override val settingsSyncFlow: Flow<Boolean> get() = settingsSync
    override suspend fun setSettingsSyncEnabled(enabled: Boolean) { settingsSync.value = enabled }

    private val syncPlayHistory = cell(false)
    override val syncPlayHistoryFlow: Flow<Boolean> get() = syncPlayHistory
    override suspend fun setSyncPlayHistoryEnabled(enabled: Boolean) { syncPlayHistory.value = enabled }

    private val themeMode = cell<String?>(null)
    override val themeModeFlow: Flow<String?> get() = themeMode
    override suspend fun setThemeMode(mode: String) { themeMode.value = mode }

    private val themeSeed = cell<String?>(null)
    override val themeSeedFlow: Flow<String?> get() = themeSeed
    override suspend fun setThemeSeed(seed: String?) { themeSeed.value = seed }

    private val driveViewMode = cell("LIST")
    override val driveViewModeFlow: Flow<String> get() = driveViewMode
    override suspend fun setDriveViewMode(mode: String) { driveViewMode.value = mode }

    private val tileSizes = ConcurrentHashMap<String, MutableStateFlow<String>>()
    private fun tileSizeCell(view: String) = tileSizes.getOrPut(view) { MutableStateFlow("") }
    override fun driveTileSizeFlow(view: String): Flow<String> = tileSizeCell(view)

    override suspend fun setDriveTileSize(view: String, size: String) { tileSizeCell(view).value = size }

    private val sidebarCollapsed = cell(false)
    override val sidebarCollapsedFlow: Flow<Boolean> get() = sidebarCollapsed
    override suspend fun setSidebarCollapsed(collapsed: Boolean) { sidebarCollapsed.value = collapsed }

    private val showExtensions = cell(true)
    override val showExtensionsFlow: Flow<Boolean> get() = showExtensions
    override suspend fun setShowExtensions(show: Boolean) { showExtensions.value = show }

    private val clipPanel = cell(SidePanelPrefs(open = false, widthDp = null))
    override val clipPanelFlow: Flow<SidePanelPrefs> get() = clipPanel
    override suspend fun setClipPanelOpen(open: Boolean) { clipPanel.value = clipPanel.value.copy(open = open) }
    override suspend fun setClipPanelWidth(widthDp: Float) { clipPanel.value = clipPanel.value.copy(widthDp = widthDp) }

    private val pikpakDomain = cell("")
    override val pikpakDomainFlow: Flow<String> get() = pikpakDomain
    override suspend fun setPikpakDomain(root: String) { pikpakDomain.value = root }

    private val snailMode = cell(SnailMode())
    override val snailModeFlow: Flow<SnailMode> get() = snailMode
    override suspend fun setSnailMode(mode: SnailMode) { snailMode.value = mode }

    private val concurrentAcceleration = cell(false)
    override val concurrentAccelerationFlow: Flow<Boolean> get() = concurrentAcceleration
    override suspend fun setConcurrentAccelerationEnabled(enabled: Boolean) { concurrentAcceleration.value = enabled }

    /** 无头机器上一次下几个连接：默认 4，与桌面端默认一致。 */
    private val concurrentConnections = cell(4)
    override val concurrentConnectionsFlow: Flow<Int> get() = concurrentConnections

    private val downloadDirPath = cell(downloadDir().toString())
    override val downloadDirPathFlow: Flow<String> get() = downloadDirPath
    override suspend fun setDownloadDirPath(path: String) { downloadDirPath.value = path }
    override suspend fun getDownloadDirPath(): String = downloadDirPath.value

    // ---- 下载任务表：唯一需要落盘的 ----

    @Volatile
    private var tasksCache: String? = null
    private val tasksFile: Path get() = root.resolve("download-tasks.json")

    override suspend fun loadDownloadTasks(): String {
        tasksCache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { if (Files.exists(tasksFile)) Files.readString(tasksFile) else "" }.getOrDefault("")
        }
        tasksCache = loaded
        return loaded
    }

    override suspend fun saveDownloadTasks(serialized: String) {
        tasksCache = serialized
        withContext(Dispatchers.IO) {
            runCatching {
                Files.createDirectories(root)
                val temp = root.resolve("download-tasks.json.tmp")
                Files.writeString(temp, serialized)
                runCatching { Files.move(temp, tasksFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    .onFailure { Files.move(temp, tasksFile, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    private val uploadTasks = cell("")
    override suspend fun loadUploadTasks(): String = uploadTasks.value
    override suspend fun saveUploadTasks(serialized: String) { uploadTasks.value = serialized }

    private val uploadCredentials = ConcurrentHashMap<String, String>()
    override suspend fun loadUploadCredentials(taskId: String): String? = uploadCredentials[taskId]
    override suspend fun saveUploadCredentials(taskId: String, serialized: String) { uploadCredentials[taskId] = serialized }
    override suspend fun clearUploadCredentials(taskId: String) { uploadCredentials.remove(taskId) }

    private val offlinePacks = cell("")
    override suspend fun loadOfflinePacks(): String = offlinePacks.value
    override suspend fun saveOfflinePacks(serialized: String) { offlinePacks.value = serialized }

    override suspend fun loadLegacyArchivePasswords(): String = ""
    override suspend fun clearLegacyArchivePasswords() {}

    private val recentMoveTargets = cell("")
    override val recentMoveTargetsFlow: Flow<String> get() = recentMoveTargets
    override suspend fun saveRecentMoveTargets(serialized: String) { recentMoveTargets.value = serialized }

    private val pinnedFolders = cell("")
    override val pinnedFoldersFlow: Flow<String> get() = pinnedFolders
    override suspend fun savePinnedFolders(serialized: String) { pinnedFolders.value = serialized }

    private val batchRename = cell("")
    override val batchRenameFlow: Flow<String> get() = batchRename
    override suspend fun saveBatchRename(serialized: String) { batchRename.value = serialized }

    private val vaultArchiveOptions = cell("")
    override val vaultArchiveOptionsFlow: Flow<String> get() = vaultArchiveOptions
    override suspend fun saveVaultArchiveOptions(serialized: String) { vaultArchiveOptions.value = serialized }

    private val renameRegexTextMode = cell(false)
    override val renameRegexTextModeFlow: Flow<Boolean> get() = renameRegexTextMode
    override suspend fun setRenameRegexTextMode(enabled: Boolean) { renameRegexTextMode.value = enabled }

    private val proxySetting = cell(ProxySetting())
    override val proxySettingFlow: Flow<ProxySetting> get() = proxySetting
    override suspend fun saveProxySetting(setting: ProxySetting) { proxySetting.value = setting }

    private val autoCheckUpdates = cell(false)
    override val autoCheckUpdatesFlow: Flow<Boolean> get() = autoCheckUpdates
    override suspend fun setAutoCheckUpdates(enabled: Boolean) { autoCheckUpdates.value = enabled }

    private val autoDetectClipboardLinks = cell(false)
    override val autoDetectClipboardLinksFlow: Flow<Boolean> get() = autoDetectClipboardLinks
    override suspend fun setAutoDetectClipboardLinks(enabled: Boolean) { autoDetectClipboardLinks.value = enabled }

    private val reduceMotion = cell(false)
    override val reduceMotionFlow: Flow<Boolean> get() = reduceMotion
    override suspend fun setReduceMotion(enabled: Boolean) { reduceMotion.value = enabled }

    private val hardwareDecoding = cell(true)
    override val hardwareDecodingFlow: Flow<Boolean> get() = hardwareDecoding
    override suspend fun setHardwareDecoding(enabled: Boolean) { hardwareDecoding.value = enabled }

    private val playbackMaxHeight = cell(0)
    override val playbackMaxHeightFlow: Flow<Int> get() = playbackMaxHeight
    override suspend fun setPlaybackMaxHeight(height: Int) { playbackMaxHeight.value = height }

    private val downloadMaxHeight = cell<Int?>(null)
    override val downloadMaxHeightFlow: Flow<Int?> get() = downloadMaxHeight
    override suspend fun setDownloadMaxHeight(height: Int?) { downloadMaxHeight.value = height }

    private val playerSeekStep = cell(PlayerGestureDefaults.SEEK_STEP_SECONDS)
    override val playerSeekStepSecondsFlow: Flow<Int> get() = playerSeekStep
    override suspend fun setPlayerSeekStepSeconds(seconds: Int) { playerSeekStep.value = seconds }

    private val playerBoostSpeed = cell(PlayerGestureDefaults.BOOST_SPEED)
    override val playerBoostSpeedFlow: Flow<Float> get() = playerBoostSpeed
    override suspend fun setPlayerBoostSpeed(speed: Float) { playerBoostSpeed.value = speed }

    private val clipAutoExtend = cell(true)
    override val clipAutoExtendFlow: Flow<Boolean> get() = clipAutoExtend
    override suspend fun setClipAutoExtend(enabled: Boolean) { clipAutoExtend.value = enabled }

    private val folderMapOpen = cell(false)
    override val folderMapOpenFlow: Flow<Boolean> get() = folderMapOpen
    override suspend fun setFolderMapOpen(open: Boolean) { folderMapOpen.value = open }

    private val folderMapPinned = cell(false)
    override val folderMapPinnedFlow: Flow<Boolean> get() = folderMapPinned
    override suspend fun setFolderMapPinned(pinned: Boolean) { folderMapPinned.value = pinned }

    private var ignoredUpdateVersion: String? = null
    override suspend fun getIgnoredUpdateVersion(): String? = ignoredUpdateVersion
    override suspend fun setIgnoredUpdateVersion(version: String) { ignoredUpdateVersion = version }

    private val metaTubeUrl = cell("")
    override val metaTubeUrlFlow: Flow<String> get() = metaTubeUrl
    override suspend fun setMetaTubeUrl(url: String) { metaTubeUrl.value = url }

    private val metaTubeToken = cell("")
    override val metaTubeTokenFlow: Flow<String> get() = metaTubeToken
    override suspend fun setMetaTubeToken(token: String) { metaTubeToken.value = token }
}
