# Placeholder for release hardening rules.
# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard defaults.

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Retrofit
-keepattributes Signature
-keepattributes *Annotation*

# Keep Gson
-keepattributes SerializedName
-keep class * { @com.google.gson.annotations.SerializedName <fields>; }

# Suppress warnings for missing optional dependencies
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn org.joda.time.**

# Keep Ollama model classes (Gson deserialization)
-keep class com.masterllm.core.ollama.model.** { *; }

# Keep InferencePerformanceTracker (reflection)
-keep class com.masterllm.runtime.gguf.InferencePerformanceTracker$LiveStats { *; }

# Keep Solace model download and inference classes
-keep class com.masterllm.app.solace.** { *; }

# Keep JNI-called methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
