# youtubedl-android membaca respons JSON yt-dlp lewat Jackson (refleksi).
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Kelas yang dipanggil dari kode native (JNI).
-keepclasseswithmembernames class * { native <methods>; }
