# Loaded by the modern Xposed API through META-INF/xposed/java_init.list.
-keep class org.lyaaz.fuckgboard.MainHook { *; }

# Shizuku instantiates the app-owned Binder service through its Context constructor.
-keep class org.lyaaz.fuckgboard.DictionaryUserService {
    public <init>(android.content.Context);
}
