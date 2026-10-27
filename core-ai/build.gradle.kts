import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core-ai
 * 端侧推理：光照估计（LiteRT 回归模型 + CV 兜底）、奶蛙抠图（MediaPipe Image Segmenter / Interactive Image Segmenter）。
 * 模型文件放本模块 src/main/assets/models/，构建时会被合并进 APK。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.naiwa.core.ai"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // LiteRT / MediaPipe 需要 mmap 直接读取，禁止压缩
        noCompress += listOf("tflite", "task")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    /*
     * 端侧推理运行时（P1 起）。坐标可用 org.tensorflow:tensorflow-lite（Maven Central）
     * 或 com.google.ai.edge.litert:litert（Google Maven），同一运行时、同 API、同 .tflite 格式。
     * 加速器顺序：GpuDelegate → CPU/XNNPACK(4 线程)。NNAPI 自 Android 15 起废弃，本项目不使用 NnApiDelegate。
     */
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    /*
     * MediaPipe Tasks Vision（P2 的点选分割）**暂不引入**。
     *
     * 原因（记录一下，避免以后有人"顺手加上"）：
     *  1. 我没把它的调用代码接进 UI，引进来就是 30MB 死重量；
     *  2. 它的 AAR 会把 INTERNET 权限合并进最终 manifest，破坏"物理上无法联网"这条硬约束；
     *  3. 没有真机可验证其 API 行为，不适合把未验证的原生推理路径塞进交付物。
     * 需要时按 core-ai/src/main/assets/models/README.md 的说明加回依赖 + 下载 magic_touch.tflite，
     * 并记得在 app 的 AndroidManifest 里用 tools:node="remove" 剥掉网络权限。
     */
    // implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
}
