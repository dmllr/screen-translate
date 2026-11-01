# JNI entry points are looked up by name from native code.
-keep class com.eillance.screentranslate.translate.BergamotNative { native <methods>; }
-keep class com.eillance.screentranslate.langid.FastTextNative { native <methods>; }
# ONNX Runtime uses JNI and reflection.
-keep class ai.onnxruntime.** { *; }
