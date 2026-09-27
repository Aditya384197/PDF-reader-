# Keep PDFBox and ML Kit discovery/runtime metadata safe in release builds.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.google.mlkit.** { *; }
