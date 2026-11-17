plugins {
    id("com.android.application")
    // AGP 9 ships built-in Kotlin support, so kotlin-android is no longer applied.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.pocketserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pocketserver"
        minSdk = 26
        // Deliberately 28: Android 10+ blocks execve() of binaries living in the
        // app's home directory for apps targeting 29+, and PocketServer must exec
        // proot + the Linux userland. This is the same trade-off Termux makes.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    // `pm install -r` refuses an APK signed with a different key than the
    // installed one, and Gradle's default debug signing resolves
    // $HOME/.android/debug.keystore from Java's user.home — which on this device
    // is the Termux home (/data/data/com.termux/files/home), not the harness home
    // that signed the installed build. Pinning the store to $HOME makes every
    // build (app UID, Shizuku or root) produce the same signature.
    signingConfigs {
        create("deviceDebug") {
            storeFile = file(
                (System.getenv("HOME") ?: System.getProperty("user.home")) +
                    "/.android/debug.keystore"
            )
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("deviceDebug")
        }
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
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    androidResources {
        // Keep the rootfs tarball byte-identical inside the APK.
        noCompress += listOf("targz")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // Compose 1.12+ requires compileSdk 37; this BOM is the newest that builds on 36.
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
