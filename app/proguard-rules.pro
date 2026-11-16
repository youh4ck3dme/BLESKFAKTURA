# R8 / ProGuard rules for Bleskova Faktura

# Keep Firebase / Firestore / Auth / AI Logic models & callbacks
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keepclassmembers class com.example.data.** { *; }

# Google Play Billing Library
-keep class com.android.billingclient.api.** { *; }

# Credential Manager & Google Identity
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Firebase Functions Callables
-keep class com.google.firebase.functions.** { *; }

# Strip verbose debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
