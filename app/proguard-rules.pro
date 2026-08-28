# Keep JavaScript bridge entry points callable after R8 renaming.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Room reads this model through generated adapters, while saved database data must remain
# compatible across optimized builds.
-keep class com.interpretertrainer.app.data.database.PracticeSessionEntity { *; }
