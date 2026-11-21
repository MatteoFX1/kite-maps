# MapLibre (inside maplibre-compose) uses JNI and reflection.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
# OkHttp's optional platform bits.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Compose resources (fonts, pictures) are looked up by name.
-keep class app.kite.resources.** { *; }
# The map engine is loaded through JavaCPP, which finds classes by name.
-keep class org.bytedeco.javacpp.** { *; }
-dontwarn org.bytedeco.**
