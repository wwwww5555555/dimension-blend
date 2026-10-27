# :core-ai 消费者混淆规则
# LiteRT / MediaPipe 的 Java API 与原生层（JNI）通过类名+方法签名互调，禁止混淆裁剪。
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.naiwa.core.ai.** { *; }
