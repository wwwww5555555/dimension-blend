package com.naiwa.feature.editor

import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.naiwa.core.common.model.FrogAsset
import com.naiwa.core.common.model.LightOverrides
import com.naiwa.feature.editor.canvas.editorTransformGestures
import com.naiwa.feature.editor.ui.ColorAdjust
import com.naiwa.feature.editor.ui.EditorBottomSheetContent

/**
 * 编辑页入口（次元融合 · 沉浸式深色布局）。
 */
@Composable
fun EditorRoute(
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.saveImage() else {
            android.util.Log.w(EditorViewModel.TAG, "用户拒绝写外部存储权限")
        }
    }

    val frogPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.importFrog(it, null) } }

    LaunchedEffect(state.message, state.mattingNotice, state.didDegrade) {
        val degradeText = if (state.didDegrade) "光影计算失败，已降级为普通贴图" else null
        val text = state.mattingNotice ?: state.message ?: degradeText
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    EditorScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onGesture = viewModel::onGesture,
        onSelectMaterial = viewModel::selectMaterial,
        onRemoveMaterial = viewModel::removeMaterialFromList,
        onImportFrog = {
            frogPickLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onClearFrog = viewModel::clearForegroundMaterial,
        onLoadThumbnail = viewModel::loadThumbnail,
        onSave = {
            // 8.1：保存 = 纯 IO；仅旧版系统需要写权限
            if (viewModel.requiresLegacyWritePermission &&
                ContextCompat.checkSelfPermission(context, viewModel.legacyWritePermission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                writePermissionLauncher.launch(viewModel.legacyWritePermission)
            } else {
                viewModel.saveImage()
            }
        },
        onFuse = viewModel::triggerFullRender,
        onOverridesChange = viewModel::setOverrides,
        onOverridesCommit = viewModel::commitOverrides,
        onColorAdjustChange = viewModel::setColorAdjust,
        onColorAdjustCommit = viewModel::commitColorAdjust,
        onHarmonizeStrengthChange = viewModel::setHarmonizeStrength,
        onResetAll = { viewModel.setOverrides(LightOverrides.DEFAULT) },
        onSaveProject = viewModel::saveProject,
        onLoadProject = viewModel::loadLatestProject,
        onCycleWrapDebug = viewModel::cycleWrapDebugMode,
        onShare = {
            viewModel.buildShareIntent()?.let { intent ->
                context.startActivity(android.content.Intent.createChooser(intent, "分享成品"))
            }
        },
    )
}

/**
 * 沉浸式编辑页：全屏深色画布 + 半透明顶部图标 + 底部 ModalBottomSheet 抽屉。
 *
 * 状态表现分工（8.4 / 8.5）：
 *  - 拖动中：`ColorFilter.colorMatrix`（纯 Compose，零位图处理）保证跟手；
 *  - 松手：`commitOverrides` 触发 720p 快渲染，位图变化由 [Crossfade] 300ms 淡入；
 *  - 一键融合：全量渲染，[ShimmerOverlay] 扫光缓解等待焦虑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onGesture: (Float, Float, Float, Float, Int, Int) -> Unit,
    onSelectMaterial: (String) -> Unit,
    onRemoveMaterial: (String) -> Unit,
    onImportFrog: () -> Unit,
    onClearFrog: () -> Unit,
    onLoadThumbnail: suspend (MaterialItem) -> Bitmap?,
    onSave: () -> Unit,
    onFuse: () -> Unit,
    onOverridesChange: (LightOverrides) -> Unit,
    onOverridesCommit: () -> Unit,
    onColorAdjustChange: (ColorAdjust) -> Unit,
    onColorAdjustCommit: () -> Unit,
    onHarmonizeStrengthChange: (Float) -> Unit,
    onResetAll: () -> Unit,
    onShare: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
    onCycleWrapDebug: () -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var panelOpen by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val preview = state.previewBitmap ?: state.scenePreview
    val busy = state.isRendering || state.isFusing

    // 手势归一化的基准必须是**图片实际显示区域**（ContentScale.Fit 后），
    // 而不是画布尺寸：4:3 的图放在 16:9 的屏幕上，Fit 会留黑边，
    // 用画布尺寸归一化会让拖动位移换算偏小 —— 表现为"素材跟不上手 / 位置乱跳"。
    val displaySize = remember(canvasSize, preview) {
        val cw = canvasSize.width
        val ch = canvasSize.height
        if (preview == null || cw <= 0 || ch <= 0) canvasSize
        else {
            val s = minOf(cw.toFloat() / preview.width, ch.toFloat() / preview.height)
            IntSize(
                (preview.width * s).toInt().coerceAtLeast(1),
                (preview.height * s).toInt().coerceAtLeast(1),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {

        // ---------------- 画布：Crossfade 300ms + ColorMatrix 实时调色 ----------------
        if (preview != null) {
            Crossfade(
                targetState = preview,
                animationSpec = tween(300),
                label = "previewFade",
                modifier = Modifier.fillMaxSize(),
            ) { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "合成预览",
                    contentScale = ContentScale.Fit,
                    colorFilter = if (state.colorAdjust.isDefault) null
                    else ColorFilter.colorMatrix(state.colorAdjust.toColorMatrix()),
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                        .clickable(
                            // 点击画布呼出/隐藏底部抽屉（8.2）
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { panelOpen = !panelOpen }
                        .editorTransformGestures(enabled = state.canInteract) { panX, panY, zoom, rotation ->
                            // 用显示区域归一化：拖动距离与画面位移严格 1:1（见 displaySize 的注释）
                            onGesture(
                                panX, panY, zoom, rotation,
                                displaySize.width.coerceAtLeast(1),
                                displaySize.height.coerceAtLeast(1),
                            )
                        },
                )
            }
        } else {
            Text(
                text = if (state.isLoading) "正在准备…" else "没有可编辑的画面",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // ---------------- 渲染中的扫光（8.5） ----------------
        if (busy) ShimmerOverlay(modifier = Modifier.fillMaxSize())

        // ---------------- 调试面板：人物区域直方图 ----------------
        // 只在点「一键融合」后出现（拖动/松手不统计）。三秒能看出的信息：
        //   p1 高 → 黑场被抬起（阴影发灰）；p99 与 p95 相等 → 亮部被削平（高光死白）。
        val debugLines = buildList {
            if (state.wrapDebugMode > 0) {
                add("调试通道：${com.naiwa.core.common.debug.DebugReport.wrapModeLabel(state.wrapDebugMode)}")
            }
            val pipe = com.naiwa.core.common.debug.DebugReport.lastPipeline
            if (pipe.isNotEmpty()) {
                add("管线：" + if (pipe == "layer") "图层管线" else "旧 3-pass（降级）")
            }
            state.debugReport?.let { add(it) }
        }
        if (debugLines.isNotEmpty()) {
            Text(
                text = debugLines.joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = Color(0xFF9AE6B4),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        // ---------------- 顶部半透明图标（返回 / 素材 / 保存） ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIcon(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            GlassIcon(onClick = onImportFrog) {
                Icon(Icons.Default.Add, contentDescription = "导入立绘", tint = Color.White)
            }
            // 删除素材：只在该导入过人像之后出现。
            // 用 AnimatedVisibility 而不是 `if` 的原因：
            //   `if` 会让按钮"啪"地出现/消失，而它紧挨着"导入"按钮 ——
            //   突然多出一个按钮会让用户误点到旁边那个，横向展开 + 淡入给了眼睛一个提示。
            // 这里不做二次确认：素材是"重新选一张"就能恢复的东西，弹窗只会让清理变慢；
            // 删除结果由 Snackbar 明确回报（EditorViewModel.clearForegroundMaterial 里发消息）。
            AnimatedVisibility(
                visible = state.frogBitmap != null,
                enter = fadeIn(tween(180)) + expandHorizontally(tween(180), expandFrom = Alignment.Start),
                exit = fadeOut(tween(140)) + shrinkHorizontally(tween(140), shrinkTowards = Alignment.Start),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    GlassIcon(onClick = onClearFrog) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "清除人像素材",
                            // 红点只给图标，不给底座：整块红色会抢走"一键融合"的视线
                            tint = Color(0xFFFF6B6B),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp).padding(end = 6.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
            GlassIcon(onClick = onSave) {
                Icon(Icons.Default.Done, contentDescription = "保存", tint = Color.White)
            }
        }

        // ---------------- 一键融合（悬浮主按钮） ----------------
        Button(
            onClick = onFuse,
            // 没有人像时**不禁用**：按钮点得动才会走到 ViewModel 的拦截分支并提示
            // "请先导入人像素材"。禁用态说不出原因，用户只会以为界面坏了。
            enabled = state.scenePreview != null && !state.isFusing && !state.isExporting,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (panelOpen) 8.dp else 32.dp)
                .fillMaxWidth(0.62f),
            shape = RoundedCornerShape(28.dp),
        ) {
            if (state.isFusing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text("  正在计算光影…", fontWeight = FontWeight.SemiBold)
            } else {
                Text(
                    // 位置已改动 → 上一轮溶图结果失效，如实改成「重新融合」
                    text = if (state.needsReFuse) "✨ 重新融合（位置已调整）" else "✨ 一键融合",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        // ---------------- 底部抽屉（8.2） ----------------
        if (panelOpen) {
            ModalBottomSheet(
                onDismissRequest = { panelOpen = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                EditorBottomSheetContent(
                    overrides = state.overrides,
                    colorAdjust = state.colorAdjust,
                    harmonizeStrength = state.harmonizeStrength,
                    autoRimStrength = state.light.keyIntensity * 0.3f,
                    onOverridesChange = onOverridesChange,
                    onColorAdjustChange = onColorAdjustChange,
                    onCommit = {
                        onOverridesCommit()
                        onColorAdjustCommit()
                    },
                    onHarmonizeStrengthChange = onHarmonizeStrengthChange,
                    onResetAll = onResetAll,
                    onSaveProject = onSaveProject,
                    onLoadProject = onLoadProject,
                    wrapDebugMode = state.wrapDebugMode,
                    onCycleWrapDebug = onCycleWrapDebug,
                )
                // 素材快速入口（保留原有能力，避免产品功能回退）
                MaterialStrip(
                    state = state,
                    onSelectMaterial = onSelectMaterial,
                    onRemoveMaterial = onRemoveMaterial,
                    onImportFrog = onImportFrog,
                    onLoadThumbnail = onLoadThumbnail,
                )
            }
        }
    }
}

/** 半透明圆形图标按钮（顶部工具条用）。 */
@Composable
private fun GlassIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 渲染扫光：一条倾斜的白色渐变带循环掠过画面。
 * 只做视觉安抚（8.5），不参与任何计算。
 */
@Composable
private fun ShimmerOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                start = Offset(x * 1200f, 0f),
                end = Offset(x * 1200f + 320f, 600f),
            ),
        ),
    )
}

/**
 * 抽屉内的素材栏：横向缩略图 + 导入 + 逐项删除。
 *
 * ## 删除角标为什么要单独一层 Box
 *
 * 缩略图本身带 `clip(RoundedCornerShape(10.dp))`（圆角裁切）。角标如果画在它里面，
 * 会被同一个裁切切掉右上角 —— 表现为"×"只剩 3/4 个圆。所以：
 * ```
 * Box(56dp)                 ← 不裁切的容器
 *   ├─ Box(fillMaxSize().clip().border())   ← 缩略图（被裁切）
 *   └─ Box(align = TopEnd)                  ← 删除角标（在裁切之外）
 * ```
 *
 * ## 为什么角标要自己消费点击
 *
 * 外层 `Column` 上有"选中"的 clickable。Compose 的命中测试是**最内层优先**，
 * 角标自带 clickable 就会吃掉这次点击 —— 于是"点 ×"不会顺带选中它。
 * 少了这一层，用户会因为"删一个却先把别的选中了"而看到画布闪一下换人。
 */
@Composable
private fun MaterialStrip(
    state: EditorUiState,
    onSelectMaterial: (String) -> Unit,
    onRemoveMaterial: (String) -> Unit,
    onImportFrog: () -> Unit,
    onLoadThumbnail: suspend (MaterialItem) -> Bitmap?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("人像素材", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (state.materials.isEmpty()) "暂无素材" else "${state.materials.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onImportFrog) { Text("导入") }
            }
            if (state.materials.isEmpty()) {
                Text(
                    text = "还没有素材，点「导入」从相册选一张带透明背景的人像",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                return@Column
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.materials, key = { it.id }) { item ->
                    val selected = item.id == state.selectedMaterialId
                    Column(
                        modifier = Modifier.width(64.dp).clickable { onSelectMaterial(item.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        var thumb by remember(item.id) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(item.id) { thumb = onLoadThumbnail(item) }

                        Box(modifier = Modifier.size(56.dp)) {
                            // 缩略图：唯一带裁切与边框的一层
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                thumb?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = item.displayName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(4.dp),
                                    )
                                }
                            }

                            // 删除角标：在裁切之外，自己消费点击
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xE61A1A1A))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onRemoveMaterial(item.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "删除「${item.displayName}」",
                                    tint = Color(0xFFFF8A80),
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }

                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
