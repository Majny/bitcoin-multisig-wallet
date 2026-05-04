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

    // Ktor client (used to call blockchain-service)
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")

    // Logging
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

    // BitcoinJ - low-level Bitcoin primitives used when assembling PSBTs
    implementation("org.bitcoinj:bitcoinj-core:0.17")

    // Test deps - JUnit 5 platform + kotlin.test assertions + parameterized tests
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")

    // In-memory H2 database (PostgreSQL compatibility mode) for PsbtRepository tests
    testImplementation("com.h2database:h2:2.2.224")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("cz.majny.wallet.psbt.MainKt")
}
