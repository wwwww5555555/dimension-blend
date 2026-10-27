import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core-pipeline
 * 图像管线的【编排层】：唯一同时依赖 :core-cv 与 :core-ai 的模块。
 * 对外只暴露 :core-common 里的 ImagePipeline 接口实现（通过 Hilt 注入），
 * 因此 feature 模块在编译期不需要知道实现存在于哪个模块。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.naiwa.core.pipeline"
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
    // 编排层：可以同时看到 cv 与 ai（这是本模块存在的唯一理由）
    implementation(project(":core-common"))
    implementation(project(":core-cv"))
    implementation(project(":core-ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
