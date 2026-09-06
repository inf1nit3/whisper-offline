plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

android {
    namespace = "dev.whisper.transcribe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.whisper.transcribe"
        // Die echte Untergrenze setzt die Engine: der arm64-Build braucht
        // dotprod (SoCs ab ~2018), geprüft zur Laufzeit in whisper_engine.c.
        // Nur mit -DGGML_VULKAN=ON muss hier 28 stehen (vkGetPhysicalDeviceFeatures2).
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "2.0"
        ndk {
            // Standard: nur Geräte-ABI (schlanke APK). Für Emulator/Universal:
            // ./gradlew assembleDebug -Pabis=arm64-v8a,x86_64
            abiFilters += (project.findProperty("abis") as String? ?: "arm64-v8a").split(",")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signierung wird unten gesetzt, wenn der Keystore vorhanden ist
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

/// CHANGELOG.md aus dem Repo-Root bei jedem Build ins Asset-Verzeichnis
/// synchronisieren — der In-App-Changelog kann so nie einen Stand verpassen.
tasks.register<Copy>("syncChangelog") {
    from(rootProject.file("../../CHANGELOG.md"))
    into("src/main/assets")
}
tasks.named("preBuild") { dependsOn("syncChangelog") }

/// Release-Signierung aus keystore/keystore.properties (lokal, nie committet).
/// Fehlt die Datei, fällt assembleRelease auf die Debug-Signatur zurück,
/// damit Build und Tests auch ohne Keystore durchlaufen.
val keystoreProps = Properties().apply {
    val f = rootProject.file("../../keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file("../../keystore/whisper-release.jks")
                storePassword = keystoreProps.getProperty("whisper.store.password")
                keyAlias = keystoreProps.getProperty("whisper.key.alias")
                keyPassword = keystoreProps.getProperty("whisper.key.password")
            }
        }
    }
    buildTypes {
        release {
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
