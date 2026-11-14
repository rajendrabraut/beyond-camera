import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: project-root keystore.properties wins, else ~/.android/beyond-camera-keystore.properties.
// Neither file is committed. Without one, release builds are signed with the debug key so they still install.
val releaseKeystoreProps: Properties? = listOf(
    rootProject.file("keystore.properties"),
    File(System.getProperty("user.home"), ".android/beyond-camera-keystore.properties")
).firstOrNull { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.beyond.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.beyond.camera"
        minSdk = 26
        targetSdk = 36
        // a.b.c.d -> a*10000000 + b*100000 + c*1000 + d (always above the 1.0.0.2 build, code 1010001)
        versionCode = 10000009
        versionName = "1.0.0.9"

        // Phones only: drops the x86 copies of the native libraries (about half the APK size).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseKeystoreProps != null) {
            create("release") {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Beyond Debug")
        }
        release {
            resValue("string", "app_name", "Beyond")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":liveclips"))
    val cameraxVersion = "1.6.2"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")

    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // QR / barcode scanning and generation: pure Java, fully offline, no telemetry.
    implementation("com.google.zxing:core:3.5.3")
    // On-device person segmentation for Portrait (model bundled; network access is stripped in the manifest).
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
}
