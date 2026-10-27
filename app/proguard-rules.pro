# 次元溶图 · Release 混淆与裁剪规则
#
# 目标：能反编译但改不动、能被裁剪但不崩、崩溃栈还能读。
# 原则：**能用 consumer-rules.pro 就近声明的，不堆在这里**
#      （:core-ai / :core-cv / :core-common / :core-pipeline 已各自声明）。

# ------------------------------------------------------------------ ① 日志

# 为什么用 -assumenosideeffects 而不是 `if (BuildConfig.DEBUG)`：
#
#  1. 它作用在**字节码层**：删掉整个调用，**连同参数求值一起删**
#     （`Log.i(TAG, "x=${a}x$b")` 的字符串拼接与临时对象都不会生成）。
#     `if (BuildConfig.DEBUG)` 只是执行时跳过，代码与常量池仍然在包里。
#  2. 本工程的日志分散在 8 个模块、124 处。`BuildConfig` 在 **library 模块里默认不生成**
#     （AGP 8 起 `buildFeatures.buildConfig` 默认 false），要逐点包装就得给 8 个模块
#     全部打开 buildConfig —— 为了一行日志改 8 份构建脚本，收益为负。
#  3. 这里的规则对**所有模块**统一生效，不依赖任何一处调用方配合。
#
# 保留 Log.w / Log.e 的原因：它们全部落在**失败与降级分支**（统计见 docs/RELEASE.md），
# 不在每帧/每像素循环里，运行时开销为 0；而它们是现场定位"为什么这台手机光影没了"的唯一线索。
# 要绝对静默，把下面 Log.w 那行放开即可（代价是 release 出问题完全无迹可查）。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# public static int w(...);

# ------------------------------------------------------------------ ② JNI / 原生库

# 任何原生方法所在类都不能被重命名：JNI 按 `Java_<包名>_<类名>_<方法名>` 查符号，
# 改了名字就是 UnsatisfiedLinkError（而且只在第一次调用时炸）。
-keepclasseswithmembernames class * {
    native <methods>;
}

# OpenCV Java 层（JNI + 静态初始化）
-keep class org.opencv.** { *; }

# android-gpuimage-plus（wysaid）：C++ 侧通过 JNI 回调 Java 侧，EffectString 由 native 解析。
# 它**没有**随 AAR 提供 consumer-rules.pro，必须在这里兜住。
-keep class org.wysaid.** { *; }

# LiteRT / MediaPipe（端点与 native 绑定）
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.lite.** { *; }

# ------------------------------------------------------------------ ③ 反射

# RuntimeProvider 用 Class.forName 探测可选的 NPU/QNN 委托。
# 这些类在公开的 LiteRT AAR 里**并不存在**（由各厂商插件包提供），
# 所以既要 keep（存在时不能改名），也要 dontwarn（不存在时不许让构建失败）。
-keep class org.tensorflow.lite.qnn.** { *; }
-keep class org.tensorflow.lite.npu.** { *; }
-dontwarn org.tensorflow.lite.qnn.**
-dontwarn org.tensorflow.lite.npu.**

# ------------------------------------------------------------------ ④ 光影渲染链路

# 本工程的 GLES 管线**没有**反射装载、没有 RegisterNatives、shader 是字符串常量，
# 所以严格来说不需要 keep —— R8 重命名它们不影响任何一处查表。
#
# 之所以还是保留：它是全项目**唯一无法在 CI 里验证的运行路径**（必须有真机 GPU 与驱动），
# 一次重命名事故的代价是"用户手机上花屏/黑屏"，而排查成本极高。
# 保留的代价只有几十 KB。这是刻意的保守，不是技术上必需。
-keep class com.naiwa.core.cv.gles.** { *; }
-keep class com.naiwa.core.render.** { *; }

# ------------------------------------------------------------------ ⑤ 崩溃栈可读性

# 不保留 SourceFile/LineNumberTable 的话，线上崩溃只有一串 `a.a.a(Unknown Source)`。
# 配合 R8 产出的 mapping.txt，可以直接还原到"哪个类的第几行"。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 注解在 Compose / Hilt / Kotlin 反射场景里仍会被读到
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ------------------------------------------------------------------ ⑥ 依赖噪音

# 这些库引用了运行时并不存在的可选依赖，只是编译期引用。
# 不 dontwarn 的话 R8 会直接让 release 构建失败（而不是产出一个能跑的包）。
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn com.google.auto.value.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.eclipse.jetty.**

# ---- LiteRT GPU 委托（AGP 自动生成的 missing_rules，见
#      app/build/outputs/mapping/release/missing_rules.txt）
#
# `tensorflow-lite-gpu:2.16.1` 的 `GpuDelegate` 字节码里引用了
# `GpuDelegateFactory$Options`，但这两个类在同版本的公开 AAR 里**没有打包**
# （只存在于 `GpuDelegateFactory.Options` 一组构造器签名中）。
# 我们只调用**无参构造** `GpuDelegate()`，从不触碰 `Options`。
#
# 就算哪天 TFLite 内部改到那条路径：`RuntimeProvider.tryCreateGpuDelegate()`
# 是 `catch (t: Throwable)` 包住的，NoClassDefFoundError 也会被吞掉并降级到 CPU，
# 不会闪退。所以这里是"确认过降级路径"的 dontwarn，不是"先让它过"的将就。
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
