package com.naiwa.feature.editor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naiwa.core.common.device.DeviceCapability
import com.naiwa.core.common.device.DeviceCapabilityProbe
import com.naiwa.core.common.model.EditorSnapshot
import com.naiwa.core.common.model.FrogAsset
import com.naiwa.core.common.model.FusionBackend
import com.naiwa.core.common.model.FusionTier
import com.naiwa.core.common.model.FusionTierChoice
import com.naiwa.core.common.model.FusionTrack
import com.naiwa.core.common.model.LightOverrides
import com.naiwa.core.common.model.LightParams
import com.naiwa.core.common.debug.DebugReport
import com.naiwa.core.common.model.MatteQuality
import com.naiwa.core.common.model.project.Project
import com.naiwa.core.render.ProjectPresets
import com.naiwa.core.common.model.Placement
import com.naiwa.core.common.model.RenderMode
import com.naiwa.core.common.model.RenderRequest
import com.naiwa.core.common.model.RenderResult
import com.naiwa.core.common.pipeline.ImagePipeline
import com.naiwa.core.common.util.PlacementTransforms
import com.naiwa.core.common.util.UndoRedoStack
import com.naiwa.data.frog.BuiltInFrogRepository
import com.naiwa.data.frog.ImportedFrogRepository
import com.naiwa.data.media.MediaStoreSaver
import com.naiwa.data.settings.SettingsRepository
import com.naiwa.data.source.SceneSourceLoader
import com.naiwa.feature.editor.ui.ColorAdjust
import com.naiwa.feature.editor.ui.applyColorMatrixToPixels
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 编辑页状态。
 *
 * 三个位图字段对应三种工作分辨率，是性能与内存的核心设计（架构文档 §7.1）：
 *  - [scenePreview]：短边 1600，颜色/阴影/融合的基准
 *  - [sceneDrag]：短边 720，拖动时的实时预览基准（**只创建一次**，避免每帧重采样）
 *  - 导出时另行按需加载原图（最多 4096），不常驻内存
 */
data class EditorUiState(
    val isLoading: Boolean = true,
    val scenePreview: Bitmap? = null,
    val sceneDrag: Bitmap? = null,
    /**
     * 当前选中素材的 id；null = 画布上没有人像。
     *
     * 它是**唯一的选择事实来源**：`frogBitmap` 是它的物化结果（选了才加载），
     * `materials` 是它的候选集合。三者不并存第二份"选中"状态 ——
     * 否则"列表高亮"与"画布上有谁"会各说各话（这类不同步在编辑器里最难查）。
     */
    val selectedMaterialId: String? = null,
    /** 当前选中素材的全尺寸位图（未选中时为 null）。 */
    val frogBitmap: Bitmap? = null,
    val placement: Placement = Placement.DEFAULT,
    val light: LightParams = LightParams.NEUTRAL,
    val lightAnalyzed: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val isRendering: Boolean = false,
    val isExporting: Boolean = false,
    /**
     * 是否正在保存图片（8.1：与 [isRendering] 严格区分）。
     * 保存路径**只读当前预览位图**，绝不触发 Shader / AI / 渲染管线。
     */
    val isSaving: Boolean = false,
    /** 调色四件套（走 Compose ColorMatrix 实时预览，不进渲染管线）。 */
    val colorAdjust: ColorAdjust = ColorAdjust.DEFAULT,
    /**
     * 位置/参数已改动但尚未重新融合。
     *
     * 拖动手势会把画布切回"贴图态"（重算光影成本高，不可能每帧跑），
     * 此时**必须如实告知**用户"当前看到的不是溶图结果"，否则用户以为光影还在生效。
     */
    val needsReFuse: Boolean = false,
    /**
     * 是否正在执行「一键融图」（全量管线：光照 → PCT-Net → 阴影 → 泊松 → 边缘优化）。
     * 与 [isRendering] 的区别：它独占全屏、禁用所有输入、按钮变成"正在计算光影…"。
     */
    val isFusing: Boolean = false,
    /**
     * AI 和谐化的全局混合强度（0~1）。默认 0.8；色彩断层严重时自动降到 0.5。
     * 最终图 = (1 - strength) * 原像素 + strength * AI 像素。
     */
    val harmonizeStrength: Float = 0.8f,
    /** 本次融图是否发生了降级（AI 失败 → 传统 CV / 纯贴图），用于 UI 提示。 */
    val didDegrade: Boolean = false,
    val actualTierLabel: String = "",
    val lastRenderMs: Long = 0L,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /**
     * 素材栏列表（内置 + 导入，按"内置在前、导入按时间倒序"拼接）。
     *
     * 只存元数据，不存位图 —— 理由见 [MaterialItem] 的 KDoc。
     */
    val materials: List<MaterialItem> = emptyList(),
    val message: String? = null,
    val mattingNotice: String? = null,
    val savedUri: Uri? = null,
    /** P3 手动调节覆盖值（全 null = 全自动）。 */
    val overrides: LightOverrides = LightOverrides.DEFAULT,
    /** 是否展开手动调节面板。 */
    val showSliders: Boolean = false,
    /** 设备档位的可读描述（诊断行展示）。 */
    val deviceLabel: String = "",
    /** 当前设备是否允许开启细节保真（低端机为 false）。 */
    val repDetailAvailable: Boolean = false,
    /** 用户是否开启了细节保真（仅在 [repDetailAvailable] 为 true 时有意义）。 */
    val repDetailEnabled: Boolean = false,
    /** 最近一次渲染实际使用的融合后端（如实回报，可能与请求值不同）。 */
    val backendLabel: String = "",
    /** 最近一次渲染实际执行的高级光效（如实回报，如"逆光+边缘光+辉光"或"无光效"）。 */
    val fxLabel: String = "",
    /**
     * 人物区域直方图摘要（调试面板）。
     *
     * 只在**全量融合**后由 `:core-pipeline` 写入（拖动/松手预览不统计，否则预览会被拖慢）。
     * null = 本次没有统计数据（CPU 降级路径或统计被关闭）。
     */
    val debugReport: String? = null,
    /** 光包裹调试通道（0 = 正常出图；1~7 见 DebugReport.wrapModeLabel）。 */
    val wrapDebugMode: Int = 0,
    /**
     * 用户选择的融合档位；`null` = 自动。
     *
     * 注意它是**用户意图**，不等于实际执行值：设备能力可能降级（见 [FusionTierChoice]），
     * 实际值由 [actualTierLabel] 如实回报。两者分开存，是为了让"用户点了分块但机器跑的是 540p"
     * 这件事在界面上说得清楚，而不是把请求值当结果展示。
     */
    val tierPreference: FusionTier? = null,
) {
    val canInteract: Boolean get() = scenePreview != null && frogBitmap != null && !isExporting && !isFusing

    /** 当前选中项在列表里的位置；-1 = 没选中/已被删。 */
    val selectedIndex: Int get() = materials.indexOfFirst { it.id == selectedMaterialId }
}

/**
 * 编辑页 ViewModel —— P0 的主流程都在这里。
 *
 * 关键设计：
 *  1. **渲染请求合并（latest-wins）**：拖动会高频产生请求，直接堆协程会排队到天荒地老。
 *     这里用一个"待处理槽 + 单循环"模式，保证同一时刻只有一个渲染在跑，且总是渲染最新状态。
 *  2. **松手判定用防抖**：手势结束 120ms 后触发中间质量预览（SETTLE），再静默 1200ms 后触发
 *     接近成品的预览（FINAL_PREVIEW）。这就是架构文档 §7.1 的"预览三段式"。
 *  3. **撤销/重做记录的是"变更前"状态**，且只在防抖触发点记录一次 —— 否则拖一次手会塞进几十条历史。
 *  4. **不做位图手动回收**（预览类）：API 26+ 位图像素在 native 堆由 GC 管理，
 *     而预览位图可能正被 Compose 绘制，贸然 recycle 会直接崩溃。只在导出路径显式回收自己创建的中间产物。
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pipeline: ImagePipeline,
    private val sceneSourceLoader: SceneSourceLoader,
    private val builtInFrogRepository: BuiltInFrogRepository,
    private val importedFrogRepository: ImportedFrogRepository,
    private val mediaStoreSaver: MediaStoreSaver,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /**
     * 设备能力：**编辑页是全项目唯一做"双轨制选路"的地方**。
     * 管线只执行不判断，这样"UI 上显示的"与"实际跑的"不可能不一致。
     */
    private val capability: DeviceCapability = DeviceCapabilityProbe.detect(context)

    /** 用户对细节保真的开关；实际是否生效还要过设备档位这一关（见 [effectiveBackend]）。 */
    private var repDetailOptIn: Boolean = true

    /**
     * 本次渲染实际使用的后端。
     *
     * 规则：低端机无条件否决（见 [FusionTrack.backendFor]）；
     * 其余设备默认开启（"能上就上"），用户可在编辑页关掉。
     */
    private val effectiveBackend: FusionBackend
        get() = FusionTrack.backendFor(capability.tier, repDetailOptIn)

    /**
     * 真正传给管线的档位。
     *
     * 规则只在 [FusionTierChoice] 里写一次（纯函数、有单测）—— 管线只执行不判断，
     * 这样"界面上说的"与"实际跑的"不可能不一致。
     */
    private val requestedTier: FusionTier?
        get() = FusionTierChoice.resolve(_state.value.tierPreference, capability.tier).requested

    private val sceneUriString: String? = savedStateHandle.get<String>(ARG_SCENE_URI)

    /** 撤销/重做（深度 20，见 UndoRedoStack 的 KDoc）。 */
    private val history = UndoRedoStack<EditorSnapshot>(UndoRedoStack.DEFAULT_CAPACITY)

    /** 最后一次"已提交"的快照；与当前状态不同才记录历史。 */
    private var committedSnapshot = EditorSnapshot()

    private var renderJob: Job? = null
    private var pendingMode: RenderMode? = null
    private var settleJob: Job? = null
    private var finalPreviewJob: Job? = null
    private var sliderJob: Job? = null

    /** 当前「一键融图」任务。保存引用只为一件事：清空素材时能取消它（见 clearForegroundMaterial）。 */
    private var fuseJob: Job? = null

    /**
     * AI 推理互斥锁：保证同一时刻只有一个协程在跑 LiteRT Interpreter。
     * 光照估计与 PCT-Net 共用同一把锁 —— 它们可能先后被同一渲染链路触发，
     * 而 Interpreter 不是线程安全的，并发 run 会崩（这是闪退的根因之一）。
     */
    private val aiMutex = Mutex()

    /**
     * 一键融图任务的互斥锁：避免用户连点"一键融图"时并发跑多条全量管线。
     */
    private val fuseMutex = Mutex()

    /**
     * Debug 开关：为 true 时跳过 PCT-Net 与泊松融合，只做基础贴图。
     * 用于排查"失真到底来自 AI 还是融合"（见需求 §三.4）。
     */
    var debugSkipAI: Boolean = false

    /** 上一轮融合结果 Bitmap 的引用，重新生成前先回收它（内存保护）。 */
    private var fusedResult: Bitmap? = null

    /** 导出用的全分辨率场景，按需加载后缓存（避免每次保存都重新解码）。 */
    private var fullResScene: Bitmap? = null

    /** 场景 Uri（导出时重新按原分辨率解码同一个 Uri）。 */
    private var sceneUri: Uri? = null

    /** 供 UI 判断是否需要申请旧版写权限（API <= 28）。 */
    val requiresLegacyWritePermission: Boolean get() = mediaStoreSaver.requiresLegacyWritePermission
    val legacyWritePermission: String get() = mediaStoreSaver.legacyWritePermission

    init {
        _state.update {
            it.copy(
                deviceLabel = "${capability.tier.label}（RAM ${capability.ramLabel} / ${capability.cores} 核）",
                repDetailAvailable = capability.tier.supportsRepDetail,
                repDetailEnabled = capability.tier.supportsRepDetail && repDetailOptIn,
            )
        }
        viewModelScope.launch { loadScene() }
        viewModelScope.launch { loadFrogLibrary() }
    }

    // ---------------------------------------------------------------- 初始化

    private suspend fun loadScene() {
        val raw = sceneUriString
        if (raw.isNullOrBlank()) {
            _state.update { it.copy(isLoading = false, message = "没有收到场景图") }
            return
        }
        val uri = Uri.parse(raw)
        sceneUri = uri
        val preview = loadBitmap(uri, SceneSourceLoader.DEFAULT_MAX_SHORT_SIDE)
        if (preview == null) {
            _state.update { it.copy(isLoading = false, message = "这张图读不出来，可能已被删除") }
            return
        }

        // 拖动用小图只创建一次：每帧重采样一张 1600 的图会直接拖垮流畅度
        val drag = createDragScene(preview)

        _state.update {
            it.copy(isLoading = false, scenePreview = preview, sceneDrag = drag)
        }
        // 实时预览已关闭：初次进入只做纯贴图（DRAG），光照与融合等点「一键融图」
        requestRender(RenderMode.DRAG)

        // 光照估计只在这里做一次（进编辑页时），结果缓存，供「一键融图」使用
        val light = pipeline.estimateLight(preview)
        _state.update { it.copy(light = light, lightAnalyzed = light.isTrustworthy) }
    }

    private suspend fun loadFrogLibrary() {
        val builtIn = builtInFrogRepository.listAssets()
        val imported = importedFrogRepository.listImported()
        // 内置在前、导入在后（仓库已按新增时间倒序）—— 用户自己的素材离拇指更近
        val items = (builtIn + imported).map(MaterialItem::from)
        _state.update { it.copy(materials = items) }

        val settings = settingsRepository.settings.first()

        // 双轨制：读用户开关（缺省为开），但设备档位仍是硬门槛
        repDetailOptIn = settings.preferRepDetailFusion
        // 融合档位偏好（null = 自动）。历史字段 preferHighQualityFusion 已在仓库层折进这个值里，
        // 所以旧用户写过的"偏好高质量"不会因为新增档位选择器而失效。
        _state.update {
            it.copy(
                repDetailAvailable = capability.tier.supportsRepDetail,
                repDetailEnabled = capability.tier.supportsRepDetail && repDetailOptIn,
                tierPreference = settings.fusionTierPreference,
            )
        }

        // 优先恢复上次选中的素材，否则用第一个内置素材，再否则用第一个导入素材
        val preferredId = settings.lastFrogId
        val preferred = items.firstOrNull { it.id == preferredId }
            ?: items.firstOrNull { it.builtIn }
            ?: items.firstOrNull()
        preferred?.let { installMaterial(it) }
    }

    /**
     * 切换细节保真融合（双轨制的高配轨道）。
     *
     * 低端机上不提供这个开关（`repDetailAvailable = false`），所以这里再做一次防御：
     * 即便 UI 被误触或被改坏，也不会让低端机跑上实验路径。
     */
    fun toggleRepDetail() {
        if (!capability.tier.supportsRepDetail) {
            _state.update { it.copy(message = "当前设备档位不支持细节保真融合（低端机走稳定路径）") }
            return
        }
        val next = !repDetailOptIn
        repDetailOptIn = next
        _state.update { it.copy(repDetailEnabled = next) }
        viewModelScope.launch { settingsRepository.setPreferRepDetailFusion(next) }
        requestRender(RenderMode.DRAG)
    }

    /**
     * 选择融合档位（`null` = 自动）。
     *
     * 设备能力可能否决用户的选择（例如中端机选"全分辨率分块"）—— 此时**如实告知并说明改用了哪一档**，
     * 而不是悄悄换掉。理由和双轨制一致：界面上显示的东西必须与实际执行的东西一致。
     */
    fun setTierPreference(tier: FusionTier?) {
        val resolution = FusionTierChoice.resolve(tier, capability.tier)
        _state.update { it.copy(tierPreference = tier) }
        viewModelScope.launch { settingsRepository.setFusionTierPreference(tier) }
        resolution.downgradeNotice()?.let { notice ->
            _state.update { it.copy(message = notice) }
        }
        requestRender(RenderMode.DRAG)
    }

    // ---------------------------------------------------------------- 手势与摆放
    /** 手势增量 → 更新 Placement（不记录历史、不做渲染，渲染由防抖触发）。 */
    fun onGesture(panX: Float, panY: Float, zoom: Float, rotation: Float, viewWidth: Int, viewHeight: Int) {
        val current = _state.value
        if (!current.canInteract) return
        val updated = PlacementTransforms.applyGesture(
            current = current.placement,
            panX = panX,
            panY = panY,
            zoom = zoom,
            rotationDeg = rotation,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )
        _state.update { it.copy(placement = updated) }
        // 位置一变，上一轮溶图结果即失效（画布会被 DRAG 贴图覆盖）——如实标记，
        // 让按钮变成「重新融合」，避免用户以为光影仍在生效。
        if (fusedResult != null) _state.update { it.copy(needsReFuse = true) }
        requestRender(RenderMode.DRAG)
        scheduleSettle()
    }

    /** 防抖：手势停止 120ms 视为"松手"，只记录历史，**不再渲染 SETTLE**。 */
    private fun scheduleSettle() {
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(SETTLE_DEBOUNCE_MS)
            commitHistoryIfChanged()
            // 实时预览已关闭：松手后保持 DRAG 纯贴图，不跑颜色协调/阴影等光照计算。
            // 光照与融合只在用户显式点「一键融图」时单独计算（见 triggerFullRender）。
        }
    }

    private fun currentSnapshot(): EditorSnapshot = EditorSnapshot(
        placement = _state.value.placement,
        overrides = _state.value.overrides,
    )

    private fun commitHistoryIfChanged() {
        val snapshot = currentSnapshot()
        if (snapshot != committedSnapshot) {
            history.record(committedSnapshot)
            committedSnapshot = snapshot
            refreshHistoryFlags()
        }
    }

    private fun refreshHistoryFlags() {
        _state.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
    }

    fun undo() {
        val previous = history.undo(currentSnapshot()) ?: return
        applySnapshot(previous)
    }

    fun redo() {
        val next = history.redo(currentSnapshot()) ?: return
        applySnapshot(next)
    }

    private fun applySnapshot(snapshot: EditorSnapshot) {
        committedSnapshot = snapshot
        _state.update { it.copy(placement = snapshot.placement, overrides = snapshot.overrides) }
        refreshHistoryFlags()
        requestRender(RenderMode.DRAG)
    }

    // ---------------------------------------------------------------- P3 手动调节

    /**
     * 手动滑杆改动。
     *
     * 两段式反馈：**立即**用中间质量预览显示颜色/阴影变化（拖动时就有反馈），
     * 停止拖动 [SLIDER_DEBOUNCE_MS] 后再升级到含融合的成品预览、并记录一个撤销点。
     * 撤销点必须在防抖点记录：否则拖一次滑杆会塞进几十条历史，深度 20 的栈瞬间被冲掉。
     */
    /**
     * 滑杆拖动中（8.4）：**只更新 state，不渲染、不跑 Shader、不跑 AI**。
     * 画面的实时反馈由 EditorScreen 用 Compose `ColorFilter.colorMatrix` 承担（零位图处理），
     * 所以拖动跟手且不会拖垮内存。
     */
    fun setOverrides(newOverrides: LightOverrides) {
        if (newOverrides == _state.value.overrides) return
        _state.update { it.copy(overrides = newOverrides) }

        sliderJob?.cancel()
        sliderJob = viewModelScope.launch {
            delay(SLIDER_DEBOUNCE_MS)
            commitHistoryIfChanged()
        }
    }

    /** 滑杆松手（8.4）：同步进工程 + 触发一次 720p 低清快渲染；淡入由 UI 的 Crossfade 负责。 */
    fun commitOverrides() {
        commitHistoryIfChanged()
        syncProjectParams()   // 非破坏性：只改工程里的参数，不碰像素
        requestRender(RenderMode.SETTLE)
    }

    /** 调色参数拖动中：只更新 state（画面由 ColorMatrix 实时反映）。 */
    fun setColorAdjust(adjust: ColorAdjust) {
        if (adjust == _state.value.colorAdjust) return
        _state.update { it.copy(colorAdjust = adjust) }
    }

    /** 调色参数松手：矩阵类参数不需要重渲染（保存时烧入，见 [saveImage]），只记历史。 */
    fun commitColorAdjust() {
        _state.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
    }

    /** 画风保护度（规格 8.2 滑杆）：影响 AI 混合强度，松手后由 [commitOverrides] 统一重渲染。 */
    fun setHarmonizeStrength(strength: Float) {
        val v = strength.coerceIn(0f, 1f)
        if (v == _state.value.harmonizeStrength) return
        _state.update { it.copy(harmonizeStrength = v) }
    }

    /** 全部恢复自动（把覆盖值整体置空，而不是填某个默认数字）。 */
    fun resetOverrides() {
        if (_state.value.overrides.isDefault) return
        commitHistoryIfChanged()
        _state.update { it.copy(overrides = LightOverrides.DEFAULT) }
        requestRender(RenderMode.DRAG)
    }

    fun toggleSliders() {
        _state.update { it.copy(showSliders = !it.showSliders) }
    }

    /**
     * 位置复位：把奶蛙放回画面中央并恢复默认大小。
     * 这是一个独立操作（不是"撤销"），所以自己记录一次历史。
     */
    fun resetPlacement() {
        commitHistoryIfChanged()
        _state.update { it.copy(placement = Placement.DEFAULT) }
        requestRender(RenderMode.DRAG)
    }

    // ---------------------------------------------------------------- 素材清空

    /**
     * 清空当前人像素材（"选错了 / 想重来"的退路）。
     *
     * 四件事，**顺序不能换**：
     *  1. **掐掉在途渲染**。`requestRender` 是 latest-wins 循环、`performFullRender` 在
     *     后台线程跑；不先停它们，清空之后才完成的那一帧会把人物又贴回来
     *     （表现是"点了删除，人像自己回来了"，而且看不出是谁写的）。
     *  2. **状态重置**。`previewBitmap` 置 **null** 而不是塞 `scenePreview`：
     *     UI 的 `previewBitmap ?: scenePreview` 本来就会回落到纯背景，
     *     而塞同一个引用会让"这张能不能回收"的判断立刻变复杂（见 [retireBitmaps]）。
     *  3. **位置归零**。否则下一次导入的素材会出现在上一张的位置和缩放上。
     *  4. **延迟回收**。**不能在这里直接 recycle**，原因见 [retireBitmaps]。
     */
    fun clearForegroundMaterial() {
        val current = _state.value
        if (current.selectedMaterialId == null && current.frogBitmap == null) return

        pendingMode = null
        renderJob?.cancel()
        settleJob?.cancel()
        finalPreviewJob?.cancel()
        sliderJob?.cancel()
        // 融合协程必须一并取消：它跑在 Dispatchers.Default 上、还会落一张带人物的结果。
        // 注意 cancel 只是"要求它停"，管线内部不做取消检查，所以 commitRenderResult
        // 里还有一道"结果到达时素材是否已被清空"的兜底（见那里）。
        fuseJob?.cancel()

        val oldFrog = current.frogBitmap
        val oldPreview = current.previewBitmap
        val oldFused = fusedResult
        fusedResult = null

        _state.update {
            it.copy(
                selectedMaterialId = null,
                frogBitmap = null,
                placement = Placement.DEFAULT,
                previewBitmap = null,
                needsReFuse = false,
                isFusing = false,
                isRendering = false,
                message = "已清除人像素材",
            )
        }

        // 历史里只存 placement / overrides，撤销它等于"在空画布上恢复一个坐标"，没有意义；
        // 直接清空，避免用户按撤销又看到一连串什么都不发生的步骤。
        history.clear()
        committedSnapshot = currentSnapshot()
        refreshHistoryFlags()

        retireBitmaps(oldFrog, oldPreview, oldFused)
    }

    /** 等待回收的位图（同一对象只登记一次）。 */
    private val pendingRecycle = ArrayList<Bitmap>(4)

    /**
     * 延迟回收位图。
     *
     * ⚠️ **绝不能在这里立即 `recycle()`**。编辑页用 `Crossfade(300ms)` 切换预览位图，
     * 刚被换下来的那张在这 300ms 内仍然每帧被绘制；对正在绘制的 Bitmap 调 recycle
     * 会抛 `Canvas: trying to use a recycled bitmap`，直接把编辑器崩掉。
     * （这也是本文件顶部注释"不做位图手动回收"的例外：只有**确定已经离开绘制**的位图才回收。）
     *
     * 与场景图同一实例的位图会被跳过：`scenePreview` / `sceneDrag` 是全程共用的，
     * 回收它们等于把背景也删了 —— 而且 `previewBitmap` 在某些路径下可能正好指向同一对象。
     */
    private fun retireBitmaps(vararg candidates: Bitmap?) {
        val scene = _state.value.scenePreview
        val drag = _state.value.sceneDrag
        candidates.forEach { bmp ->
            if (bmp == null || bmp.isRecycled) return@forEach
            if (bmp === scene || bmp === drag) return@forEach
            if (pendingRecycle.any { it === bmp }) return@forEach
            pendingRecycle += bmp
        }
        if (pendingRecycle.isEmpty()) return

        viewModelScope.launch {
            delay(PREVIEW_RETIRE_MS)          // ① 先让 Crossfade 把旧帧画完
            if (!awaitPipelineIdle()) {       // ② 再等"正在读这张位图"的渲染退出
                // 宁可交给 GC（native 堆会随 Bitmap 对象回收），也不能在别人读的时候释放内存
                Log.w(TAG, "回收超时：仍有渲染未结束，本批位图留给 GC")
                pendingRecycle.clear()
                return@launch
            }
            val doomed = pendingRecycle.toList()
            pendingRecycle.clear()
            doomed.forEach { bmp ->
                // runCatching + isRecycled：回收失败（被别处抢先释放）绝不能让 UI 崩
                runCatching { if (!bmp.isRecycled) bmp.recycle() }
            }
        }
    }

    /**
     * 等"可能正在读位图"的任务真正结束。
     *
     * `Job.cancel()` 只是**请求**取消 —— 本工程的渲染管线内部没有取消检查点，一张已经在算的图
     * 会继续跑完。如果在它读着 `frogBitmap` 的时候 recycle，native 层会读到已释放内存
     * （SIGSEGV，不是 Java 异常，抓不到 Kotlin 堆栈）。所以回收前必须确认两件事：
     *  ① 预览渲染（[renderJob]）已结束 —— cancel 后它会在下一个挂起点退出；
     *  ② 全量融图没有在跑 —— `fuseMutex` 在整段渲染期间被持有，拿一次锁就能证明。
     *
     * 超时返回 false：调用方**跳过回收**。泄漏一张位图是可恢复的，野指针崩溃不是。
     */
    private suspend fun awaitPipelineIdle(timeoutMs: Long = PIPELINE_IDLE_TIMEOUT_MS): Boolean {
        val rendersDone = runCatching { renderJob?.join() }.isSuccess
        val fuseDone = withTimeoutOrNull(timeoutMs) { fuseMutex.withLock { } } != null
        if (!rendersDone || !fuseDone) Log.w(TAG, "管线未在 ${timeoutMs}ms 内空闲")
        return rendersDone && fuseDone
    }

    // ---------------------------------------------------------------- 素材

    /**
     * 供素材条加载缩略图（128px）。
     * 缩略图解码走仓库而不是在 Composable 里直接读 assets —— 素材的存储位置是数据层的知识，
     * UI 不该知道"内置素材在 assets、导入素材在 filesDir"。
     *
     * 缩略图由 UI 持有（`remember(item.id)`），这里**不缓存也不回收**：
     * 列表项本身不存位图，缩略图的生命周期完全跟着组合走。
     */
    suspend fun loadThumbnail(item: MaterialItem, size: Int = THUMBNAIL_SHORT_SIDE): Bitmap? =
        if (item.builtIn) {
            builtInFrogRepository.loadFrogBitmap(item.id, size)
        } else {
            importedFrogRepository.loadBitmap(item.id, size)
        }

    /** 选中列表中的某个素材（点了就换，重复点当前项无副作用）。 */
    fun selectMaterial(id: String) {
        if (_state.value.selectedMaterialId == id) return
        val item = _state.value.materials.firstOrNull { it.id == id }
        if (item == null) {
            _state.update { it.copy(message = "这个素材已不在列表里") }
            return
        }
        viewModelScope.launch { installMaterial(item) }
    }

    /**
     * 把某个素材装进画布：解码 → 边缘预处理 → 更新状态 → 重渲染。
     *
     * 三条不变式：
     *  1. **旧的 `frogBitmap` 必须延迟回收**。它可能正被在途渲染读取（管线不检查取消点），
     *     立即 recycle 会让 native 层读到已释放内存 → 直接 SIGSEGV，不是 Java 异常。
     *  2. 边缘预处理**每个素材只做一次**（alpha 柔化不幂等，重复做会把边缘越磨越虚）。
     *  3. 选中的 id 与位图必须在**同一次** `update` 里落地，否则 UI 会出现
     *     "列表高亮已换、画布还是上一个人"的中间帧。
     */
    private suspend fun installMaterial(item: MaterialItem) {
        val loaded = if (item.builtIn) {
            builtInFrogRepository.loadFrogBitmap(item.id)
        } else {
            importedFrogRepository.loadBitmap(item.id)
        }
        if (loaded == null) {
            _state.update { it.copy(message = "这个素材加载失败，换一个试试") }
            return
        }

        // 边缘预处理（规格 6.1~6.4）：**每个素材只做一次**。
        //  - 导入素材：导入时已处理并写了索引版本号 → 这里跳过（旧版本导入的会补做一次）
        //  - 内置素材：没有索引可写，用会话内集合兜住"同一素材被反复选中"
        val bitmap = if (needsEdgePrep(item.id, item.builtIn)) {
            val prepared = pipeline.prepareFrog(loaded, runMattingIfNeeded = false)
            if (prepared != null) {
                markEdgePrepared(item.id, item.builtIn)
                // loaded 是刚从仓库解码出来的位图，预处理产出了新位图后它就没用了
                if (prepared.bitmap !== loaded) loaded.recycle()
                prepared.bitmap
            } else {
                loaded
            }
        } else {
            loaded
        }

        val previous = _state.value.frogBitmap
        _state.update {
            it.copy(selectedMaterialId = item.id, frogBitmap = bitmap, mattingNotice = null)
        }
        settingsRepository.setLastFrogId(item.id)
        requestRender(RenderMode.DRAG)

        // 换下来那张：可能仍被本次或上一次渲染持有 → 走延迟回收（见 retireBitmaps）
        if (previous != null && previous !== bitmap) retireBitmaps(previous)
    }

    /** 本次会话内已做过边缘预处理的素材（内置素材没有索引可写，用它兜住幂等性）。 */
    private val edgePreparedInSession = HashSet<String>()

    private suspend fun needsEdgePrep(id: String, builtIn: Boolean): Boolean {
        if (id in edgePreparedInSession) return false
        // 内置素材没有索引记录，按"需要处理"处理（会话集合保证只处理一次）
        if (builtIn) return true
        return importedFrogRepository.needsEdgePrep(id)
    }

    private suspend fun markEdgePrepared(id: String, builtIn: Boolean) {
        edgePreparedInSession += id
        if (!builtIn) importedFrogRepository.markEdgePrepared(id)
    }

    /**
     * **从素材列表里彻底删除**（不是"清空画布"）。
     *
     * 与 [clearForegroundMaterial] 的区别：
     *  - `clearForegroundMaterial`：只把画布上的人拿掉，素材仍留在列表里；
     *  - 本方法：从列表移除 + 删文件（导入素材）+ 若删的正是当前选中则自动接续。
     *
     * ## 自动接续规则：优先选**被删项的前一个**
     *
     * 删除之后用户的视线仍在刚才那个位置，选中前一个（视觉上是"它左边那个"）
     * 比跳到第一个更符合直觉。删的是第一项时退回新的第一项。
     *
     * ## 为什么先更新列表、再异步做后面的事
     *
     * 删文件和加载下一个素材都是 IO/解码（几十到几百 ms）。列表如果等它们做完再更新，
     * 用户会看到"点了叉号，缩略图还在那儿杵着" —— 这是最典型的"点了没反应"观感。
     * 所以：**列表立刻消失 → 后台删文件 → 选中接续**。
     */
    fun removeMaterialFromList(id: String) {
        val current = _state.value
        val index = current.materials.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = current.materials[index]
        val wasSelected = current.selectedMaterialId == id
        val remaining = current.materials.filterNot { it.id == id }

        // ① 列表立即更新；如果删的是选中项，同时把"选中"置空（不能让 selectedMaterialId
        //    短暂指向一个已不存在的 id —— 那会让列表高亮与画布 owner 不一致）
        _state.update {
            it.copy(
                materials = remaining,
                selectedMaterialId = if (wasSelected) null else it.selectedMaterialId,
                message = "已删除「${item.displayName}」",
            )
        }

        viewModelScope.launch {
            // ② 落盘删除：只有导入素材有文件；内置素材只是从本次会话的列表里移除
            if (!item.builtIn) {
                val ok = importedFrogRepository.delete(item.id)
                if (!ok) Log.w(TAG, "素材文件删除失败（索引已清）：$id")
            }

            if (!wasSelected) return@launch

            // ③ 删的正是当前选中 → 接续 or 清空
            if (remaining.isEmpty()) {
                // 列表空了：清画布 + 位置归零 + 取消在途渲染 + 回收那张位图
                clearForegroundMaterial()
                return@launch
            }
            val next = remaining.getOrNull(index - 1) ?: remaining.first()
            installMaterial(next)
        }
    }

    /**
     * 从相册导入奶蛙图。
     *
     * 抠图 + **边缘预处理（规格 6.1~6.4）** 都由 [ImagePipeline.prepareFrog] 完成
     * —— feature 模块不引用任何算法实现类（依赖纪律第 4 条）。
     */
    fun importFrog(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = null) }
            val raw = sceneSourceLoader.loadFromUri(uri, FROG_IMPORT_SHORT_SIDE)
            if (raw == null) {
                _state.update { it.copy(isLoading = false, message = "这张图读不出来") }
                return@launch
            }

            // 柔度来自当前场景的光照参数（只影响 6.3 的柔化半径，范围 0.3~1.5px）
            val prepared = pipeline.prepareFrog(raw, softness = _state.value.light.shadowSoftness)
            if (prepared == null) {
                _state.update { it.copy(isLoading = false, message = "没能从这张图里识别出奶蛙") }
                return@launch
            }

            val asset = importedFrogRepository.save(
                bitmap = prepared.bitmap,
                displayName = displayName ?: "导入素材",
                hasAlpha = prepared.quality != MatteQuality.MATTED_SUSPICIOUS,
            )
            if (asset == null) {
                _state.update { it.copy(isLoading = false, message = "素材保存失败，存储空间可能不足") }
                return@launch
            }
            // 导入路径已经做过边缘预处理，登记版本号，避免选中时再柔化一次（柔化不幂等）
            markEdgePrepared(asset.id, builtIn = false)

            val edgeNote = if (prepared.edgePrepApplied) "，已做边缘去白与预乘校正" else ""
            val notice = when {
                prepared.quality == MatteQuality.ALREADY_HAS_ALPHA ->
                    "这张图自带透明通道，已直接使用$edgeNote"
                prepared.quality == MatteQuality.MATTED_OK ->
                    "已自动抠图（前景占比 ${(prepared.foregroundRatio * 100).toInt()}%）$edgeNote"
                else ->
                    "自动抠图效果可能不理想（前景占比 ${(prepared.foregroundRatio * 100).toInt()}%），" +
                        "建议换一张背景更干净或自带透明通道的图"
            }

            // 新素材插到"导入区"的最前面（用户刚选的应该在最显眼的位置）
            val newItem = MaterialItem.from(asset)
            _state.update { state ->
                val builtInItems = state.materials.filter { it.builtIn }
                val importedItems = state.materials.filterNot { it.builtIn }
                state.copy(
                    materials = builtInItems + listOf(newItem) + importedItems,
                    isLoading = false,
                    mattingNotice = notice,
                )
            }
            installMaterial(newItem)
        }
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 请求渲染（latest-wins 合并）。
     *
     * 不用 `job.cancel()` 的原因：管线的计算段不检查取消点，取消只能放弃结果、
     * 停不下已经开始的 CPU 计算；用一个待处理槽反而能让"总是渲染最新状态"这件事变确定。
     */
    private fun requestRender(mode: RenderMode) {
        // 无条件"最新覆盖"：拖动时用户要的是即时反馈，任何排队等待都会让手感变差。
        // 中间质量/成品预览会在防抖后重新请求，所以不会被永久挤掉。
        pendingMode = mode
        if (renderJob?.isActive == true) return
        renderJob = viewModelScope.launch {
            while (true) {
                val next = pendingMode ?: break
                pendingMode = null
                renderOnce(next)
            }
        }
    }

    private suspend fun renderOnce(mode: RenderMode) {
        // 融合进行中不跑预览渲染：两条链路都写 previewBitmap，后完成者会覆盖前者 ——
        // 表现为"融合完成后画面又跳回贴图/旧位置"。融合结果必须是最终态。
        if (_state.value.isFusing) return

        val current = _state.value
        val frog = current.frogBitmap ?: return
        val scene = when (mode) {
            RenderMode.DRAG -> current.sceneDrag
            RenderMode.SETTLE, RenderMode.FINAL_PREVIEW -> current.scenePreview
            // 导出走独立路径（save()），这里不给 EXPORT 提供 1600 的降级源，避免误用低清图导出
            RenderMode.EXPORT -> return
        } ?: return

        _state.update { it.copy(isRendering = true) }

        val request = RenderRequest(
            scene = scene,
            frog = frog,
            placement = current.placement,
            light = current.light,
            overrides = current.overrides,
            mode = mode,
            requestedBackend = effectiveBackend,
            requestedTier = requestedTier,
        )

        val result = try {
            pipeline.render(request)
        } catch (t: Throwable) {
            Log.w(TAG, "渲染失败: ${t.message}", t)
            _state.update { it.copy(isRendering = false, message = "预览渲染失败：${t.message}") }
            return
        }

        // 清空素材的竞态：管线内部不检查取消点，可能在 cancel 之后仍然跑完。
        // 这时结果里还带着人物，绝不能落进 previewBitmap —— 那正是"删了又自己回来"。
        if (_state.value.frogBitmap == null) {
            Log.i(TAG, "预览渲染结果到达时素材已被清空，丢弃该帧")
            runCatching { if (!result.bitmap.isRecycled) result.bitmap.recycle() }
            _state.update { it.copy(isRendering = false) }
            return
        }

        _state.update {
            it.copy(
                previewBitmap = result.bitmap,
                isRendering = false,
                lastRenderMs = result.totalMs,
                actualTierLabel = result.tierLabel,
                backendLabel = result.backend.label,
                fxLabel = result.fxPlan?.summary ?: "",
            )
        }
    }

    /** 触发一次高质量预览（用户点"渲染效果"或界面空闲时调用）。 */
    fun requestFinalPreview() {
        requestRender(RenderMode.FINAL_PREVIEW)
    }

    /**
     * 「一键融合」（次元融合版）：三级降级，任何一级失败都不会让预览停在错误状态。
     *
     *  ① GPU 主路径（[ImagePipeline.renderGpu]）：GLES 3.0 全 shader 管线 ——
     *     绿幕色键 → Gamma 线性 → 接触阴影(Multiply) → Alpha 叠加 → Rim(Screen) →
     *     Light Wrap(Screen) → 反弹光(Screen) → 高频重注入 → sRGB。
     *     不持 [aiMutex]（不触碰 LiteRT，GPU 与推理互不阻塞）。
     *  ② CPU 全量（[ImagePipeline.render] FINAL_PREVIEW）：持 [aiMutex] 串行推理
     *     （光照估计 + PCT-Net/MKL 和谐化），GPU 不可用/报错时走这里。
     *  ③ 最终兜底：DRAG 纯直贴（Canvas α 合成），绝不闪退。
     *
     * [fuseMutex] 保证同一时刻只有一条全量管线（防连点并发）。
     */
    fun triggerFullRender() {
        val current = _state.value
        // 拦截空渲染：没有人像时按钮仍可点（禁用态说不出原因，是可发现性最差的做法），
        // 由这里给出明确提示。清空素材后第一次点它走的就是这条分支。
        if (current.frogBitmap == null) {
            _state.update { it.copy(message = "请先导入人像素材") }
            return
        }
        if (!current.canInteract) return
        if (current.isFusing) return

        fuseJob = viewModelScope.launch {
            fuseMutex.withLock {
                // 先掐掉排队中的预览渲染，否则它可能在融合之后完成并覆盖结果
                pendingMode = null
                renderJob?.cancel()
                _state.update { it.copy(isFusing = true, didDegrade = false) }
                try {
                    withContext(Dispatchers.Default) {
                        performFullRender()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "一键融合彻底失败，降级为普通贴图：${t.message}", t)
                    // ③ 最终兜底：只做 Canvas 直贴
                    requestRender(RenderMode.DRAG)
                    _state.update { it.copy(isFusing = false, didDegrade = true) }
                }
            }
        }
    }

    /** 全量渲染：GPU 主路径 → CPU 全量（串行推理）的两级尝试。 */
    private suspend fun performFullRender() {
        val current = _state.value
        val frog = current.frogBitmap ?: return
        val scene = current.scenePreview ?: return

        // 内存保护：长边 > 2048 先降采样（GPU 纹理与 CPU 路径共用此保护）
        val workScene = downsampleIfOversized(scene, FULL_RENDER_MAX_SHORT_SIDE)

        val request = RenderRequest(
            scene = workScene,
            frog = frog,
            placement = current.placement,
            light = current.light,
            overrides = current.overrides,
            mode = RenderMode.FINAL_PREVIEW,
            requestedBackend = effectiveBackend,
            requestedTier = requestedTier,
            debugSkipAI = debugSkipAI,
            harmonizeStrength = current.harmonizeStrength,
        )

        // ① GPU 主路径：不持 aiMutex（GPU 不触碰 LiteRT），任何失败返回 null
        val gpuResult = if (debugSkipAI) null else {
            try {
                pipeline.renderGpu(request)
            } catch (t: Throwable) {
                Log.w(TAG, "GPU 融合异常，降级 CPU：${t.message}", t)
                null
            }
        }
        if (gpuResult != null) {
            commitRenderResult(gpuResult, gpuBackend = true)
            return
        }

        // ② CPU 全量：串行推理（aiMutex 防并发崩溃）；AI 再挂则跳过 AI 只做传统 CV
        _state.update { it.copy(didDegrade = true) }   // 走到这 = GPU 已失败，如实标记降级
        val result = try {
            aiMutex.withLock { pipeline.render(request) }
        } catch (t: Throwable) {
            Log.w(TAG, "CPU 渲染失败，降级为传统 CV + 贴图：${t.message}", t)
            aiMutex.withLock {
                pipeline.render(request.copy(debugSkipAI = true, requestedBackend = FusionBackend.CLASSICAL))
            }
        }
        commitRenderResult(result.bitmap, gpuBackend = false)
    }

    /** 提交渲染结果：回收上一轮融合缓存 + 过饱和检测（自动降混合强度）+ 状态更新。 */
    private fun commitRenderResult(resultBitmap: Bitmap, gpuBackend: Boolean) {
        val current = _state.value

        // 清空素材的竞态兜底：clearForegroundMaterial 会 cancel fuseJob，但管线内部
        // 不检查取消点，一张已经在算的图仍可能算完。若此时素材已为空，
        // 这张结果就是"清空之前的世界"，落进 state 会让删除看起来没生效。
        if (current.frogBitmap == null) {
            Log.i(TAG, "融合结果到达时素材已被清空，丢弃该结果")
            runCatching { if (!resultBitmap.isRecycled) resultBitmap.recycle() }
            _state.update { it.copy(isFusing = false, isRendering = false) }
            return
        }

        // 内存保护：回收上一次融合结果
        val old = fusedResult
        fusedResult = resultBitmap
        if (old != null && old !== fusedResult) old.recycle()

        // 色彩断层检测：过饱和占比超阈值 → 混合强度自动降 0.5（GPU 路径不做此降级）
        // 基准用**场景原图**（与结果同坐标系/同比例），不能用素材图 —— 素材是几百 px，
        // 结果是一千多 px，尺寸校验会直接跳过检测，导致该机制永远不生效。
        val reference = current.scenePreview ?: current.frogBitmap
        // reference 不可能是 null：上面的 guard 已经保证 frogBitmap != null，
        // 而 scenePreview 只会更早存在。省掉一次恒真判断（Kotlin 也会因此少一条告警）。
        val nextStrength = if (!gpuBackend && current.harmonizeStrength > 0.5f &&
            detectOversaturation(resultBitmap, reference)
        ) {
            Log.w(TAG, "检测到色彩断层（过饱和），混合强度 ${current.harmonizeStrength} → 0.5")
            0.5f
        } else {
            current.harmonizeStrength
        }

        _state.update {
            it.copy(
                previewBitmap = resultBitmap,
                isFusing = false,
                harmonizeStrength = nextStrength,
                backendLabel = if (gpuBackend) "GPU Shader" else it.backendLabel,
                didDegrade = false,
                needsReFuse = false,   // 融合结果已刷新，标记清除
                // 调试面板：全量融合后才有数据（GPU 路径统计，CPU 降级路径保持上一次）
                debugReport = if (gpuBackend) DebugReport.personSummary else it.debugReport
            )
        }
    }

    /**
     * 过饱和检测：若融合结果里过饱和像素（任一通道 ≥ 250）占比超过阈值，判定为色彩断层。
     * 这是"自动降混合强度"的真实信号来源，比看后端类型可靠。
     */
    private fun detectOversaturation(fused: Bitmap, original: Bitmap): Boolean {
        if (fused.width != original.width || fused.height != original.height) return false
        val w = fused.width
        val h = fused.height
        val fp = IntArray(w * h)
        fused.getPixels(fp, 0, w, 0, 0, w, h)
        val op = IntArray(w * h)
        original.getPixels(op, 0, w, 0, 0, w, h)

        var oversat = 0
        var sampled = 0
        for (i in fp.indices) {
            val a = (fp[i] ushr 24) and 0xFF
            if (a == 0) continue
            sampled++
            val fr = (fp[i] ushr 16) and 0xFF
            val fg = (fp[i] ushr 8) and 0xFF
            val fb = fp[i] and 0xFF
            val or = (op[i] ushr 16) and 0xFF
            val og = (op[i] ushr 8) and 0xFF
            val ob = op[i] and 0xFF
            // 融合后任一通道打到近满，而原图该通道远未满 → 过饱和
            val clamped = (fr >= 250 || fg >= 250 || fb >= 250) &&
                (or < 200 || og < 200 || ob < 200)
            if (clamped) oversat++
        }
        if (sampled == 0) return false
        return oversat.toFloat() / sampled > OVER_SATURATION_RATIO
    }

    /** 长边超过 [maxShortSide] 时等比降采样（保持宽高比，不拉伸）。 */
    private fun downsampleIfOversized(source: Bitmap, maxShortSide: Int): Bitmap {
        val shortSide = minOf(source.width, source.height)
        if (shortSide <= maxShortSide) return source
        val ratio = maxShortSide.toFloat() / shortSide
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    // ---------------------------------------------------------------- 工程（非破坏性编辑）

    /**
     * 当前工程（DAG 非破坏性编辑的唯一事实来源）。
     *
     * UI 的每次操作都应表达为"改这里的一个参数"；渲染引擎按需重算。
     * 本轮渲染主路径仍是已验证的 GPU 管线，DAG 执行器（`:core-render`）已就绪，
     * 切换需真机对拍后启用（见交付说明）。
     */
    private var projectState: Project? = null

    /** 工程文件目录（filesDir/projects）。 */
    private fun projectDir(): java.io.File =
        java.io.File(context.filesDir, "projects").apply { mkdirs() }

    /** 初始化 / 重建默认工程（P1+P2 图层树）。 */
    fun ensureProject() {
        if (projectState != null) return
        projectState = ProjectPresets.fusionProject(
            id = "proj_${System.currentTimeMillis()}",
            backgroundUri = sceneUri?.toString() ?: "",
            foregroundUri = "",
            rimStrength = _state.value.overrides.rimStrength
                ?: (0.3f * _state.value.light.keyIntensity),
            shadowStrength = _state.value.overrides.shadowStrength
                ?: _state.value.light.shadowStrength,
        )
    }

    /** 保存工程（JSON 到 filesDir/projects/<id>.json），与分辨率无关，几 KB。 */
    fun saveProject() {
        ensureProject()
        val p = projectState ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(projectDir(), "${p.id}.json")
                file.writeText(p.toJson())
                _state.update { it.copy(message = "工程已保存：${file.name}") }
            } catch (t: Throwable) {
                Log.w(TAG, "保存工程失败：${t.message}", t)
                _state.update { it.copy(message = "保存工程失败：${t.message}") }
            }
        }
    }

    /** 加载最近保存的工程；损坏文件不会崩溃（解析返回 null → 提示）。 */
    fun loadLatestProject() {
        viewModelScope.launch(Dispatchers.IO) {
            val latest = projectDir().listFiles { f -> f.extension == "json" }
                ?.maxByOrNull { it.lastModified() }
            if (latest == null) {
                _state.update { it.copy(message = "还没有保存过工程") }
                return@launch
            }
            val text = runCatching { latest.readText() }.getOrNull()
            val loaded = text?.let { Project.fromJson(it) }
            if (loaded == null) {
                _state.update { it.copy(message = "工程文件损坏，无法加载") }
                return@launch
            }
            projectState = loaded
            // 把工程里的边缘光/阴影参数同步回 UI 滑杆（工程是事实来源）
            val rim = loaded.node(ProjectPresets.P2_MAIN)?.float("strength", 0.35f)
            val shadow = loaded.node(ProjectPresets.P2C)?.float("opacity", 0.5f)
            _state.update {
                it.copy(
                    overrides = it.overrides.copy(rimStrength = rim, shadowStrength = shadow),
                    message = "已加载工程：${latest.name}",
                    needsReFuse = true,
                )
            }
        }
    }

    /** 滑杆参数同步进工程（drag 结束时调用，保持 DAG 与 UI 一致）。 */
    private fun syncProjectParams() {
        val p = projectState ?: return
        val s = _state.value
        var updated = p
        s.overrides.rimStrength?.let {
            updated = updated.withParam(ProjectPresets.P2_MAIN, "strength", it.toString())
        }
        s.overrides.shadowStrength?.let {
            updated = updated.withParam(ProjectPresets.P2C, "opacity", it.toString())
        }
        projectState = updated
    }

    /**
     * 切换光包裹调试通道（0 → 1 → … → 7 → 0），并立刻重渲染一次。
     *
     * 调试通道走的是**真实管线**（不是另画一张图）：P2-w 输出中间量 → 合成直通 →
     * 后处理整段跳过 → FXAA 不做 tonemap/gamma，所以屏幕上的灰度就是变量的值。
     */
    fun cycleWrapDebugMode() {
        val next = DebugReport.cycleWrapMode()
        _state.update { it.copy(wrapDebugMode = next) }
        if (next == 0) _state.update { it.copy(debugReport = null) }
        triggerFullRender()
    }

    // ---------------------------------------------------------------- 保存（纯 IO）

    /**
     * 保存当前画布（8.1：**严禁重跑 Shader / AI / 渲染管线**）。
     *
     * 行为：取 [EditorUiState.previewBitmap]（没有则退到场景原图）→ 烧入调色矩阵 →
     * 写相册。整条链路只有一次 O(n) 矩阵运算 + 一次 IO，没有渲染、没有融合、没有推理，
     * 因此无论当前是"贴图态"还是"已溶图态"，点击即保存、不卡顿。
     *
     * 与旧 [save] 的区别：旧实现会以 [RenderMode.EXPORT] 重新跑整条管线（几秒），
     * 那属于"渲染"而不是"保存"，已从 UI 入口移除。
     */
    fun saveImage() {
        val current = _state.value
        val base = current.previewBitmap ?: current.scenePreview
        if (base == null) {
            _state.update { it.copy(message = "还没有可保存的画面") }
            return
        }
        if (current.isSaving) return

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                val output = withContext(Dispatchers.Default) {
                    val w = base.width
                    val h = base.height
                    val px = IntArray(w * h)
                    base.getPixels(px, 0, w, 0, 0, w, h)
                    val adjusted = applyColorMatrixToPixels(px, current.colorAdjust.toColorMatrix())
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                        setPixels(adjusted, 0, w, 0, 0, w, h)
                    }
                }
                val saved = mediaStoreSaver.saveToGallery(output)
                output.recycle()
                if (saved == null) {
                    _state.update {
                        it.copy(isSaving = false, message = "保存失败：请检查存储权限（Android 9 及以下需要）")
                    }
                } else {
                    settingsRepository.setLastSaveFolder(MediaStoreSaver.ALBUM_NAME)
                    _state.update {
                        it.copy(isSaving = false, savedUri = saved.uri, message = "已保存到相册")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "保存失败：${t.message}", t)
                _state.update { it.copy(isSaving = false, message = "保存失败：${t.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- 导出与分享（旧路径，保留供显式导出）

    /**
     * 保存到系统相册。
     * 导出使用**原分辨率**场景（按需重新解码），而不是预览用的 1600 版本。
     */
    fun save() {
        viewModelScope.launch {
            val current = _state.value
            val frog = current.frogBitmap
            val uri = sceneUri
            if (frog == null || uri == null) {
                _state.update { it.copy(message = "还没有可保存的画面") }
                return@launch
            }

            _state.update { it.copy(isExporting = true, message = null) }

            val full = fullResScene ?: loadBitmap(uri, MAX_EXPORT_SHORT_SIDE)?.also { fullResScene = it }
            if (full == null) {
                _state.update { it.copy(isExporting = false, message = "原图读取失败，无法导出") }
                return@launch
            }

            val result = try {
                pipeline.render(
                    RenderRequest(
                        scene = full,
                        frog = frog,
                        placement = current.placement,
                        light = current.light,
                        overrides = current.overrides,
                        mode = RenderMode.EXPORT,
                        requestedBackend = effectiveBackend,
                        requestedTier = requestedTier,
                    ),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "导出渲染失败: ${t.message}", t)
                _state.update { it.copy(isExporting = false, message = "导出失败：${t.message}") }
                return@launch
            }

            val saveResult = mediaStoreSaver.saveToGallery(result.bitmap)
            // 导出产物由本方法创建且不再被 UI 使用，可以安全回收
            result.bitmap.recycle()

            if (saveResult == null) {
                _state.update {
                    it.copy(
                        isExporting = false,
                        message = "保存失败：请检查是否授予了存储权限（Android 9 及以下需要）",
                    )
                }
            } else {
                settingsRepository.setLastSaveFolder(MediaStoreSaver.ALBUM_NAME)
                _state.update {
                    it.copy(
                        isExporting = false,
                        savedUri = saveResult.uri,
                        message = "已保存到相册 / ${MediaStoreSaver.ALBUM_NAME}（${result.totalMs} ms）",
                    )
                }
            }
        }
    }

    /** 分享刚保存的成品。 */
    fun buildShareIntent(): Intent? {
        val uri = _state.value.savedUri ?: return null
        return mediaStoreSaver.buildShareIntent(uri, text = "我用奶蛙相机做的")
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null, mattingNotice = null, savedUri = null) }
    }

    // ---------------------------------------------------------------- 工具

    private suspend fun loadBitmap(uri: Uri, maxShortSide: Int): Bitmap? =
        if (uri.scheme == "file") {
            uri.path?.let { sceneSourceLoader.loadFromFile(java.io.File(it), maxShortSide) }
        } else {
            sceneSourceLoader.loadFromUri(uri, maxShortSide)
        }

    /** 创建拖动专用的低清场景；没必要缩放时直接复用同一实例（省一次拷贝）。 */
    private fun createDragScene(source: Bitmap): Bitmap {
        val shortSide = minOf(source.width, source.height)
        if (shortSide <= RenderMode.DRAG.maxShortSide) return source
        val ratio = RenderMode.DRAG.maxShortSide.toFloat() / shortSide
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    override fun onCleared() {
        fullResScene?.recycle()
        fullResScene = null
        fusedResult?.recycle()
        fusedResult = null
        super.onCleared()
    }

    companion object {
        const val TAG = "NaiwaCamera"
        const val ARG_SCENE_URI = "sceneUri"

        /** 手势停止多久算"松手"。太短会在双指调整时抖动，太长会让中间预览迟迟不出来。 */
        private const val SETTLE_DEBOUNCE_MS = 120L

        /** 静默多久升级到"接近成品"预览。 */
        private const val FINAL_PREVIEW_DELAY_MS = 1200L

        /** 滑杆停止拖动多久算一次操作完成（用于记录撤销点 + 升级到成品预览）。 */
        private const val SLIDER_DEBOUNCE_MS = 300L

        /** 导入素材的短边上限：素材规范是 512~1024，超过无收益。 */
        private const val FROG_IMPORT_SHORT_SIDE = 1024

        /** 导出的场景解码上限（大图保护 §7.3）。 */
        private const val MAX_EXPORT_SHORT_SIDE = 4096

        /** 素材条缩略图解码尺寸。 */
        private const val THUMBNAIL_SHORT_SIDE = 128

        /** 全量渲染（一键融图）前的工作场景长边上限：超过先降采样，防 OOM。 */
        private const val FULL_RENDER_MAX_SHORT_SIDE = 2048

        /** 过饱和像素占比超过此阈值即判定为色彩断层，触发混合强度自动降 0.5。 */
        private const val OVER_SATURATION_RATIO = 0.15f

        /**
         * 位图从"离开 state"到"真正 recycle"之间的等待时间。
         *
         * 必须大于编辑页的 `Crossfade(300ms)`：淡出期间旧位图仍在被绘制，
         * 提前回收会让 Compose 在下一帧抛 "trying to use a recycled bitmap"。
         * 600ms = 300ms 动画 + 一帧余量 + 慢设备的抖动空间。
         */
        private const val PREVIEW_RETIRE_MS = 600L

        /**
         * 等待渲染管线空闲的上限。
         *
         * 4K 导出路径的预算上限是 12s，但回收只发生在"用户点了删除/换素材"之后，
         * 此时通常没有导出在跑；5s 足够覆盖一次 1080p 全量融合（预算 5s）。
         * 超时就不回收 —— 见 [awaitPipelineIdle]。
         */
        private const val PIPELINE_IDLE_TIMEOUT_MS = 5_000L
    }
}

/**
 * 供 UI 使用的渲染档位标签。
 *
 * 直接转发 [FusionTier.label] —— 标签定义在枚举里，新增档位时忘记补文案会**编译不过**
 * （`when` 不再穷举），而不是在界面上显示一片空白。
 */
internal val RenderResult.tierLabel: String get() = actualTier.label
