buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.11")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// 根目录的 run 只启动桌面应用，避免同时执行需要参数的 CLI 和截图工具。
// 默认（fpk-only）下 :desktopApp 不在构建内，不注册该任务（否则任务路径无法解析）；
// 只有 -Pfnpak.full=true（或兼容的 -Pfnpak.fpkOnly=false）才注册。
val fullBuild = providers.gradleProperty("fnpak.full").map(String::toBoolean).getOrElse(false)
val legacyFpkOnly = providers.gradleProperty("fnpak.fpkOnly").map(String::toBoolean)
if (fullBuild || legacyFpkOnly.getOrNull() == false) {
    tasks.register<DefaultTask>("run") {
        group = "application"
        description = "Run the Piko desktop application"
        impliesSubProjects = true
        dependsOn(":desktopApp:run")
    }
}
