package com.naiwa.camera.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naiwa.camera.ui.nav.NaiwaNavHost
import com.naiwa.camera.ui.theme.NaiwaCameraTheme

/**
 * 应用根：主题 + 导航（次元溶图 v1.0）。
 *
 * 这里刻意保持"什么都没有"：不注入任何状态、不做任何初始化。
 * 应用级初始化（模型预热、OpenCV 加载）放在 [com.naiwa.camera.NaiwaApp]，
 * 页面级状态放在各自的 ViewModel —— 根组件一旦有状态，就很难说清谁负责它。
 */
@Composable
fun NaiwaRoot() {
    NaiwaCameraTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            NaiwaNavHost()
        }
    }
}
