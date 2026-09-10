plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

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
        // 只在提供了 -PRELEASE_STORE_FILE 时创建发布签名（CI 用 GitHub Secrets 注入）。
        // 本地构建没有这些属性，会走下面的 debug 签名兜底。
        create("release") {
            val storePath = findProperty("RELEASE_STORE_FILE") as String?
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = findProperty("RELEASE_KEY_PASSWORD") as String?
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
            signingConfig = if (!(findProperty("RELEASE_STORE_FILE") as String?).isNullOrBlank()) {
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
    implementation(libs.androidx.navigation.compose)

    implementation(libs.conscrypt.android)
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
