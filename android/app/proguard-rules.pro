-keep class kotlinx.serialization.** { *; }
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.github.luben.zstd.ZstdInputStream

# PyTorch Android creates NativePeer/HybridData objects through JNI. Keep these
# classes and native members intact in release builds so R8 does not rewrite or
# remove entry points required by libpytorch_jni.so.
-keep class org.pytorch.** { *; }
-keep class com.facebook.jni.** { *; }
-keep class com.facebook.soloader.** { *; }
-keep class com.facebook.soloader.nativeloader.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.pytorch.**
-dontwarn com.facebook.jni.**
-dontwarn com.facebook.soloader.**

-keep @com.getcapacitor.annotation.CapacitorPlugin public class * {
    @com.getcapacitor.annotation.PermissionCallback <methods>;
    @com.getcapacitor.annotation.ActivityCallback <methods>;
    @com.getcapacitor.annotation.Permission <methods>;
    @com.getcapacitor.PluginMethod public <methods>;
}

-keep public class * extends com.getcapacitor.Plugin { *; }
-keep @com.getcapacitor.NativePlugin public class * {
    @com.getcapacitor.PluginMethod public <methods>;
}
-keep public class * extends org.apache.cordova.* {
    public <methods>;
    public <fields>;
}
