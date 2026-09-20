// Android port of simplify2md — Gradle settings.
// Independent Gradle build; the Go/Vue desktop app under mdview/ is NOT part of this build.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "simplify2md-android"
include(":app")
