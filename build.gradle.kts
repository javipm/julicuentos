// Root build file — declares the pinned plugins once for the build (design.md D9).
// Version pins come from gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
