# :core-common 消费者混淆规则
# 领域模型用于跨模块边界（含反射式序列化/调试输出），保留公开成员名。
-keep class com.naiwa.core.common.model.** { *; }
