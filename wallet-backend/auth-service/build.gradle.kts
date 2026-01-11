plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Ktor server ---
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")

    // --- JSON (kotlinx.serialization) ---
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12")

    // --- JWT (Auth0 java-jwt) pro RS256 signing ---
    implementation("com.auth0:java-jwt:4.4.0")

    // (volitelné, ale praktické) logování v dev
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // --- Testy ---
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.12")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:2.3.12")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12")

    // pokud chceš v testech i ověřovat JWT signature/verifierem:
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}


tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21) // doporučuju 21; 24 ti může dělat bordel se závislostma a CI
}

application {
    mainClass.set("org.example.MainKt")
}
