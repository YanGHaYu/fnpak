pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "piko"

// 只构建服务端 / fpk 时（CI、WSL 等没有桌面工具链的机器）排除需要 JDK 25 的模块：
// :desktopApp 在配置期就会解析 JDK 25 工具链，缺它会触发 toolchain 自动下载，而
// foojay-resolver-convention 0.9.0 在 Gradle 9.6 下走这条路径会抛
// "JvmVendorSpec does not have member field IBM_SEMERU"。传 -Pfnpak.fpkOnly=true 时
// 只 include :shared 与 :server（:server 只依赖 :shared），构建链路只需 JDK 21。
// 不传该属性时保持完整模块集合，本地开发与桌面打包行为不变。
val fpkOnly = providers.gradleProperty("fnpak.fpkOnly").map(String::toBoolean).getOrElse(false)
if (fpkOnly) {
    include(":shared")
    include(":server")
} else {
    include(":app")
    include(":shared")
    include(":ui")
    include(":desktopApp")
    include(":cli")
    include(":server")
    include(":shots")
}

// 与 SDK 联调：local.properties 里写 pikpak.sdk.dir=../pikpak-kotlin，依赖里的 pikpak-kotlin 就换成那个目录的
// 源码构建，改了 SDK 下次编译即生效。local.properties 不进仓库，CI 与别的机器照常取 libs.versions.toml 里的
// 正式版本，所以联调不必发版，也不必临时指向 mavenLocal 再记得改回来
val localProperties = java.util.Properties().apply {
    val file = file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}
localProperties.getProperty("pikpak.sdk.dir")?.let { sdkDir ->
    includeBuild(sdkDir) {
        dependencySubstitution {
            substitute(module("io.github.nihildigit:pikpak-kotlin")).using(project(":"))
        }
    }
}
// 同理联调触摸桥：windowstouch.dir=../compose-windows-touch
localProperties.getProperty("windowstouch.dir")?.let { dir ->
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module("dev.nihildigit:compose-windows-touch")).using(project(":"))
        }
    }
}
