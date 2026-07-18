import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
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
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roomPlugin)
    alias(libs.plugins.secretsGradle)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // Compose Desktop (PC — Windows / macOS / Linux)
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
            implementation("com.google.mlkit:text-recognition:16.0.0")
            implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
            implementation("com.google.mlkit:text-recognition-korean:16.0.0")
            // Anime streaming — Media3 ExoPlayer with HLS support
            implementation("androidx.media3:media3-exoplayer:1.3.1")
            implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
            implementation("androidx.media3:media3-ui:1.3.1")
            implementation("androidx.media3:media3-datasource:1.3.1")
            implementation(files("libs/sherpa-onnx-1.13.4.aar"))
            implementation("org.apache.commons:commons-compress:1.26.1")
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.ksoup)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.jspecify)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
            }
            resources.srcDir(rootProject.file("kokoro-assets"))
        }
    }
}

android {
    namespace = "com.alexleoreeves.novelapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    val releaseStoreFile = localProperty("RELEASE_STORE_FILE")
    val releaseStorePassword = localProperty("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = localProperty("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = localProperty("RELEASE_KEY_PASSWORD")

    defaultConfig {
        applicationId = "com.alexleoreeves.novelapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 35
        versionName = "1.35"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        noCompress += listOf("onnx", "bin", "dict", "json", "wav")
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

        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
        }
    }
    // Workaround: AGP 8.5.x checkAarMetadata NPE with KMP + Room/KSP
    // Google issue #289866777 - "Cannot invoke List.get(int) because path is null"
    // Only the release variant crashes; debug variant still validates compatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    sourceSets["main"].assets.srcDir(rootProject.file("kokoro-assets"))
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Workaround: AGP 8.5.x NPE in checkReleaseAarMetadata with KMP+KSP+Room
// Google issue #289866777 — only this specific variant check crashes,
// not the entire AAR metadata feature.
tasks.matching { it.name == "checkReleaseAarMetadata" }.configureEach {
    enabled = false
}

dependencies {
    debugImplementation(compose.uiTooling)
    add("kspAndroid", libs.room.compiler)
}

secrets {
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "local.defaults.properties"
}

compose.desktop {
    application {
        mainClass = "com.alexleoreeves.novelapp.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "NovelApp"
            packageVersion = "1.35.0"
            description = "Watch Anime · Read Novels · Read Manga — All in One"
            copyright = "© 2025 Mike A. (Alex Leo Reeves)"
            vendor = "Alex Leo Reeves"

            windows {
                iconFile.set(project.file("src/desktopMain/resources/icons/novelapp.ico"))
                menuGroup = "NovelApp"
                upgradeUuid = "055ef1e1-ffe1-4d5a-89b5-efd053023af0"
            }
        }
    }
}
