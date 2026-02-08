plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")
    implementation("io.ktor:ktor-server-call-logging:2.3.12")

    // JSON
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Logging backend for logback.xml
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Flyway
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")

    // JDBC driver
    implementation("org.postgresql:postgresql:42.7.4")

    // Hikari
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Exposed
    implementation("org.jetbrains.exposed:exposed-core:0.53.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.53.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.53.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.53.0")

    // Bitcoin address derivation (BIP-32/84/86)
    implementation("org.bitcoinj:bitcoinj-core:0.17")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("cz.majny.wallet.registry.MainKt")
}
