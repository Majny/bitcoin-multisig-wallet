import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Load `local.properties` (gitignored) for developer-specific overrides.
// Used to inject the API gateway URL into BuildConfig without hardcoding it
// into the source. If the file or the property is missing, the build falls
// back to the default below — `10.0.2.2`, which is the Android emulator's
// alias for the host machine's localhost. With the backend running via
// `docker compose up`, that default makes the app work out-of-the-box.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}
val apiGatewayBaseUrl: String = localProperties.getProperty("api.gateway.base.url")
    ?: "http://10.0.2.2:8080/api/v1"

android {
    namespace = "com.example.bitcoinwallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bitcoinwallet"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "API_GATEWAY_BASE_URL", "\"$apiGatewayBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // --- Compose ---
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Icons (Lock, CheckCircle, Autorenew...)
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation (AppNavHost)
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // --- Lifecycle + coroutines ---
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- QR Code (ZXing core) ---
    implementation("com.google.zxing:core:3.5.3")

    // --- UR decoder (Sparrow/Keystone airgap QR) ---
    implementation("com.sparrowwallet:hummingbird:1.7.4")

    // --- CameraX (QR scanner) ---
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // --- Ktor 3 ---
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-android:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
    implementation("io.ktor:ktor-client-logging:3.0.0")
}

