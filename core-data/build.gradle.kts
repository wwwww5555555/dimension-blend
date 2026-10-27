import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core-data —— 数据来源统一出口
//   - 内置奶蛙素材库：assets/frogs 目录下的透明 PNG 与 manifest.json（授权门控）
//   - 用户导入的奶蛙图（应用私有目录）
//   - MediaStore 保存 / 相册读取
//   - DataStore 用户设置
//
// 【踩坑记录】本文件的注释一律用行注释。
// 若在块注释里写出"斜杠 + 星号"这两个字符（例如用通配符描述路径），
// Kotlin 会把它识别为【嵌套块注释的开始】。后续如果没有对应的收尾符号，
// plugins 块会被整段当成注释吞掉 —— 症状是"该模块一个 variant 都没有、构建却不报错"，
// 排查成本极高。见 docs/ARCHITECTURE-CHANGES.md 的记录。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.naiwa.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    implementation(project(":core-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    // Android 的 org.json 在 JVM 单测里是桩实现，用真实实现替换才能测 manifest.json 解析
    testImplementation(libs.json)
}
