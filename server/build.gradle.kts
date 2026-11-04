// 无头服务端：只依赖 :shared 的业务层，不碰 :ui / :desktopApp，因此不会把 Compose、skiko、
// mediamp/mpv 与 AWT 桌面特性拖进来。跑在 fnOS 上时由 fpk 的 cmd/main 拉起。
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    // 与 :shared、:cli 的字节码版本一致
    jvmToolchain(21)
}

application {
    applicationName = "piko-server"
    mainClass = "dev.piko.server.MainKt"
    // 无 X11 的 NAS 上不能有任何 AWT 窗口；headless 顺带让随手调用的桌面 API 直接报错而不是挂起
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    // M1 只用原始 text/event-stream 发心跳；插件留给 M2 的类型化事件
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)
    // SDK 的 PikPakClient 用 Ktor HttpClient 调 PikPak API，需要 JVM 客户端引擎
    implementation(libs.ktor.client.okhttp)
    // :shared 的 PikoLocale 等用到 Compose runtime；exclude 掉空壳 runtime-desktop 后需显式补回 androidx 的实现
    implementation("androidx.compose.runtime:runtime-desktop:1.12.0")
}

// 同 :cli：:shared 的 api 依赖带进 org.jetbrains.compose.runtime:runtime-desktop，
// 那是个只有 manifest 的空壳，与 androidx 的同名 jar 撞名，installDist 往 lib/ 拷贝时会失败
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-desktop")
}
