package com.naiwa.camera

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。
 *
 * 这里只做「必须尽早完成、且与 UI 无关」的初始化：
 *  - P1 起：预热光照估计模型（后台线程，失败静默降级到传统 CV）。
 *  - P1 起：OpenCVLoader.initLocal()（失败则整个 CV 链路走纯 Kotlin 实现）。
 * 绝不能在此做阻塞式大文件读取或模型加载，否则冷启动会超过 1s。
 */
@HiltAndroidApp
class NaiwaApp : Application()
