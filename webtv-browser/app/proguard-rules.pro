# Règles ProGuard/R8. Le build "debug" livré n'utilise pas d'obfuscation.
# On garde les classes WebView par sécurité si un jour minifyEnabled passe à true.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class android.webkit.** { *; }
