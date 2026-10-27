package com.naiwa.core.common.debug

/**
 * 渲染调试报告槽（进程内单例）。
 *
 * 为什么放在 `:core-common`：**采样算法在 `:core-cv`（需要 Bitmap），展示在 `:feature-editor`**，
 * 而 feature-editor 只依赖 core-common / core-data / core-render —— 两个模块之间没有直接依赖边。
 * 用一个极薄的纯数据槽传递字符串，比为了调试新增模块依赖更干净。
 *
 * 只在**全量融合**（点「一键融合」）后写入，拖动/松手预览**不写** —— 那两条路径每帧都跑，
 * 做直方图统计会把预览拖慢。
 */
object DebugReport {

    /**
     * 是否启用统计。
     *
     * 关掉时：不做任何像素统计（零开销），槽位保持上一次内容。
     * 这里默认 `true`，因为这轮就是为定位"泛白/发平"而加的测量；稳定后改成 false 即可。
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * 光包裹调试通道（0 = 正常）。
     *
     * 放在这里而不是 RenderRequest：它是一个**纯调试开关**，不应该进入渲染契约
     * （否则 DAG/工程文件/测试都要跟着改），管线在构造 Params 时读一次即可。
     * 取值含义见 `GlesFusionRenderer.Params.debugWrapMode`。
     */
    @Volatile
    var wrapMode: Int = 0

    /**
     * 最近一帧实际由谁渲染：`"layer"` = 新图层管线，`"legacy"` = 降级到旧 3-pass。
     *
     * 这个字段的价值：**shader 编译失败时降级是静默的**，画面看起来"什么效果都没有"，
     * 很容易被误判成"参数没生效"。有了它，一眼就能区分"效果太弱"和"根本没跑"。
     */
    @Volatile
    var lastPipeline: String = ""

    /**
     * 回读方向的**实测**结论（null = 尚未探测）。
     *
     * `false` = 回读缓冲区首行就是位图首行，直接 copy 即可；
     * `true`  = 驱动行序与规范推导相反，已自动翻转。
     *
     * 为什么值得占一个字段：Y 轴翻转是"要么全对要么全错"的问题，一旦现场出现
     * 上下颠倒，先看这一位就能区分「探测判错了」和「某个渲染器绕过了 GlReadback」。
     */
    @Volatile
    var readbackFlip: Boolean? = null

    /**
     * 通道名（UI 按钮上显示）。
     *
     * v0.26 起 5 与 7 的语义变了（旧版都在看"向外画的那圈"）：
     * - 5 = **向内渗透带** —— 它必须只出现在人物内侧，出现在轮廓外就是又退化成描边了；
     * - 7 = **发光源图** —— 只应有迎光侧轮廓在亮，全周均匀说明迎光门控失效；
     * - 8 = **泛光金字塔** —— 从轮廓向外柔和衰减，出现方块台阶说明 tent 上采样有问题。
     */
    fun wrapModeLabel(mode: Int): String = when (mode) {
        0 -> "关闭"
        1 -> "法线"
        2 -> "主光/环境权重"
        3 -> "双色光"
        4 -> "高度掩码"
        5 -> "向内渗透带"
        6 -> "阴影遮挡"
        7 -> "发光源图"
        8 -> "泛光金字塔"
        else -> "关闭"
    }

    /** 循环切换（0..8）。 */
    fun cycleWrapMode(): Int {
        wrapMode = (wrapMode + 1) % 9
        return wrapMode
    }

    /** 最近一次人物区域直方图摘要（多行文本，直接可贴到日志/屏幕）。 */
    @Volatile
    var personHistogram: String? = null
        private set

    /**
     * 预格式化的一行结论，供 UI 顶部小面板显示（比完整报告短）。
     * 例：`person p1=3 p50=96 p95=232 p99=238 gap=6 ✓`
     */
    @Volatile
    var personSummary: String? = null
        private set

    fun publish(histogram: String, summary: String) {
        personHistogram = histogram
        personSummary = summary
    }

    fun clear() {
        personHistogram = null
        personSummary = null
    }
}
