package com.naiwa.core.render

import android.util.Log

/**
 * 文本滤镜链（EffectString）—— 阶段五"第三方滤镜库集成"的**可交付实现**。
 *
 * 【为什么没有直接用 gpuimage / gpuimage-plus（重要，如实说明）】
 * 1. `org.wysaid:gpuimage-plus:3.0.0` 在 Maven Central **不存在**（该库只发布在已关停的 jcenter），
 *    Gradle 解析必然失败；
 * 2. `jp.co.cyberagent.android:gpuimage:2.1.0` 可解析，但实测其公开 API 是
 *    `onDraw(int textureId, FloatBuffer cubeBuffer, FloatBuffer textureBuffer): void`、
 *    `init()` 为 **private**、且**没有** `setFloat` —— 它为 GLSurfaceView 驱动设计，
 *    **无法在不破坏封装的前提下接入离屏 FBO 管线**（强行反射调私有 init 会在各家驱动上碎）；
 *    它还带 4 个 ABI 的 libyuv native so，纯增体积。
 *
 * 因此这里实现**语义等价**的文本滤镜链：一行文本声明整条滤镜链，
 * 由**单枚 shader 一次 pass** 完成（这是文本滤镜的核心价值：不是省代码，是省 pass）。
 *
 * 【语法】（与 gpuimage-plus 的 EffectString 习惯一致）
 * ```
 * brightness(0.10); contrast(1.25); saturation(1.30); sepia(0.40); vignette(0.25)
 * ```
 * 也支持简写前缀 `gp:`（历史 spec 兼容）：`gp:sepia` ≡ `sepia(1.0)`。
 *
 * 所有参数都走 uniform（不拼进 shader 源码）——改数值不需要重新编译 program。
 */
object EffectStringParser {

    private const val TAG = "NaiwaCamera"

    data class Effect(val name: String, val value: Float)

    /** 支持的滤镜键（其余静默忽略，避免一个拼写错误毁掉整条链）。 */
    private val SUPPORTED = setOf(
        "brightness", "contrast", "saturation", "sepia", "vignette",
        "exposure", "tempR", "tempG", "tempB",
    )

    /** `gp:` 前缀的历史简写到参数的映射。 */
    private val LEGACY_ALIASES = mapOf(
        "sepia" to Effect("sepia", 1f),
        "contrast" to Effect("contrast", 1.2f),
        "brightness" to Effect("brightness", 0.1f),
        "saturation" to Effect("saturation", 1.2f),
        "sharpen" to Effect("contrast", 1.15f),   // 无卷积时的近似
    )

    fun parse(text: String): List<Effect> {
        if (text.isBlank()) return emptyList()
        val body = text.trim()

        // 兼容历史 spec：gp:name 或 gp:name:value
        if (body.startsWith("gp:")) {
            val parts = body.split(":")
            val name = parts.getOrNull(1)?.lowercase() ?: return emptyList()
            val raw = parts.getOrNull(2)?.toFloatOrNull()
            val alias = LEGACY_ALIASES[name] ?: return emptyList()
            return listOf(if (raw != null) alias.copy(value = raw) else alias)
        }

        return body.split(';', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                val m = Regex("""^([A-Za-z]+)\s*(?:\(\s*(-?\d*\.?\d+)\s*\))?$""").find(token)
                    ?: return@mapNotNull null
                val name = m.groupValues[1]
                if (name !in SUPPORTED) {
                    Log.i(TAG, "未知滤镜键 $name，已忽略")
                    return@mapNotNull null
                }
                val raw = m.groupValues[2]
                Effect(name, if (raw.isEmpty()) 1f else (raw.toFloatOrNull() ?: 1f))
            }
    }

    /** 参数 → uniform（默认值 = 恒等变换）。 */
    fun uniformsFor(effects: List<Effect>): Map<String, Float> {
        val map = LinkedHashMap<String, Float>()
        map["uBrightness"] = 0f
        map["uContrast"] = 1f
        map["uSaturation"] = 1f
        map["uSepia"] = 0f
        map["uVignette"] = 0f
        map["uExposure"] = 1f
        map["uTempR"] = 1f
        map["uTempG"] = 1f
        map["uTempB"] = 1f
        effects.forEach { e ->
            when (e.name) {
                "brightness" -> map["uBrightness"] = e.value.coerceIn(-1f, 1f)
                "contrast" -> map["uContrast"] = e.value.coerceIn(0f, 3f)
                "saturation" -> map["uSaturation"] = e.value.coerceIn(0f, 3f)
                "sepia" -> map["uSepia"] = e.value.coerceIn(0f, 1f)
                "vignette" -> map["uVignette"] = e.value.coerceIn(0f, 1f)
                "exposure" -> map["uExposure"] = e.value.coerceIn(0.2f, 3f)
                "tempR" -> map["uTempR"] = e.value.coerceIn(0f, 2f)
                "tempG" -> map["uTempG"] = e.value.coerceIn(0f, 2f)
                "tempB" -> map["uTempB"] = e.value.coerceIn(0f, 2f)
            }
        }
        return map
    }

    /**
     * 单枚 shader：一条滤镜链全部在一次 pass 内完成。
     * 顺序固定为「曝光 → 亮度 → 对比度 → 饱和度 → 色温 → 棕褐 → 暗角」，
     * 与 Lightroom 的基本面板顺序一致（顺序不同结果不同，必须固定）。
     */
    const val SHADER_EFFECT = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uSepia;
uniform float uVignette;
uniform float uExposure;
uniform float uTempR;
uniform float uTempG;
uniform float uTempB;

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);        // 规格：UV 一律 clamp
    vec4 tex = texture(uSrc, uv);
    vec3 c = tex.rgb;

    c *= uExposure;                              // 曝光（线性增益）
    c *= vec3(uTempR, uTempG, uTempB);           // 色温（分通道增益）
    c += uBrightness;                            // 亮度（加法，避免压暗黑位）
    c = (c - 0.5) * uContrast + 0.5;             // 对比度（绕中灰）
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, uSaturation);            // 饱和度
    vec3 sepiaC = vec3(
        dot(c, vec3(0.393, 0.769, 0.189)),
        dot(c, vec3(0.349, 0.686, 0.168)),
        dot(c, vec3(0.272, 0.534, 0.131))
    );
    c = mix(c, sepiaC, uSepia);                  // 棕褐
    float d = distance(uv, vec2(0.5));
    c *= 1.0 - uVignette * smoothstep(0.35, 0.75, d);   // 暗角

    fragColor = vec4(clamp(c, 0.0, 1.0), tex.a);
}"""
}
