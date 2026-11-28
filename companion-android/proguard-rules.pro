# Libraries that load classes/providers reflectively or via JNI.
-keep class com.flyfishxu.kadb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class android.sun.security.** { *; }

# R8 "missing class" errors, all in code paths never reached on Android:
# - Conscrypt's pre-KitKat platform socket adapters reference platform internals absent from the SDK.
# - BouncyCastle's LDAP cert/CRL stores reference javax.naming, which Android does not ship.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn javax.naming.**
