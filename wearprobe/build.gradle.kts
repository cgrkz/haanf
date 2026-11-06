import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A throwaway watch app that only reports what Health Services offers on this watch.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.haanf.wearprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.haanf.wearprobe"
        // Wear OS 3, the oldest version Health Services supports.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.health.services)
    implementation(libs.kotlinx.coroutines.android)
}
