# ── R8 / ProGuard rules for NovaRead TV ──────────────────────────────────────
# Release builds run R8 (isMinifyEnabled = true). These rules pin the classes
# and members reached through JNI, native libraries and whose names are
# resolved reflectively at runtime, so R8 can shrink/rename the rest safely.

# ── Embedded NodeBridge (JNI) ────────────────────────────────────────────────
# The C++ shim resolves
#   Java_com_alexleoreeves_novelapp_nodebridge_NodeNativeBridge_startNode
# by the fully-qualified name at runtime. Renaming the class or its external
# method (or stripping the symbol via obfuscation) makes the JNI lookup fail
# silently and the bridge degrades to backend fallback.
-keep class com.alexleoreeves.novelapp.nodebridge.NodeNativeBridge { *; }

# ── Sherpa-ONNX offline TTS (JNI) ────────────────────────────────────────────
# com.k2fsa.sherpa.onnx.* binds native methods from libsherpa-onnx-jni.so by
# exact name. Obfuscating these classes crashes TTS with UnsatisfiedLinkError.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ── VideoLAN LibVLC player (JNI) ─────────────────────────────────────────────
# The media player loads native libVLC through native methods resolved by
# class/method name; exact names must survive R8.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# ── kotlinx.serialization models ─────────────────────────────────────────────
# KSP generates *$$serializer classes and Companion serializer() methods that
# the runtime looks up reflectively. Preserve the descriptor classes and the
# serializer factory pattern for every model in the app package.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keep,includedescriptorclasses class com.alexleoreeves.novelapp.**$$serializer { *; }
-keepclassmembers class com.alexleoreeves.novelapp.** { *** Companion; }
-keepclasseswithmembers class com.alexleoreeves.novelapp.** { kotlinx.serialization.KSerializer serializer(...); }
-dontnote kotlinx.serialization.**

# ── ZXing QR (phone/TV pairing payment flows) ────────────────────────────────
# Decoders/encoders are registered through the reader/writer registry.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── HTTP stack (OkHttp / Ktor) ───────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# ── Activity entry point (manifest already pins it; belt-and-braces) ────────
-keep class com.alexleoreeves.novelapp.tv.TvMainActivity { *; }

# ── Crash reporting: readable stack traces ───────────────────────────────────
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
