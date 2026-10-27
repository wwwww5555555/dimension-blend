import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :feature-editor
 * 编辑主流程：奶蛙素材选择 / 导入抠图（点选分割 + 手动涂抹）/ 拖动缩放旋转 /
 * 光照协调预览 / 融合导出 / 保存与分享。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.naiwa.feature.editor"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 只依赖接口与模型（:core-common）+ 数据来源（:core-data）。
    // 禁止依赖 :core-cv / :core-ai / :core-pipeline 的任何具体实现类。
    implementation(project(":core-common"))
    implementation(project(":core-data"))
    implementation(project(":core-render"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    // 顶部半透明图标（ArrowBack / Done）：icons-core 是基础图标集，约 200KB
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
