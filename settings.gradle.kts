// Julicuentos native app — single :app module (design.md D5).
// Toolchain pins live in gradle/libs.versions.toml (design.md D9).
// Note: plugin versions are declared in the root build.gradle.kts plugins
// block via the version catalog — Gradle 8.7 does not resolve toml aliases
// from the settings plugins block, and this keeps the pins single-sourced.

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

rootProject.name = "julicuentos"
include(":app")
