import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core-common
 * 只放「跨层共享、无副作用」的东西：领域模型、图像通用工具、渲染管线的接口契约。
 * 禁止依赖：Android UI（Compose）、CameraX、OpenCV、LiteRT —— 保持可纯 JVM 单测。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.naiwa.core.common"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    // BitmapDecoder 需要读 EXIF 方向（相机与相册图都必须校正）
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit)
}
