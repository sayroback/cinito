-keep class webtor.app.EngineHost { *; }
-keep class webtor.app.WebtorApp { *; }
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*, InnerClasses
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
