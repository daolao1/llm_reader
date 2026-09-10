import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        // ---- 纯 Kotlin，无平台依赖：模型、平台缝隙声明 ----
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        // ---- Android 与 Desktop 共用的 JVM 实现 ----
        // 小说站点抓取（Jsoup）、LLM 客户端（OkHttp）、DataStore 仓储、本地文档解析
        // 全部落在这里：两端共享同一份代码，改动一次两端生效。
        // 将来若要支持 iOS，需要把这一层里依赖 JVM 的部分（Jsoup/java.util.zip/OkHttp）
        // 替换成 KMP 等价物后上移到 commonMain。
        val jvmSharedMain by creating {
            dependsOn(commonMain)
            dependencies {
                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.okhttp)
                implementation(libs.okhttp.logging)
                implementation(libs.okhttp.dnsoverhttps)
                implementation(libs.jsoup)
                implementation(libs.androidx.datastore.preferences.core)
                implementation(libs.okio)
                implementation(libs.markdown)
            }
        }

        val androidMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(libs.conscrypt.android)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                // Android 的 org.json 由系统 android.jar 提供，桌面 JVM 没有；
                // Kakuyomu / Literotica / Syosetu / Pixiv 四个源用 org.json 解析
                // 站点接口，这里补一个 API 相同的实现，让它们两端共用同一份代码。
                implementation(libs.org.json)
            }
        }

        // 桌面端（= 纯 JVM）路径的测试：`./gradlew :shared:desktopTest`
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

android {
    namespace = "com.zzy.llmreader.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
