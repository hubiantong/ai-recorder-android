// 中国镜像开关：默认直连官方源（保证非中国开发者与 CI 可靠性）；
// 国内网络可在 gradle.properties 或命令行加 -PuseCnMirrors=true 启用阿里云镜像优先。
// 注意：pluginManagement / dependencyResolutionManagement 为乱序执行块，
// 看不到脚本级变量，须在块内各自读取 gradle 属性。

pluginManagement {
    val useCnMirrors = providers.gradleProperty("useCnMirrors").getOrElse("false").toBoolean()
    repositories {
        if (useCnMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    val useCnMirrors = providers.gradleProperty("useCnMirrors").getOrElse("false").toBoolean()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useCnMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ai-recorder-android"
include(":app")
