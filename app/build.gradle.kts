plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rayo.isladinamica"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rayo.isladinamica"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.11"
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Shizuku: lets the app grant itself ADB-level permissions
    // (WRITE_SECURE_SETTINGS, notification listener, overlay, battery
    // whitelist) without a PC. API 13.1.5 removed Shizuku.newProcess(),
    // so shell commands run through a UserService (see IslandUserService).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
