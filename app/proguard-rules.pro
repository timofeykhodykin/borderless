# gomobile bindings are called from native code
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keepclassmembers class * implements libv2ray.CoreCallbackHandler { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
