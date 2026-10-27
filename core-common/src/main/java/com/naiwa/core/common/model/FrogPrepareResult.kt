package com.naiwa.core.common.model

import android.graphics.Bitmap

/**
 * 奶蛙素材准备结果 —— 「把用户给的任意一张图变成可融合的奶蛙」这一步的产物。
 *
 * 它把"抠图有没有成功"这件事变成**显式输出**，而不是让调用方去猜：
 * 导入的图可能本来就有透明通道（无需处理），也可能抠得很干净，也可能抠出一坨可疑结果
 * （例如把整张图判成前景）。UI 依据 [quality] 决定要不要提示用户"这张图建议手动修一下"。
 *
 * @param bitmap 带 alpha 的奶蛙位图（新建的，调用方负责回收）
 * @param didRunMatting 是否真的跑过抠图（导入图自带透明通道时为 false）
 * @param foregroundRatio 抠图后的前景占比（0~1）；未抠图时为 0
 * @param edgePrepApplied 是否执行了边缘预处理（6.1~6.4）。**false 不代表失败**，
 *   也可能是因为图像尺寸不合法而按契约跳过
 * @param edgePrepSummary 边缘预处理的单行摘要，直接用于日志与诊断行
 */
data class FrogPrepareResult(
    val bitmap: Bitmap,
    val didRunMatting: Boolean,
    val foregroundRatio: Float,
    val quality: MatteQuality,
    val edgePrepApplied: Boolean = false,
    val edgePrepSummary: String? = null,
) {
    /**
     * 可用性判断。
     * 仅 [MatteQuality.MATTED_SUSPICIOUS] 需要提示用户手动修（P2 会提供点选分割 + 涂抹）。
     */
    val isUsable: Boolean get() = quality != MatteQuality.MATTED_SUSPICIOUS
}

/**
 * 抠图质量。判据是前景占比 —— 太高说明背景没抠掉，太低说明主体被抠没了。
 *
 * - [ALREADY_HAS_ALPHA]：原图自带透明通道，直接可用
 * - [MATTED_OK]：色键抠图成功，前景占比落在合理区间
 * - [MATTED_SUSPICIOUS]：前景占比 < 5% 或 > 95%，结果可疑，建议人工修
 */
enum class MatteQuality {
    ALREADY_HAS_ALPHA,
    MATTED_OK,
    MATTED_SUSPICIOUS,
    ;

    companion object {
        /** 前景占比的合理区间：下限 5%（主体至少该有这么点），上限 95%（高于此说明背景没被抠掉）。 */
        const val MIN_REASONABLE_RATIO = 0.05f
        const val MAX_REASONABLE_RATIO = 0.95f

        fun of(ratio: Float): MatteQuality =
            if (ratio in MIN_REASONABLE_RATIO..MAX_REASONABLE_RATIO) MATTED_OK else MATTED_SUSPICIOUS
    }
}
