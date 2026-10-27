package com.naiwa.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `GpuImagePlusNode` 的契约测试。
 *
 * 重点是**降级契约**：JVM 单测环境里没有 libCGE.so，`isAvailable()` 必须返回 false、
 * `apply()` 返回 null 且**不抛 UnsatisfiedLinkError** —— 这正是真机上 ABI 不匹配时
 * 的行为，DAG 必须能靠它直通继续渲染。
 */
class GpuImagePlusNodeTest {

    @Test
    fun `cge前缀正确提取`() {
        assertEquals(
            "@adjust(brightness, 0.15)",
            GpuImagePlusNode.effectOf("cge:@adjust(brightness, 0.15)"),
        )
    }

    @Test
    fun `非cge前缀返回null`() {
        assertNull(GpuImagePlusNode.effectOf("es:contrast(1.2)"))
        assertNull(GpuImagePlusNode.effectOf("gp:sepia"))
        assertNull(GpuImagePlusNode.effectOf(""))
    }

    @Test
    fun `无原生库时可用性为false且不崩溃`() {
        // JVM 单测没有 .so → UnsatisfiedLinkError 必须被内部吞掉
        assertFalse("单测环境应判定不可用", GpuImagePlusNode.isAvailable())
    }

    // 注：`apply()` 需要真实 Bitmap，JVM 单测拿不到（Bitmap 是未实现的桩）。
    // 它的降级行为由上面的 isAvailable 契约覆盖（不可用 → executor 走 blit 直通），
    // 真实位图路径在真机验证。

    @Test
    fun `通道规范标明两条语法`() {
        // 文档性断言：两种 spec 前缀的含义不能混
        assertEquals("cge:", GpuImagePlusNode.SPEC_PREFIX)
    }
}
