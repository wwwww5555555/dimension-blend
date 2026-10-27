package com.naiwa.core.cv.fusion

import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **结构重参数化的核心断言：折叠前后逐点一致。**
 *
 * 这是 MobileFusion 那类方法的立身之本 —— 如果折叠引入了误差，
 * "训练时多分支、推理时单路径"就不成立，整条路线都站不住。
 *
 * 测试刻意用**确定性伪随机权重**（而非全零或全同）：
 * 全零权重下任何实现都能对上，测不出漏折叠某个分支的错误。
 */
class RepConvTest {

    private fun input(channels: Int, height: Int, width: Int): FloatArray {
        // 确定性"图像"：包含梯度、纹理与平坦区，避免只有同质输入掩盖边界处理错误
        val a = FloatArray(channels * height * width)
        var idx = 0
        for (c in 0 until channels) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val v = when (c % 3) {
                        0 -> (x - width / 2) / width.toFloat()
                        1 -> (y - height / 2) / height.toFloat()
                        else -> if (((x / 3 + y / 3) and 1) == 0) 0.8f else -0.6f
                    }
                    a[idx++] = v
                }
            }
        }
        return a
    }

    @Test
    fun `多分支与折叠单路径输出一致`() {
        val rc = RepConv(
            inChannels = 3,
            midChannels = 2,
            outChannels = 2,
            branchKernels = RepBlock.DEFAULT_BRANCHES,
        )
        rc.initDeterministic(seed = 20261016L, scale = 0.3f)

        val h = 17
        val w = 13
        val x = input(3, h, w)

        val multi = rc.forwardMultiBranch(x, h, w)

        val folded = rc.fold()
        assertEquals(5, folded.kernel)
        val single = folded.forward(x, h, w)

        assertEquals(multi.size, single.size)
        var maxDiff = 0f
        var maxMag = 0f
        for (i in multi.indices) {
            maxDiff = max(maxDiff, abs(multi[i] - single[i]))
            maxMag = max(maxMag, abs(multi[i]))
        }
        println("[RepConv] 参数 ${rc.parameterCount} 个；折叠前后最大差值 = %.3e（量级 %.3f）".format(maxDiff, maxMag))

        assertTrue("输出量级不应为 0（否则测试无意义）", maxMag > 0.01f)
        // 求和顺序不同会带来浮点差异，用相对容差
        assertTrue("折叠前后不一致：maxDiff=$maxDiff maxMag=$maxMag", maxDiff < 1e-4f * maxMag + 1e-6f)
    }

    @Test
    fun `单通道单输出也能正确折叠`() {
        // 这正是 RepDetailRefiner 的真实形状：1 进 1 出、单通道
        val rc = RepDetailRefiner.repConv
        val h = 21
        val w = 19
        val x = input(1, h, w)

        val multi = rc.forwardMultiBranch(x, h, w)
        val folded = RepDetailRefiner.folded
        val single = folded.forward(x, h, w, RepDetailRefiner.effectiveKernelSize)

        var maxDiff = 0f
        for (i in multi.indices) maxDiff = max(maxDiff, abs(multi[i] - single[i]))
        println(
            "[RepConv] 细节保真算子：参数 ${RepDetailRefiner.parameterCount} 个 / " +
                "折叠核 ${folded.kernel}×${folded.kernel} / 有效核 ${RepDetailRefiner.effectiveKernelSize}×${RepDetailRefiner.effectiveKernelSize} / " +
                "最大差值 = %.3e".format(maxDiff),
        )
        assertTrue("细节保真算子折叠不一致：$maxDiff", maxDiff < 1e-5f)
    }

    @Test
    fun `外圈权重为零时有效核自动收缩且结果不变`() {
        // 只让 3×3 与 1×1 分支出力，5×5 与两个方向分支全零
        val rc = RepConv(1, 1, 1, RepBlock.DEFAULT_BRANCHES).also { it.initDeterministic(7L, 0.4f) }
        for (i in rc.branchWeights[0].indices) rc.branchWeights[0][i] = 0f
        for (i in rc.branchWeights[2].indices) rc.branchWeights[2][i] = 0f
        for (i in rc.branchWeights[3].indices) rc.branchWeights[3][i] = 0f
        rc.branchBiases[0].fill(0f)
        rc.branchBiases[2].fill(0f)
        rc.branchBiases[3].fill(0f)

        val folded = rc.fold()
        assertEquals("折叠后核尺寸仍是 5（结构决定的）", 5, folded.kernel)
        assertEquals("但有效核应收缩到 3", 3, folded.effectiveKernelSize())

        val h = 15
        val w = 15
        val x = input(1, h, w)
        val multi = rc.forwardMultiBranch(x, h, w)
        val full = folded.forward(x, h, w, effectiveK = 5)
        val fast = folded.forward(x, h, w, effectiveK = folded.effectiveKernelSize())

        for (i in multi.indices) {
            assertEquals("有效核收缩后必须逐点相同", multi[i], fast[i], 1e-5f)
            assertEquals("全核与有效核必须逐点相同", full[i], fast[i], 1e-5f)
        }
    }

    @Test
    fun `折叠块在两段之间带激活时仍然等价`() {
        // RepBlock = RepConv → PReLU → RepConv。激活夹在两个可折叠卷积**之间**，
        // 折叠只吃掉线性部分、不改变激活的位置，所以整块的前向仍必须逐点一致。
        // 这条断言的意义：将来接入论文里的训练权重（RepFFN 需要非线性）时，
        // 折叠路径不需要改动 —— 这个测试会守住它。
        val block = RepBlock(
            inChannels = 2,
            hiddenChannels = 2,
            outChannels = 2,
            negativeSlope = 0.25f,
        )
        block.first.initDeterministic(11L, 0.35f)
        block.second.initDeterministic(29L, 0.35f)

        val h = 14
        val w = 11
        val x = input(2, h, w)

        val multi = block.forwardMultiBranch(x, h, w)
        val folded = block.forwardFolded(x, h, w)

        var maxDiff = 0f
        var maxMag = 0f
        for (i in multi.indices) {
            maxDiff = max(maxDiff, abs(multi[i] - folded[i]))
            maxMag = max(maxMag, abs(multi[i]))
        }
        println("[RepBlock] 折叠块等价性：maxDiff = %.3e（量级 %.3f）".format(maxDiff, maxMag))
        assertTrue("输出量级不应为 0", maxMag > 0.01f)
        assertTrue("折叠块前后不一致：$maxDiff", maxDiff < 1e-4f * maxMag + 1e-6f)
    }

    @Test
    fun `方向分支确实参与折叠`() {
        // 只开 1×3（水平）分支：折叠后应只在**中间一行的第 1~3 列**非零
        // （1×3 核居中放进 5×5 后占列 1..3，两侧留零）
        val rc = RepConv(1, 1, 1, RepBlock.DEFAULT_BRANCHES).also { it.initDeterministic(3L, 0.5f) }
        for (b in listOf(0, 1, 3, 4)) {
            rc.branchWeights[b].fill(0f)
            rc.branchBiases[b].fill(0f)
        }
        rc.branchBiases[2].fill(0f)

        val folded = rc.fold()
        for (y in 0 until 5) {
            for (x in 0 until 5) {
                val wv = folded.weightAt(0, 0, y, x)
                when {
                    y != 2 -> assertEquals("1×3 分支只应影响中间一行：(y=$y,x=$x)", 0f, wv, 0f)
                    x in 1..3 -> assertTrue("核覆盖处应有非零权重：(y=$y,x=$x) = $wv", wv != 0f)
                    else -> assertEquals("中间行两侧应留零：(y=$y,x=$x)", 0f, wv, 0f)
                }
            }
        }
    }
}
