plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.toffaha.qurangarden"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.toffaha.qurangarden"
        minSdk = 24
        targetSdk = 34
        // Auto-bumped on every GitHub Actions build, so updates always install over the old app.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
        // Fingerprint of the native (Kotlin/manifest) code. Releases with the same fingerprint
        // only changed the page, so the app can update itself silently without reinstalling.
        buildConfigField("String", "NATIVE_HASH", "\"${System.getenv("NATIVE_HASH") ?: "dev"}\"")
    }

    // One fixed signing key for every build. Android only installs an update over the
    // existing app when both APKs are signed with the same key.
    signingConfigs {
        create("family") {
            storeFile = rootProject.file("keystore/quran-garden.jks")
            storePassword = System.getenv("QG_STORE_PASSWORD") ?: "qurangarden"
            keyAlias = "qurangarden"
            keyPassword = System.getenv("QG_KEY_PASSWORD") ?: "qurangarden"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("family")
        }
    }

    buildFeatures {
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
}
