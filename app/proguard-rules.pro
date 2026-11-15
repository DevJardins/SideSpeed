# SideSpeed's shell-uid helper is started by name from app_process:
#   app_process /system/bin ... io.github.devjardins.sidespeed.helper.HelperMain --apk=...
# Nothing in the app references it, so R8 must keep the class name and its static main.
-keep class io.github.devjardins.sidespeed.helper.HelperMain {
    public static void main(java.lang.String[]);
    public static final io.github.devjardins.sidespeed.helper.HelperMain INSTANCE;
}

# Libraries that load classes/providers reflectively or via JNI.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class android.sun.security.** { *; }

# R8 "missing class" errors, all in code paths never reached on Android:
# - Conscrypt's pre-KitKat platform socket adapters reference platform internals absent from the SDK.
# - BouncyCastle's LDAP cert/CRL stores reference javax.naming, which Android does not ship.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn javax.naming.**
