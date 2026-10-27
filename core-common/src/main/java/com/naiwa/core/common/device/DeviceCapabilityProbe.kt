package com.naiwa.core.common.device

import android.app.ActivityManager
import android.content.Context
import com.naiwa.core.common.model.DeviceCapabilityTier
import com.naiwa.core.common.model.FusionTrack

/**
 * 设备能力探测 —— 全项目**唯一**读取硬件信息的地方。
 *
 * 为什么要抽出来：档位会影响"双轨制"选路、低端机降级、融合档位初值三处行为。
 * 如果每处各读一次 `ActivityManager`，只要有一处判断口径不同（比如一处看核数、
 * 一处看 isLowRamDevice），就会出现"UI 说可用、管线说不行"的矛盾状态。
 *
 * 判定规则本身在 [FusionTrack.tierFor]（纯函数，可单测）；本类只负责**取数**。
 */
data class DeviceCapability(
    /** 总物理内存（字节）；取不到为 0 */
    val totalRamBytes: Long,
    /** 可用核心数 */
    val cores: Int,
    /** 厂商 low-ram 标记 */
    val lowRamFlag: Boolean,
) {
    /** 计算出的档位。 */
    val tier: DeviceCapabilityTier get() = FusionTrack.tierFor(totalRamBytes, cores, lowRamFlag)

    /** 内存的易读描述，用于诊断行/日志。 */
    val ramLabel: String
        get() = if (totalRamBytes > 0) {
            String.format(java.util.Locale.US, "%.1fGB", totalRamBytes / 1024.0 / 1024 / 1024)
        } else {
            "未知"
        }
}

object DeviceCapabilityProbe {

    /**
     * 探测当前设备能力。
     *
     * **不抛异常**：任何取数失败都退化成"未知"（RAM = 0 → 按能力不足处理），
     * 因为"用不了实验特性"远好过"因为拿不到内存信息而崩溃"。
     */
    fun detect(context: Context): DeviceCapability {
        val cores = try {
            Runtime.getRuntime().availableProcessors()
        } catch (t: Throwable) {
            0
        }

        val am = try {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        } catch (t: Throwable) {
            null
        }

        val totalRam = try {
            if (am == null) {
                0L
            } else {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                info.totalMem
            }
        } catch (t: Throwable) {
            0L
        }

        val lowRam = try {
            am?.isLowRamDevice == true
        } catch (t: Throwable) {
            false
        }

        return DeviceCapability(totalRamBytes = totalRam, cores = cores, lowRamFlag = lowRam)
    }
}
