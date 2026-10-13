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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Family app installed by hand: sign with the debug key so the APK installs directly.
            // For Google Play, replace this with your own release keystore.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
}
