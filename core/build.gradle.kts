plugins {
    id("com.android.library")
}

android {
    namespace = "com.ralenday.sereno.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    // Wearable Data Layer: teléfono <-> reloj por Bluetooth/Wi-Fi (gratis, sin servidor)
    api("com.google.android.gms:play-services-wearable:19.0.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // org.json real para las pruebas en JVM (el de android.jar es un stub)
    testImplementation("org.json:json:20250517")
}
