# Methods exposed to JavaScript are looked up by name at runtime.
-keepclassmembers class com.devicevault.app.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
