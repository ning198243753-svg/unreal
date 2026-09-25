# libxposed entry point is referenced from META-INF/xposed/java_init.list by name.
-keep class com.moon.location.xposed.ModuleEntry { *; }
-keep class com.moon.location.xposed.** { *; }

# Keep classes referenced reflectively from modules.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
