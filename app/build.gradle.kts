import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release 签名写死在仓库根目录的 keystore.properties
 * （keystore 文件为 keystore/release.keystore），本地和 CI 都能直接
 * `./gradlew :app:assembleRelease` 出签名包，不需要任何额外配置。
 *
 * ⚠️ 前提是仓库保持**私有**，详见 keystore.properties 顶部说明。
 * 文件缺失时自动回退 debug 签名，保证别人 clone 下来也能构建。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStorePath: String? = keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.zzy.llmreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zzy.llmreader"
        minSdk = 24
        targetSdk = 34
        // 版本号可由 CI 注入：./gradlew :app:assembleRelease -PversionName=1.2.3 -PversionCode=10203
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 10000
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 读 keystore.properties；文件不存在时留空，release 回退到 debug 签名。
        create("release") {
            if (releaseStorePath != null) {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseStorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    // 全部核心逻辑（网页抓取 / LLM 翻译 / 生图 / 配置仓储 / 模型）都在 :shared 里，
    // 与桌面端共用同一份实现。
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.kotlinx.coroutines.android)
    // ReaderViewModel 里导入 SillyTavern st-chatu8 配置时直接用了 JsonObject
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
