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
