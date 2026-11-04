# 本机构建环境（Windows / x64）

> 这份文档记录为了在 Windows 上跑通 `:server` 而额外装到本机的东西，以及**怎么卸干净**。
> 仓库自身的构建约定（版本目录、模块划分）不在本文范围。

生成时间：2026-11-04

---

## 1. 结论：装了什么

| 组件 | 版本 | 路径 | 体积 | 用途 |
|---|---|---|---|---|
| Eclipse Temurin JDK 25 | 25.0.5+8-LTS | `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot` | 290 MB | `:desktopApp`、`:shots` 声明 `jvmToolchain(25)`，配置期 `desktopJavaLauncher.get()` 需要它 |
| Android SDK Platform | android-37.1 | `C:\Users\PC\AppData\Local\Android\Sdk\platforms\android-37.1` | 约 120 MB | `:app`、`:shared`、`:ui` 的 `compileSdk = release(37) { minorApiLevel = 1 }` |
| Android SDK Build-Tools | 37.0.0 | `...\Sdk\build-tools\37.0.0` | 约 139 MB | AGP 9.3.1 编译/打包 |
| Android SDK Platform-Tools | 37.0.1 | `...\Sdk\platform-tools` | 约 17 MB | adb 等通用工具 |
| Android SDK Command-line Tools | 23.0.0（zip 16111833） | `...\Sdk\cmdline-tools\latest` | — | 安装上面几个包的入口 |
| **Android SDK 合计** | | `C:\Users\PC\AppData\Local\Android\Sdk` | **447 MB** | |

**没有改动**：JDK 17（`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot`，仍是默认 `JAVA_HOME`）、仓库里的 `gradle.properties`、`E:\piko` 上游只读目录。

另外还依赖一个**之前就已存在**的便携 JDK 21：`C:\Users\PC\Documents\Trae-CN\temp_20261104_01\jdk-21.0.13+9`，`:server`/`:cli` 的 `jvmToolchain(21)` 与运行时（`pikpak-kotlin 2.5.0` 是 Java 21 字节码）用它。它在临时工作区里，删目录即可，不是系统安装。

---

## 2. 安装前的现状（回滚基准）

```
java -version      -> openjdk version "17.0.20" 2026-07-21 (Temurin-17.0.20+8)
JAVA_HOME          -> C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot\
ANDROID_HOME       -> (未设置)
ANDROID_SDK_ROOT   -> (未设置)
C:\Users\PC\AppData\Local\Android\Sdk -> 不存在
已装 JDK           -> 仅 jdk-17.0.20.8-hotspot
Gradle 包装器      -> 9.6.1
```

---

## 3. 安装命令原文

### 3.1 JDK 25

```powershell
winget install --id EclipseAdoptium.Temurin.25.JDK --silent `
  --accept-package-agreements --accept-source-agreements --disable-interactivity
```

`winget` 装的包版本 `25.0.5.8`，MSI 默认装到 `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`，并在注册表登记（Gradle 自动探测就是从注册表看到的它）。

### 3.2 Android SDK

命令行工具（官方 zip，147.8 MB）：

```powershell
$sdk = "C:\Users\PC\AppData\Local\Android\Sdk"
Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip" `
  -OutFile "$env:TEMP\cmdline-tools.zip"
New-Item -ItemType Directory -Force -Path "$sdk\cmdline-tools" | Out-Null
Expand-Archive "$env:TEMP\cmdline-tools.zip" -DestinationPath "$sdk\cmdline-tools"
Move-Item "$sdk\cmdline-tools\cmdline-tools" "$sdk\cmdline-tools\latest"
```

安装组件（新版 cmdline-tools 里 `sdkmanager` 已废弃，且旧 bat 传 `platforms;android-37.1` 会被拆成两个参数，改用随包的 `android.exe`）：

```powershell
& "$sdk\cmdline-tools\latest\bin\android.exe" sdk install "platforms;android-37.1"
& "$sdk\cmdline-tools\latest\bin\android.exe" sdk install "build-tools;37.0.0"
# platform-tools 由旧 sdkmanager 装成（同版本 37.0.1）
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses   # 新版提示已无需手动接受
```

> 仓库声明的是 **37.1（minorApiLevel = 1）** 而不是 android-37；`platforms;android-37.0` 与 `37.2` 都在仓库里，但 `compileSdk` 只认 37.1。

### 3.3 仓库内的本地配置（不进 git）

`E:\piko-fork\local.properties`（已被 `.gitignore` 的 `/local.properties` 忽略）：

```properties
sdk.dir=C\:\\Users\\PC\\AppData\\Local\\Android\\Sdk
```

没有设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`：AGP 只读 `local.properties` 的 `sdk.dir`，避免污染全局环境变量。

### 3.4 Gradle 的工具链登记（不进仓库）

`%USERPROFILE%\.gradle\gradle.properties`（**新建**，之前不存在）：

```properties
org.gradle.java.installations.paths=C\:\\Program Files\\Eclipse Adoptium\\jdk-25.0.5.8-hotspot,C\:\\Users\\PC\\Documents\\Trae-CN\\temp_20261104_01\\jdk-21.0.13+9,C\:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.8-hotspot
org.gradle.java.installations.auto-detect=true
```

为什么需要它：`settings.gradle.kts` 里的 `org.gradle.toolchains.foojay-resolver-convention` **0.9.0** 与 Gradle **9.6.1** 不兼容，任何一次需要下载工具链的解析都会抛
`NoSuchFieldError: IBM_SEMERU`（`DistributionsKt.<clinit>`）。本机把三个 JDK 显式登记后，解析在本地命中，不再走 foojay。**没有改仓库里的插件版本**。

触发点：`:desktopApp/build.gradle.kts:110` 的 `afterEvaluate { tasks.named<JavaExec>("run") { executable(desktopJavaLauncher.get()…) } }`，任何任务在解析任务图时都会实例化 `run`，因此即使只编 `:server` 也要能找到 JDK 25。

---

## 4. 编译命令

`E:\` 下 `.gradle` 写权限不可用，统一用 `--project-cache-dir` 指到临时目录：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
Set-Location "E:\piko-fork"
.\gradlew.bat :server:compileKotlin --project-cache-dir "C:\Users\PC\Documents\Trae-CN\temp_20261104_01\.piko-gradle-cache-fork"
.\gradlew.bat :server:installDist   --project-cache-dir "C:\Users\PC\Documents\Trae-CN\temp_20261104_01\.piko-gradle-cache-fork"
```

Gradle 守护进程跑在 JDK 17 上；真实编译用 `jvmToolchain` 指定的 21/25。

---

## 5. 卸载 / 回滚

按顺序执行即可回到安装前状态：

```powershell
# 1) JDK 25
winget uninstall --id EclipseAdoptium.Temurin.25.JDK

# 2) Android SDK（整目录删除，447 MB）
Remove-Item -Recurse -Force "C:\Users\PC\AppData\Local\Android\Sdk"

# 3) 仓库外的本地配置
Remove-Item "E:\piko-fork\local.properties"                 # 只是本机路径，删掉后仓库回到干净状态
Remove-Item "$env:USERPROFILE\.gradle\gradle.properties"     # 本文件是本次新建的；若日后有别的配置，只删那两行 org.gradle.java.installations.*

# 4) 临时工作区里的便携 JDK 21 与构建缓存（按需）
Remove-Item -Recurse -Force "C:\Users\PC\Documents\Trae-CN\temp_20261104_01\jdk-21.0.13+9"
Remove-Item -Recurse -Force "C:\Users\PC\Documents\Trae-CN\temp_20261104_01\.piko-gradle-cache-fork"
Remove-Item -Recurse -Force "E:\piko-fork\build", "E:\piko-fork\server\build"   # build 产物，被 .gitignore 忽略
```

卸载后 `java -version` 仍应是 17.0.20，`JAVA_HOME` 不应改变。

---

## 6. 与上游的偏离

`shared/src/commonMain/kotlin/dev/piko/shared/data/PikoClientManager.kt` 里三处 `concurrentAcceleration` 已注释掉：发布版 `pikpak-kotlin 2.5.0` 的 `PikPakClient` 没有这个构造参数/属性，只有 `local.properties` 指向 `../pikpak-kotlin` 源码做 `includeBuild` 时才有。

- 影响：多连接并发加速开关退化为"只记录状态"，实际并发仍由 `CONNECTION_BUDGET = 8` 决定；不改变鉴权、列目录、播放等主流程。
- 恢复：拿到带 `concurrentAcceleration` 的 SDK 版本后，把这三行注释还原即可。
