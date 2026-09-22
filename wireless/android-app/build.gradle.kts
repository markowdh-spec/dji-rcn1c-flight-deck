import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application") version "8.5.2"
}

val syncFpvSimAssets = tasks.register<Sync>("syncFpvSimAssets") {
    from(layout.projectDirectory.dir("../../viz_app/static/fpv-sim"))
    into(layout.projectDirectory.dir("assets/fpv-sim"))
}

tasks.matching { it.name == "preBuild" || it.name == "assemble" }.configureEach {
    dependsOn(syncFpvSimAssets)
}

android {
    namespace = "com.drone.rcn1cbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drone.rcn1cbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "6.0.0"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            res.srcDirs("res")
            assets.srcDirs("assets")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = file(System.getenv("RCN1C_KEYSTORE_PATH")
                    ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
