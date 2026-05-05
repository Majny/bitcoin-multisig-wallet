plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:3.0.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.0")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.0.0")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.0.0")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.0.0")

    // Ktor client (for Mempool.space API)
    // OkHttp engine: CIO engine fails with Connection reset against mempool.space/testnet4
    // (likely a TLS/HTTP2 handshake incompatibility in CIO). OkHttp handles it correctly.
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-client-logging:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.0.0")
    testImplementation("io.ktor:ktor-client-mock:3.0.0")  // canned HTTP responses, no real network
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("cz.majny.wallet.blockchain.MainKt")
}
