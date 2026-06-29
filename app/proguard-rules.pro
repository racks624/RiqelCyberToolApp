# Keep our application classes
-keep class com.riqel.cybertool.** { *; }

# Keep Android framework classes
-keep class android.** { *; }
-keep class androidx.** { *; }

# Keep OkHttp and GSON
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.gson.** { *; }

# Keep Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class *

# Keep CameraX
-keep class androidx.camera.** { *; }

# Keep WorkManager
-keep class androidx.work.** { *; }

# Remove debug logs in release (optional)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
