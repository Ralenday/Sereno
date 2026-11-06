plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ralenday.sereno.mobile"
    compileSdk = 37

    defaultConfig {
        // Debe ser el MISMO applicationId que el del reloj para que la Data Layer los enlace
        applicationId = "com.ralenday.sereno"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
