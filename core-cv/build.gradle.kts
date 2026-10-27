import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core-cv
 * 纯像素域算法：颜色迁移、方向性阴影合成、泊松/多频段融合、边缘羽化与去光晕。
 * 依赖方向：core-common -> core-cv（单向，禁止反向依赖）。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.naiwa.core.cv"
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
     * P1 起启用 OpenCV 4.10.0（仅 Maven Central）。
     * 用途：Lab 颜色空间迁移、seamlessClone 泊松融合、形态学与距离变换、拉普拉斯金字塔。
     * 加载方式：core-cv 内的 OpenCvRuntime 懒加载并缓存结果，
     * 失败时上层会整体降级到纯 Kotlin 路径（见 docs/ARCHITECTURE.md §9）。
     */
    implementation(libs.opencv)

    testImplementation(libs.junit)
}
