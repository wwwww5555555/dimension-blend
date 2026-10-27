package com.naiwa.core.ai

import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * 端侧推理运行时装配：负责**加速器探测与回退**，并把结果缓存下来。
 *
 * 探测顺序（规格 8.3，不可颠倒）：
 *  1. **Qualcomm QNN Delegate**（高通 NPU）—— 通过反射探测，见 [tryCreateQnnDelegate]；
 *  2. **LiteRT NPU Delegate**（通用 NPU）—— 通过反射探测；
 *  3. **GPU Delegate（OpenCL）** —— `CompatibilityList` 先问系统是否支持，再尝试创建；
 *  4. **CPU + XNNPACK（4 线程）** —— 最终兜底，任何设备都能跑。
 *
 * 【为什么 QNN / NPU 用反射】这两个 delegate 位于 Google Maven 的
 * `com.google.ai.edge.litert` 系列包，而当前依赖是 Maven Central 的
 * `org.tensorflow:tensorflow-lite`（同运行时、同 API）。用反射探测意味着：
 *  - 当前依赖下代码照常编译运行，QNN/NPU 探测自动返回 null、落到 GPU/CPU；
 *  - 将来加对应 litert-* 依赖后，**无需改本文件**即可启用 QNN/NPU 路径。
 * 这是"结构就位、真机验证依赖"的稳妥做法 —— 不在无驱动验证的情况下硬塞未测试的 delegate。
 *
 * 【禁止 NNAPI】NNAPI 自 Android 15 起已废弃，本文件**不引入**任何 NnApiDelegate 代码；
 * 已有 NNAPI 路径（如果有）逐步迁移到 LiteRT 的 CompiledModel API。
 *
 * 【缓存】探测结果按模型路径缓存，避免每张图重复初始化 delegate（探测本身有几十毫秒开销）。
 */
object RuntimeProvider {

    private const val TAG = "NaiwaCamera"

    /** 选中的加速器类型，写入日志便于判断某台设备实际走了哪条路。 */
    enum class Accelerator { QNN, NPU, GPU, CPU }

    /** 探测结果缓存：模型路径 → (Interpreter, Accelerator)。 */
    private val cache = HashMap<String, Pair<Interpreter, Accelerator>>()

    /**
     * 探测并创建 Interpreter（含加速器），结果缓存。
     *
     * @param modelFile 模型文件
     * @param maxAccelerator 允许探测到的最高加速器（默认 QNN，即全链尝试）。
     *   低端机可传 [Accelerator.CPU] 跳过所有 delegate。
     */
    fun createInterpreter(
        modelFile: File,
        maxAccelerator: Accelerator = Accelerator.QNN,
    ): Pair<Interpreter, Accelerator> {
        val key = modelFile.absolutePath
        cache[key]?.let { return it }

        val result = try {
            probe(modelFile, maxAccelerator)
        } catch (t: Throwable) {
            Log.w(TAG, "加速器探测异常，回退 CPU：${t.message}")
            createCpuInterpreter(modelFile) to Accelerator.CPU
        }
        cache[key] = result
        return result
    }

    /** 按顺序探测：QNN → NPU → GPU → CPU。 */
    private fun probe(modelFile: File, maxAccelerator: Accelerator): Pair<Interpreter, Accelerator> {
        val rank = mapOf(
            Accelerator.QNN to 0,
            Accelerator.NPU to 1,
            Accelerator.GPU to 2,
            Accelerator.CPU to 3,
        )
        val maxRank = rank[maxAccelerator] ?: 3

        if (maxRank <= 0) tryCreateQnnDelegate(modelFile)?.let { return it to Accelerator.QNN }
        if (maxRank <= 1) tryCreateNpuDelegate(modelFile)?.let { return it to Accelerator.NPU }
        if (maxRank <= 2) tryCreateGpuDelegate(modelFile)?.let { return it to Accelerator.GPU }
        return createCpuInterpreter(modelFile) to Accelerator.CPU
    }

    /**
     * 尝试创建 QNN Delegate（高通 NPU）。反射探测：类不存在时返回 null。
     *
     * LiteRT 的 QNN delegate 位于 `com.google.ai.edge.litert:litert-...` 系列，
     * 加依赖后反射即命中；当前依赖下自动跳过。
     */
    private fun tryCreateQnnDelegate(modelFile: File): Interpreter? {
        return try {
            val clazz = Class.forName("org.tensorflow.lite.qnn.QnnDelegate")
            val options = Class.forName("org.tensorflow.lite.qnn.QnnDelegate.Options").getConstructor().newInstance()
            val delegate = clazz.getConstructor(options.javaClass).newInstance(options)
            val interpreter = Interpreter(mapModel(modelFile), Interpreter.Options().apply {
                addDelegate(delegate as org.tensorflow.lite.Delegate)
            })
            Log.i(TAG, "推理运行时：QNN Delegate（高通 NPU）")
            interpreter
        } catch (t: Throwable) {
            // 类不存在 / 创建失败，静默落到下一级
            null
        }
    }

    /**
     * 尝试创建 LiteRT NPU Delegate（通用 NPU）。反射探测，同上。
     */
    private fun tryCreateNpuDelegate(modelFile: File): Interpreter? {
        return try {
            val clazz = Class.forName("org.tensorflow.lite.npu.NpuDelegate")
            val options = Class.forName("org.tensorflow.lite.npu.NpuDelegate.Options").getConstructor().newInstance()
            val delegate = clazz.getConstructor(options.javaClass).newInstance(options)
            val interpreter = Interpreter(mapModel(modelFile), Interpreter.Options().apply {
                addDelegate(delegate as org.tensorflow.lite.Delegate)
            })
            Log.i(TAG, "推理运行时：NPU Delegate")
            interpreter
        } catch (t: Throwable) {
            null
        }
    }

    /** 尝试创建 GPU Delegate（OpenCL）。 */
    private fun tryCreateGpuDelegate(modelFile: File): Interpreter? {
        var delegate: GpuDelegate? = null
        return try {
            val compatibility = CompatibilityList()
            if (!compatibility.isDelegateSupportedOnThisDevice) {
                Log.i(TAG, "本机不支持 GPU Delegate（CompatibilityList 判定），降级")
                return null
            }
            delegate = GpuDelegate()
            val interpreter = Interpreter(mapModel(modelFile), Interpreter.Options().apply {
                addDelegate(delegate)
            })
            Log.i(TAG, "推理运行时：GPU(OpenCL) Delegate")
            interpreter
        } catch (t: Throwable) {
            Log.w(TAG, "GPU Delegate 创建失败，降级：${t.message}")
            runCatching { delegate?.close() }
            null
        }
    }

    private fun createCpuInterpreter(modelFile: File): Interpreter {
        Log.i(TAG, "推理运行时：CPU + XNNPACK（$CPU_THREADS 线程）")
        return Interpreter(
            mapModel(modelFile),
            Interpreter.Options().apply {
                setNumThreads(CPU_THREADS)
                setUseXNNPACK(true)
            },
        )
    }

    /** 用 mmap 加载模型：避免把几 MB 模型整体读进堆，也避免 GC 压力。 */
    private fun mapModel(modelFile: File): java.nio.MappedByteBuffer =
        RandomAccessFile(modelFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }

    /** CPU 线程数。4 是移动端的常用折中。 */
    private const val CPU_THREADS = 4
}
