import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun localProperty(name: String): String? =
    localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

plugins {
    alias(libs.plugins.androidApplication)
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.secretsGradle)
}

android {
    namespace = "com.alexleoreeves.novelapp.tv"
    compileSdk = 35

    val releaseStoreFile = localProperty("RELEASE_STORE_FILE")
    val releaseStorePassword = localProperty("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = localProperty("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = localProperty("RELEASE_KEY_PASSWORD")

    defaultConfig {
        applicationId = "com.alexleoreeves.novelapp.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 43
        versionName = "1.43"

        // Exclude x86/x86_64 desktop binaries to cut APK size in half
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    buildTypes {
        if (
            releaseStoreFile != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            signingConfigs {
                create("release") {
                    storeFile = rootProject.file(releaseStoreFile)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
        }

        release {
            // R8 code shrinking: cuts DEX size substantially. JNI/native and
            // reflection entry points are pinned in proguard-rules.pro
            // (nodebridge, Sherpa-ONNX, LibVLC, kotlinx.serialization, ZXing).
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("onnx", "bin", "dict", "json", "wav", "zip")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("../composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/data")
            kotlin.srcDir("../composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/nodebridge")
            kotlin.exclude(
                "LocalDownloadRepository.kt",
                "LocalFileStorage.kt",
                "MangaPageCache.kt"
            )
        }
    }
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("../nodebridge/jniLibs")
            assets.srcDir("../nodebridge/assets")
        }
    }
}

dependencies {
    // TV UI
    implementation("androidx.activity:activity-compose:1.9.3")
    // FileProvider for in-app updates (androidx.core.content.FileProvider)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0-rc01")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
    // Media Player: LibVLC SDK for robust MKV/HEVC/HLS direct streaming
    implementation("org.videolan.android:libvlc-all:3.6.0-eap5")
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(libs.jsoup)
    implementation(libs.ksoup)
    implementation("com.google.zxing:core:3.5.3")
}

secrets {
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "local.defaults.properties"
}
