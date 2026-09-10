pluginManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 自动下载匹配的 JDK 工具链。取代了原先硬编码在 gradle.properties 里的
// org.gradle.java.home=/opt/homebrew/...（那行让工程只能在作者的 Mac 上构建）。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "llm_reader"

// :shared —— KMP 核心逻辑（Android + Desktop 共用）
// :app    —— Android 客户端（Jetpack Compose）
// :desktop—— 桌面客户端（Compose Multiplatform for Desktop）
include(":shared")
include(":app")
include(":desktop")
