package com.naiwa.camera.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.naiwa.camera.ui.home.HomeScreen
import com.naiwa.feature.editor.EditorRoute

/**
 * 路由表。只有 :app 知道导航图，feature 模块只暴露 `XxxRoute` 入口函数。
 *
 * v1.0 起只有两个页面：**首页（相册导入）→ 编辑页**。
 * 拍照能力已整体移除（`:feature-camera` 模块、CameraX 依赖、CAMERA 权限一并删除），
 * 首页就是唯一入口。
 */
object Routes {
    const val HOME = "home"
    const val EDITOR_PATTERN = "editor/{sceneUri}"

    /** 场景参数名（与 `EditorViewModel.ARG_SCENE_URI` 必须一致）。 */
    const val ARG_SCENE_URI = "sceneUri"

    fun editor(sceneUri: String): String = "editor/${Uri.encode(sceneUri)}"
}

/**
 * 应用导航图：首页 → 编辑页。
 *
 * 编辑完成后 `popBackStack` 回首页（不保留编辑页，避免旧状态残留）。
 * 用 `launchSingleTop` 防止连点导入压出两个编辑页 —— 那会让返回键要按两次。
 */
@Composable
fun NaiwaNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onScenePicked = { uri ->
                    navController.navigate(Routes.editor(uri.toString())) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = Routes.EDITOR_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_SCENE_URI) { type = NavType.StringType }),
        ) {
            EditorRoute(onBack = { navController.popBackStack() })
        }
    }
}
