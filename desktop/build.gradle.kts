import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// 版本号由 CI 通过 -PversionName / -PversionCode 注入，本地构建用默认值。
val appVersionName: String = (findProperty("versionName") as String?) ?: "1.0.0"

/**
 * jpackage 的版本号规则比 semver 严：DMG 要求 MAJOR > 0，MSI 要求三段纯数字。
 * 这里做一次归一化（0.x.y -> 1.x.y，去掉 -beta 之类的后缀）。
 */
val packagingVersion: String = appVersionName.substringBefore('-').split('.').let { p ->
    val major = (p.getOrNull(0)?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val minor = (p.getOrNull(1)?.toIntOrNull() ?: 0).coerceAtLeast(0)
    val patch = (p.getOrNull(2)?.toIntOrNull() ?: 0).coerceAtLeast(0)
    "$major.$minor.$patch"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.zzy.llmreader.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "LLMReader"
            packageVersion = packagingVersion

            // ⚠️ 以下字符串务必保持纯 ASCII。
            // jpackage 在 Windows 上用**平台默认字符集**（不是 UTF-8）读写中间文件，
            // 一旦 description / copyright 里出现中文或 © 这类非 ASCII 字符，
            // 它就会抛 MalformedInputException（报错信息只有一行 "Input length = 1"）
            // 并让 :desktop:packageMsi 直接失败 —— macOS/Linux 默认 UTF-8 所以看不出来。
            description = "LLM Reader - local & online novel reader with LLM translation"
            vendor = "zzy"
            copyright = "(c) zzy"
        }
    }
}
