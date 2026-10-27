# :core-cv 消费者混淆规则
# OpenCV Java 层通过 JNI 反射调用，必须整体保留。
-keep class org.opencv.** { *; }
-keep class com.naiwa.core.cv.** { *; }
