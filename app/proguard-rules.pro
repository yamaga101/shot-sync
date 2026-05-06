# Google API client uses reflection on its model classes.
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.googleapis.** { *; }

# OkHttp / Retrofit-ish
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.lang.model.element.**
