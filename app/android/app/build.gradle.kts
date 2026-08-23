plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.whisper.transcribe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.whisper.transcribe"
        // Die echte Untergrenze setzt die Engine: die arm64-Variante braucht i8mm
        // (SoCs ab ~2021), geprüft zur Laufzeit in whisper_jni.c.
        // Nur mit -DGGML_VULKAN=ON muss hier 28 stehen (vkGetPhysicalDeviceFeatures2).
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.6.1"
        ndk {
            // Standard: nur Geräte-ABI (schlanke APK). Für Emulator/Universal:
            // ./gradlew assembleDebug -Pabis=arm64-v8a,x86_64
            abiFilters += (project.findProperty("abis") as String? ?: "arm64-v8a").split(",")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.17.0")
}
