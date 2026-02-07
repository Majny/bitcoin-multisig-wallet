plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.0"
    application
}

application {
    mainClass.set("cz.majny.wallet.gateway.MainKt")
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.0.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.0")

    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.0.0")

    implementation("io.ktor:ktor-server-call-logging-jvm:3.0.0")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.0.0")

    implementation("io.ktor:ktor-server-auth-jvm:3.0.0")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:3.0.0")

    // -------- JWT (Auth0) - ty importy com.auth0.jwt.* --------
    implementation("com.auth0:java-jwt:4.4.0")

    // -------- KTOR CLIENT (volání microservices) --------
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-cio:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-client-logging:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")

    // -------- LOGGING --------
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("com.auth0:jwks-rsa:0.22.1")


    testImplementation(kotlin("test"))

}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}