import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * 签名信息从 `local.properties` 读取（该文件在 .gitignore 里，**绝不进仓库**）。
 *
 * ⚠️ 这段必须放在 `plugins {}` **之后** —— Gradle Kotlin DSL 只允许
 * `buildscript {}` / `plugins {}` / import 出现在脚本最前面，插入别的东西会直接语法失败。
 *
 * 需要的四个键（缺任一视为"没有配置签名"，release 产出 unsigned 包）：
 * ```
 * RELEASE_STORE_FILE=/absolute/or/relative/to/root/release.jks
 * RELEASE_STORE_PASSWORD=***
 * RELEASE_KEY_ALIAS=dimension
 * RELEASE_KEY_PASSWORD=***
 * ```
 * 路径可写绝对路径，也可写相对**工程根目录**的路径（如 `keystore/release.jks`）。
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(key: String): String? =
    localProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStorePath: String? = localProp("RELEASE_STORE_FILE")

/**
 * 版本号可被环境变量覆盖（CI 打 tag 发布时用）：
 * `VERSION_NAME`（如 `1.0.1`，tag 去掉前导 v）、`VERSION_CODE`（单调递增整数）。
 * 不设时用仓库内的默认值，本地构建行为不变。
 *
 * ⚠️ versionCode 必须随每次发布递增，否则手机上无法覆盖安装（"应用未安装"）。
 */
val buildVersionName: String = System.getenv("VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
val buildVersionCode: Int = System.getenv("VERSION_CODE")?.trim()?.toIntOrNull() ?: 100

android {
    namespace = "com.naiwa.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naiwa.camera"
        minSdk = 26          // Android 8.0
        targetSdk = 34       // 需求指定 Android 14；如需上架 Play，后续可单独提升到 36
        versionCode = buildVersionCode
        versionName = buildVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 端侧 AI 全部走 ARM64；armeabi-v7a 为可选兼容（见 docs 降级矩阵）
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * 只在**四个键齐全**时才创建签名配置 ——
     * 否则 `assembleRelease` 会因为 `storeFile` 为 null 直接失败，
     * 而没配签名的协作者/CI 只想验证 R8 能否跑通（产出 unsigned 包）也会被卡住。
     */
    signingConfigs {
        val store = releaseStorePath
        if (store != null) {
            create("release") {
                storeFile = rootProject.file(store)
                storePassword = localProp("RELEASE_STORE_PASSWORD")
                keyAlias = localProp("RELEASE_KEY_ALIAS")
                keyPassword = localProp("RELEASE_KEY_PASSWORD")
                // minSdk = 26 → **v1(JAR) 签名没有意义**（它只服务 API 24 及以下的验证器），
                // 显式关掉：少一层签名块，也避免"v1 存在但没有被验证"的误解。
                // v2 覆盖 Android 7.0+，v3 覆盖 9.0+ 并支持密钥轮换。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没配签名时为 null → 产出 app-release-unsigned.apk（仍可验证 R8）
            signingConfig = signingConfigs.findByName("release")
            isDebuggable = false
            isJniDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // .tflite / .task 必须不压缩：LiteRT 通过 mmap 直接映射 assets，压缩后无法加载
        noCompress += listOf("tflite", "task", "onnx")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
        // 多模块各自携带 OpenCV 原生库时只保留一份，避免 APK 体积翻倍
        jniLibs.pickFirsts += setOf("**/libopencv_java4.so")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- 业务模块 ----
    // 注意：app 不直接依赖 :core-cv / :core-ai —— 只有 :core-pipeline 允许同时看到两者
    implementation(project(":core-common"))
    implementation(project(":core-pipeline"))
    implementation(project(":core-data"))
    implementation(project(":feature-editor"))

    // ---- Compose（版本由 BOM 统一管理）----
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ---- AndroidX ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // ---- DI ----
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ---- 测试 ----
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
