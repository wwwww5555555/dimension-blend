import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core-render
 * DAG 非破坏性编辑：工程图（Project）的执行引擎。
 *
 * 分工：
 *  - RenderGraphEngine：纯逻辑（拓扑序 / 脏标记传播 / ColorMatrix 合并 / 执行计划），**可纯 JVM 单测**；
 *  - RenderGraphExecutor：GLES 执行（FBO 纹理池 / 节点分发 / readPixels）。
 *
 * 依赖纪律：只依赖 :core-common（模型与图算法），不依赖 :core-cv / :core-ai
 * —— DAG 是执行框架，不该知道任何具体算法实现。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.naiwa.core.render"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // 本模块的类会触及 android.util.Log / Bitmap 等框架类：
            // 本地单测里这些是未实现的桩，不打开这个开关会直接抛 "not mocked"。
            // 纯逻辑（拓扑/脏传播/滤镜解析）本来就与它们无关，默认值足够。
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.kotlinx.coroutines.android)
    // android-gpuimage-plus（wysaid）：预编译 AAR，自带 native so，无需 NDK。
    // -min 版去掉了视频模块；POM 无传递依赖。
    implementation("org.wysaid:gpuimage-plus:3.2.0-min")
    testImplementation(libs.junit)
    // 本地单测里 android.jar 的 org.json 是未实现的桩（"not mocked"），
    // 用真实实现覆盖测试类路径，才能验证工程文件的序列化往返。
    testImplementation("org.json:json:20240303")
}
