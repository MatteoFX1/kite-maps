# MapLibre uses JNI and reflection.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
# OkHttp's optional platform bits.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
