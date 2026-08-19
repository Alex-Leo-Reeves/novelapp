# ── R8 / ProGuard rules for NovaRead TV ──────────────────────────────────────
# Release builds run R8 (isMinifyEnabled = true). These rules pin the classes
# and members reached through JNI, native libraries and whose names are
# resolved reflectively at runtime, so R8 can shrink/rename the rest safely.

# ── Compose & TV Foundation ────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-keep class androidx.tv.** { *; }
-dontwarn androidx.compose.**
-dontwarn androidx.tv.**

# ── Coil 3 Image Loading ───────────────────────────────────────────────────
-keep class coil3.** { *; }
-dontwarn coil3.**

# ── Embedded NodeBridge (JNI) ────────────────────────────────────────────────
-keep class com.alexleoreeves.novelapp.nodebridge.** { *; }
-dontwarn com.alexleoreeves.novelapp.nodebridge.**

# ── Android System TextToSpeech (TTS) ───────────────────────────────────────
-keepclassmembers class android.speech.tts.** { *; }
-dontwarn android.speech.tts.**

# ── Sherpa-ONNX Offline TTS (JNI & Native Engine) ───────────────────────────
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**


# ── VideoLAN LibVLC player (JNI) ─────────────────────────────────────────────
-keep class org.videolan.** { *; }
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**

# ── App Classes & Data Models ────────────────────────────────────────────────
-keep class com.alexleoreeves.novelapp.tv.** { *; }
-keep class com.alexleoreeves.novelapp.data.** { *; }

# ── kotlinx.serialization models ─────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keep,includedescriptorclasses class com.alexleoreeves.novelapp.**$$serializer { *; }
-keepclassmembers class com.alexleoreeves.novelapp.** { *** Companion; }
-keepclasseswithmembers class com.alexleoreeves.novelapp.** { kotlinx.serialization.KSerializer serializer(...); }
-dontnote kotlinx.serialization.**

# ── ZXing QR (phone/TV pairing payment flows) ────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── HTTP stack (OkHttp / Ktor / Coroutines) ─────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn org.jsoup.**
-dontwarn com.fleeksoft.ksoup.**

# ── Activity entry point (manifest already pins it; belt-and-braces) ────────
-keep class com.alexleoreeves.novelapp.tv.TvMainActivity { *; }

# ── Crash reporting: readable stack traces ───────────────────────────────────
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
