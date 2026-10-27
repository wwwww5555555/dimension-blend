package com.naiwa.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.naiwa.camera.ui.NaiwaRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * 「次元溶图」唯一 Activity（Compose 单 Activity 架构）。
 *
 * v1.0 起不再有拍照：应用启动直接进首页（相册导入背景），
 * 因此这里**不需要任何运行时权限**，也没有启动弹窗。
 *
 * 状态全部在各页 ViewModel 里，Activity 只负责挂 Compose 内容 ——
 * 它一旦持有状态，配置变更与进程重建就很难说清谁负责恢复。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用是**强制深色**（见 ui/theme/Theme.kt），但 enableEdgeToEdge() 的默认策略
        // 是"跟随系统深浅色"。系统处于浅色模式时，它会要求**深色**状态栏图标 ——
        // 落在深色画布上就是完全看不见。这里显式指定 dark（= 浅色图标）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            NaiwaRoot()
        }
    }
}
