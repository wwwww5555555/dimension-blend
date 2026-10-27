pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // google()：AndroidX / Hilt / MediaPipe
        google()
        // mavenCentral()：OpenCV(org.opencv:opencv) 与 TFLite(org.tensorflow:tensorflow-lite) 在此
        mavenCentral()
        // wysaid 私有仓库：android-gpuimage-plus 只在此发布（Maven Central 没有）
        maven { url = uri("https://maven.wysaid.org/") }
    }
}

rootProject.name = "NaiwaCamera"

// ---- 应用层 ----
include(":app")

// ---- 基础能力层（无业务、无 UI）----
include(":core-common")   // 领域模型、管线接口、图像工具（零原生、零 UI）
include(":core-cv")       // OpenCV / 像素算法：颜色迁移、KelvinRgb、阴影、融合、边缘
include(":core-ai")       // 端侧推理：光照估计、抠图（传统 CV 兜底为纯 Kotlin）
include(":core-pipeline") // ★ 编排层：唯一同时引用 core-cv 与 core-ai 的模块
include(":core-render")   // ★ DAG 非破坏性编辑：工程图 + 纹理缓存 + 局部重算 + FBO 执行

// ---- 数据层 ----
include(":core-data")     // 奶蛙素材库(manifest.json)、MediaStore 保存、DataStore 设置

// ---- 特性层（UI + ViewModel，只依赖 ImagePipeline 接口）----
include(":feature-editor") // 素材导入 / 抠图涂抹 / 变换 / 预览 / 保存分享

