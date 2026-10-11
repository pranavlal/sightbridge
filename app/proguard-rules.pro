# SightBridge application rules. Meta DAT 0.8+ includes its consumer R8 rules.
# Keep Kotlin coroutine continuation signatures used by SDK flow collection.
-keepattributes Signature,InnerClasses,EnclosingMethod

# MediaPipe's Flogger discovers its caller from stack frames. Obfuscation and
# inlining break Graph initialization in non-debuggable builds (upstream #6138).
-keep class com.google.common.flogger.** { *; }
# Native packet creation accesses MediaPipe classes and protobuf fields by name.
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
# Optional graph-template/profiling APIs are absent from the tasks AAR and unused.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
# ONNX JNI resolves tensor/result classes by their original Java names.
-keep class ai.onnxruntime.** { *; }
