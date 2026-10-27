package com.naiwa.core.common.debug

import android.util.Log

/**
 * 真机验证入口与配置（规格 8 的调试工具，非产品功能）。
 *
 * 作用：让"没有真机"的当前阶段就能把**所有需要真机验证的路径**预留好开关与日志入口，
 * 拿到真机后无需改代码即可逐项验证。集中在一个类里，避免验证点散落在各处。
 *
 * 需要真机验证的项（对应规格 8 的"降级与验证"）：
 *  - 8.1 3D LUT：CPU 三线性采样数值正确性（已有单测）、跨域融合观感；
 *  - 8.2 AI 和谐化：MKL 的观感、PCT-Net 的 NPU 推理 + 8s 超时回退；
 *  - 8.3 NPU 探测链：QNN→NPU→GPU→CPU 的实际命中与回退。
 */
object DebugConfig {

    private const val TAG = "NaiwaCamera.Debug"

    /**
     * 诊断模式开关。开启后，渲染管线各阶段会打详细日志（光效强度、LUT 命中、和谐化后端、
     * 加速器命中），便于真机逐项对照。通过 DataStore 或 adb 属性控制均可。
     */
    @Volatile
    var diagnosticLogging: Boolean = false

    /**
     * 强制启用 PCT-Net（绕过"仅高端机"的门槛）。
     *
     * 用途：在中端机上也能测试 PCT-Net 的 NPU 推理与超时回退，不必非要一台高端机。
     * 产品里恒为 false（高端机门槛是安全护栏）；真机调试时手动置 true。
     */
    @Volatile
    var forcePctNet: Boolean = false

    /**
     * 打印一次完整的验证清单（真机到手后，跑一次就知道要测哪些、怎么判通过）。
     */
    fun dumpVerificationChecklist() {
        val lines = listOf(
            "========== 奶蛙相机 真机验证清单（规格 8 跨域融合） ==========",
            "[8.1 3D LUT]",
            "  1. 编辑页状态行应显示「光效」与融合档位；导入二次元奶蛙 + 写实暖光室内背景。",
            "  2. 奶蛙应呈现暖色调（warm_indoor LUT 命中），不再高饱和贴纸感。",
            "  3. 换冷光夜景背景 → 奶蛙偏冷蓝（cool_night 命中）。",
            "  4. 逆光背景 + 强主光 → backlit LUT 命中，边缘光朝向光源一侧。",
            "  logcat: adb logcat -s NaiwaCamera | grep -E 'LUT|光效|和谐化'",
            "[8.2 MKL 和谐化]",
            "  5. 前景/背景色调差异大时，MKL 应让奶蛙色度向背景靠拢（协方差匹配）。",
            "  logcat: adb logcat -s NaiwaCamera | grep 'MKL'",
            "[8.2 PCT-Net（高端机 / 或 forcePctNet=true）]",
            "  6. 权重放到 filesDir/models/pct_net_vit.tflite 后，应打印「PCT-Net 和谐化（QNN/NPU/GPU）」。",
            "  7. 权重缺失时应打印「回退 MKL」，不崩溃。",
            "  8. 推理超 8s 应自动回退 MKL（超时保护）。",
            "  9. 注意：PCT-Net 官方权重是 PyTorch .pth 格式，需先转 LiteRT（见 DEVICE-VERIFICATION.md）。",
            "[8.3 NPU 探测链]",
            "  10. adb logcat -s NaiwaCamera | grep '推理运行时'，确认命中 QNN/NPU/GPU/CPU 哪一级。",
            "  11. 高通机型应命中 QNN，其他机型依次降级，最终 CPU+XNNPACK 兜底。",
            "[已排除]",
            "  - FLUX.2-klein-4B：需约 13GB VRAM，不满足端侧内存约束。",
            "  - DreamLite：权重需邮件申请 + 依赖文本编码器，流程繁琐（已换 PCT-Net）。",
            "================================================================",
        )
        lines.forEach { Log.i(TAG, it) }
    }

    /**
     * 打印一次"当前生效路径"快照：一次看清这台设备实际走了哪些路径。
     */
    fun dumpActivePaths(
        deviceTier: String,
        lutPreset: String?,
        harmonizerBackend: String?,
        accelerator: String?,
    ) {
        Log.i(
            TAG,
            "生效路径快照 → 设备=$deviceTier LUT=${lutPreset ?: "无"} " +
                "和谐化=${harmonizerBackend ?: "无"} 加速器=${accelerator ?: "未探测"}",
        )
    }
}
