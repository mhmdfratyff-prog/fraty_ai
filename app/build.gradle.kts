plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val fratyLocalProperties = java.util.Properties().apply {
    val secretsFile = rootProject.file("fraty.local.properties")
    if (secretsFile.isFile) secretsFile.inputStream().use { load(it) }
}
fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "")}\""

android {
    namespace = "com.fraty.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fraty.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.02"
        buildConfigField("String", "FRATY_BACKEND_URL", buildConfigString(fratyLocalProperties.getProperty("FRATY_BACKEND_URL", "")))
        buildConfigField("String", "FRATY_APP_TOKEN", buildConfigString(fratyLocalProperties.getProperty("FRATY_APP_TOKEN", "")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
