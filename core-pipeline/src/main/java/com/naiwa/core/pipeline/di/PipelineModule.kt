package com.naiwa.core.pipeline.di

import com.naiwa.core.common.pipeline.ImagePipeline
import com.naiwa.core.pipeline.DefaultImagePipeline
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 把 [ImagePipeline] 接口绑定到具体实现。
 *
 * 这个绑定是「feature 只依赖接口、不依赖实现」这条依赖纪律能够成立的**唯一支点**：
 * 编辑页通过 `@Inject constructor(pipeline: ImagePipeline)` 拿到实现，
 * 而它的编译期依赖里没有 `:core-cv` / `:core-ai` / `:core-pipeline` 中的任何一个具体类。
 *
 * P1/P2 接入 OpenCV 与泊松融合时，只改本模块内部的实现，绑定关系不变。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PipelineModule {

    @Binds
    @Singleton
    abstract fun bindImagePipeline(impl: DefaultImagePipeline): ImagePipeline
}
