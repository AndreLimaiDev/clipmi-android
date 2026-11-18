# youtubedl-android membaca respons JSON yt-dlp lewat Jackson (refleksi).
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Kelas yang dipanggil dari kode native (JNI).
-keepclasseswithmembernames class * { native <methods>; }

# youtubedl-android membongkar Python bawaan lewat Apache Commons Compress, yang membuat kelas "extra field" ZIP lewat
# refleksi (ExtraFieldUtils); tanpa ini inisialisasi yt-dlp gagal di APK rilis (ExceptionInInitializerError).
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# LiteRT (deteksi wajah/pembicara) memanggil balik kelas Java dari kode native.
-keep class com.google.ai.edge.litert.** { *; }
