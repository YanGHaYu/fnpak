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

// 默认只构建服务端 / fpk（CI、WSL、没装桌面工具链的机器都能直接跑）：
// :desktopApp 在配置期就会解析 JDK 25 工具链，缺它会触发 toolchain 自动下载，而
// foojay-resolver-convention 0.9.0 在 Gradle 9.6 下走这条路径会抛
// "JvmVendorSpec does not have member field IBM_SEMERU"。因此默认只 include
// :shared 与 :server（:server 只依赖 :shared），构建链路只需 JDK 21。
//
// 需要桌面 / CLI / 截图等完整模块时显式传 -Pfnpak.full=true（需要 JDK 25）。
// 兼容旧属性 -Pfnpak.fpkOnly：显式传 false 等价于 full=true；两者冲突（full=true
// 同时 fpkOnly=true）时以 full=true 优先。源码目录仍保留在原位，只是默认不参与配置。
val fullBuild = providers.gradleProperty("fnpak.full").map(String::toBoolean).getOrElse(false)
val legacyFpkOnly = providers.gradleProperty("fnpak.fpkOnly").map(String::toBoolean)
val includeAll = fullBuild || legacyFpkOnly.getOrNull() == false
if (includeAll) {
    include(":app")
    include(":shared")
    include(":ui")
    include(":desktopApp")
    include(":cli")
    include(":server")
    include(":shots")
} else {
    include(":shared")
    include(":server")
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
