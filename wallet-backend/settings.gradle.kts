pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        // dej sem VERZI, kterou používáš v celém repu
        id("org.jetbrains.kotlin.jvm") version "2.2.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
        // pokud používáš i Ktor pluginy nebo cokoliv dalšího přes plugins DSL, přidej sem taky
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

include(":app")
include(":utils")
include(":signer-service")
include(":mobile-signer")
include(":api-gateway")
include(":auth-service")

include("wallet-registry")
include("blockchain-service")
include("price-service")
include("psbt-service")
include("explorer-service")