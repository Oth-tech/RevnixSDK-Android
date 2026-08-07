pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "revnix-kotlin"

// The client core is pure JVM Kotlin so the resilience matrix runs on a plain
// JVM test task; only the Play Billing glue needs the Android toolchain.
include(":revnix-core")
include(":revnix-android")
// Kotlin Multiplatform: shares the policy, forks the transport to Ktor.
include(":revnix-kmp")
