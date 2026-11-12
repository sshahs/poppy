# --- Matrix Rust SDK (UniFFI over JNA) ---
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.matrix.rustcomponents.sdk.** { *; }
-keep class uniffi.** { *; }

# --- UnifiedPush / Tink ---
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-keep class org.unifiedpush.android.connector.** { *; }

# Strip verbose logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
