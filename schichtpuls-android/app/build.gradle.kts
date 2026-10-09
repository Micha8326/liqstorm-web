plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.schichtpuls.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.schichtpuls.app"
        minSdk = 26
        targetSdk = 35
        // Every CI build gets a higher number, so a new APK installs over the old one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // A fixed key for this sideloaded personal app, so updates keep installing over each other.
        create("sideload") {
            storeFile = file("schichtpuls-sideload.jks")
            storePassword = "schichtpuls"
            keyAlias = "schichtpuls"
            keyPassword = "schichtpuls"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
