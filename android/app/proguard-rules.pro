# SQLCipher ships native code reached over JNI; its classes must not be renamed or stripped.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Room, Firebase Auth, Google Play Services and Coil ship their own consumer ProGuard rules, so no
# manual keeps are needed for them here. If a release smoke test surfaces a ClassNotFoundException or
# NoSuchMethodException (watch Cropify — a JitPack lib with no bundled rules), add a targeted -keep.
