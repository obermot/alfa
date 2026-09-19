plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nezabudka.testharness"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nezabudka.alpha.clean013"
        minSdk = 26
        targetSdk = 36
        versionCode = 113
        versionName = "0.2.3-alarm-controls-alpha"
    }

    val signingStorePath = System.getenv("NEZABUDKA_SIGNING_STORE_PATH")
    val signingStorePassword = System.getenv("NEZABUDKA_KEYSTORE_PASSWORD")
    val signingKeyAlias = System.getenv("NEZABUDKA_KEY_ALIAS")
    val signingKeyPassword = System.getenv("NEZABUDKA_KEY_PASSWORD")
    val hasPersistentSigning = listOf(
        signingStorePath,
        signingStorePassword,
        signingKeyAlias,
        signingKeyPassword
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasPersistentSigning) {
            create("nezabudkaPersistent") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasPersistentSigning) {
                signingConfig = signingConfigs.getByName("nezabudkaPersistent")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.room:room-runtime:2.8.1")
    implementation("androidx.room:room-ktx:2.8.1")
    ksp("androidx.room:room-compiler:2.8.1")
    implementation("com.alphacephei:vosk-android:0.3.75")
}
