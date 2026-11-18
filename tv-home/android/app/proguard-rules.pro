# The page calls these through window.TVOS.
-keepclassmembers class app.couchtv.launcher.MainActivity$Native {
    @android.webkit.JavascriptInterface <methods>;
}

# fork: the ADB client (home/SelfAdb.kt). Plain TCP only: its TLS side (Conscrypt) is not shipped.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.conscrypt.**
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
-dontwarn io.github.muntashirakon.**
