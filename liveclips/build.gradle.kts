import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signing = listOf(
    rootProject.file("liveclips-keystore.properties"),
    File(System.getProperty("user.home"), ".android/beyond-liveclips-keystore.properties")
).firstOrNull { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.beyond.liveclips"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.beyond.liveclips"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        signing?.let {
            create("release") {
                storeFile = rootProject.file(it.getProperty("storeFile"))
                storePassword = it.getProperty("storePassword")
                keyAlias = it.getProperty("keyAlias")
                keyPassword = it.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val camera = "1.6.2"
    val media3 = "1.9.4"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.camera:camera-camera2:$camera")
    implementation("androidx.camera:camera-lifecycle:$camera")
    implementation("androidx.camera:camera-video:$camera")
    implementation("androidx.camera:camera-view:$camera")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    testImplementation("junit:junit:4.13.2")
}
