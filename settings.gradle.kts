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
    }
    plugins {
        id("com.android.application") version "8.13.2"
        id("org.jetbrains.kotlin.android") version "2.3.0"
        id("org.jetbrains.kotlin.jvm") version "2.3.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "HyAssistant"
include(":core")
// HY_CORE_ONLY=1 builds/tests just the pure-Kotlin module (no Android SDK needed).
if (System.getenv("HY_CORE_ONLY") == null) {
    include(":app")
}
