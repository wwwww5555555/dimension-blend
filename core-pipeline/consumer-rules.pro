# :core-pipeline 消费者混淆规则
# 管线实现类通过 Hilt 注入 + 被反射式序列化调试输出引用，保留公开成员名。
-keep class com.naiwa.core.pipeline.** { *; }
