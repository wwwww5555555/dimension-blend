package com.naiwa.camera.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naiwa.camera.R

/**
 * 首页：唯一的入口是「从相册导入背景」。
 *
 * 设计约束（为什么长这样）：
 * - **只有一个主动作**。这是修图工具，首页不是营销页；两个按钮就会产生选择成本。
 * - **深色底 + 低饱和容器**：画布预览区必须是照片本身的颜色，UI 不能抢色。
 *   色值全部来自主题（#121212 背景 / surfaceContainer 逐级提亮），不写死。
 * - 不用图标库：这里只需要一个"加号"，用两条圆角矩形画出来即可，
 *   免得多引一个 material-icons 依赖（APK 体积 + 编译时间）。
 *
 * 导入成功后由调用方负责跳转编辑页（[onScenePicked]），首页不持有任何业务状态。
 */
@Composable
fun HomeScreen(
    onScenePicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // 用户取消时 uri 为 null —— 什么也不做，不要弹提示（取消是正常操作）
        if (uri != null) onScenePicked(uri)
    }

    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.app_name),
                color = colors.onBackground,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "DIMENSION BLEND",
                color = colors.primary,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_tagline),
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
            )

            Spacer(Modifier.weight(1f))

            ImportCard(onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            })

            Spacer(Modifier.weight(1f))

            StepRow()
            Spacer(Modifier.height(12.dp))
            Text(
                text = "全部处理在本机完成，图片不会离开设备",
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 中央的大按钮：整块可点，视觉上是一张"待放图"的画框。 */
@Composable
private fun ImportCard(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(232.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(colors.surfaceContainerHigh, colors.surfaceContainerLow),
                ),
            )
            .border(1.dp, colors.outlineVariant, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 加号：两个圆角矩形，不依赖图标库
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(colors.primary.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, colors.primary.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(colors.primary, RoundedCornerShape(1.dp)),
                )
                Box(
                    Modifier
                        .width(2.dp)
                        .height(22.dp)
                        .background(colors.primary, RoundedCornerShape(1.dp)),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.home_import_scene),
                color = colors.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_import_hint),
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

/** 三步提示：把"接下来会发生什么"一次说清，减少进编辑页后的困惑。 */
@Composable
private fun StepRow() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(
            stringResource(R.string.home_step_1),
            stringResource(R.string.home_step_2),
            stringResource(R.string.home_step_3),
        ).forEach { text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(colors.primary, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = text, color = colors.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}
