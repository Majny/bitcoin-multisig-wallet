pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        // Shared Kotlin plugin versions for all subprojects in this build.
        id("org.jetbrains.kotlin.jvm") version "2.2.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
        // If a subproject pulls in extra Gradle plugins (e.g. Ktor) via the
        // plugins DSL, declare their versions here too.
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "wallet"

include(":api-gateway")
include(":auth-service")

include("wallet-registry")
include("blockchain-service")
include("price-service")
include("psbt-service")
include("explorer-service")