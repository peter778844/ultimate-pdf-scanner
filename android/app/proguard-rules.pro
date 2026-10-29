# Libraries whose native code calls back into Java by class and member name (JNI): R8 must not rename or drop them.
-keep class org.opencv.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# ML Kit (translation, language identification, face detection) wires its components together by
# reflection; without these the translator failed with a null factory in the R8 build.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**

# PDFBox loads fonts, filters and resources by name.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
