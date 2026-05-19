-keep class kotlinx.serialization.** { *; }
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.github.luben.zstd.ZstdInputStream

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
