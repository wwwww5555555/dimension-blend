# ARCHITECTURE.md 修正说明与关键 diff（v0.1 → v0.2 → v0.3）

> 最新一轮（v0.2 → v0.3：清单体检 + 取消内置素材 + UI 暖黄 + 性能口径放宽）见文档末尾的
> **「附：本轮修改记录（2026-10-16，v0.2 → v0.3）」**。

本文件逐条对应你提出的 10 个问题 + 补充项，说明「原文档错在哪 → 改成了什么 → 它落在哪个文件/代码里 → 怎么验证」。

## 总览

| # | 问题 | 严重度 | 落点 |
| --- | --- | --- | --- |
| 1 | 版本矩阵采信未来版本号 | 阻塞构建 | `gradle/libs.versions.toml`、`gradle-wrapper.properties`、7 个模块 `build.gradle.kts`、`ARCHITECTURE.md §2` |
| 2 | `:core-ai → :core-cv` 依赖箭头矛盾 | 架构错误 | `ARCHITECTURE.md §3`、`settings.gradle.kts`、`app/build.gradle.kts`、`feature-editor/build.gradle.kts` |
| 3 | 管线归属未明确 | 架构缺失 | 新增 `:core-pipeline` 模块 |
| 4 | Reinhard 力度施加位置错误 | 算法错误 | `ARCHITECTURE.md §6.3`（P1 落 `core-cv/color/ColorTransfer.kt`） |
| 5 | 方向性阴影只是近似却没标注 | 预期管理 | `ARCHITECTURE.md §6.5`、`ROADMAP.md P2/P3` |
| 6 | `feather` 无量纲 | 算法缺陷 | `ARCHITECTURE.md §6.6` |
| 7 | 融合预算 800ms 严重低估 | 验收不通过 | `ARCHITECTURE.md §7.2`、`ROADMAP.md` |
| 8 | P0–P2 就引入分块 | 过度设计 | `ARCHITECTURE.md §7.3`、`ROADMAP.md P3` |
| 9 | 色温正反解不成对 | 一致性问题 | `ARCHITECTURE.md §6.2`（P1 落 `core-cv/color/KelvinRgb.kt`） |
| 10a | 预览只有两态 | 体验缺陷 | `ARCHITECTURE.md §7.1` |
| 10b | 未处理广色域输入 | 色偏 | `ARCHITECTURE.md §6.7` |
| 10c | 无撤销/重做 | 体验缺陷 | `core-common/util/UndoRedoStack.kt`、`model/EditorSnapshot.kt` |
| 10d | 测试矩阵不全 | 质量风险 | `ARCHITECTURE.md §10` |
| 10e | 素材授权只靠约定 | 合规风险 | `data/assets/frogs/manifest.json`、`FrogManifest.kt` + 6 个单测 |

---

## 1. 版本矩阵真实性

**原问题**：v0.1 直接采用 AGP 9.4.0 / Gradle 9.6.0 / Kotlin 2.3.21 / compileSdk 37 / Compose BOM 2026.09.00，并要求 Android Studio 2026.2.1+。这些版本号本身是当期官方稳定版（我逐个核对过官方发布页），但它们**同时要求 IDE、SDK、AGP 三者全部到位**，属于「一开始就赌新版」，一旦你的 IDE 版本不匹配，Gradle Sync 直接失败，P0 无法开工。

**修正**：默认改为 **Plan A**（官方兼容性表已闭合 + 大规模项目验证过的组合），把新版降级为需要显式确认才启用的 Plan C。

```diff
 [versions]
-agp = "9.4.0"
-kotlin = "2.3.21"
-ksp = "2.3.21-2.0.2"
-composeBom = "2026.09.00"
-camerax = "1.6.1"
-hilt = "2.60.1"
+agp = "8.7.3"          # 官方：最高支持 API 35、最低 Gradle 8.9、JDK 17、Build Tools 34.0.0
+kotlin = "2.0.21"      # Kotlin 2.0.x 要求 AGP >= 8.5
+ksp = "2.0.21-1.0.27"  # 与 Kotlin 严格同前缀
+composeBom = "2024.10.01"
+camerax = "1.4.0"
+hilt = "2.52"
```

```diff
 # gradle/wrapper/gradle-wrapper.properties
-distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
+distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

```diff
 # 7 个模块的 build.gradle.kts（app / core-common / core-cv / core-ai / core-pipeline / data / feature-*）
-    compileSdk = 37
+    compileSdk = 35
```

**闭合性核对（三项约束互相成立）**

| 约束 | 来源 | 结论 |
| --- | --- | --- |
| AGP 8.7 → Gradle ≥ 8.9 | AGP 8.7 官方兼容性说明 | Gradle 8.9 ✓ |
| Kotlin 2.0.21 → Gradle 7.5 ~ 8.10 | Kotlin Gradle 兼容表 | Gradle 8.9 **在范围内，无警告** ✓ |
| Kotlin 2.0.x → AGP ≥ 8.5 | Android 官方 Kotlin 支持表 | AGP 8.7.3 ✓ |
| Compose 1.7（BOM 2024.10.01）→ compileSdk ≥ 34 | Compose 1.7 / Material3 1.3.0 的 AAR metadata | compileSdk 35 ✓ |
| AGP 8.7 → 最高 API 35 | AGP 8.7 官方 | compileSdk 35 = 上限，正好 ✓ |

**回退方案改成真正的回退**（Plan B 只降三处，Kotlin 与业务代码完全不动）：

```diff
-（原 Plan B）AGP 8.13.2 + Gradle 8.13 + Kotlin 2.3.21 + compileSdk 36 —— 这其实是"次新版"，不是回退
+（新 Plan B）AGP 8.5.2 + Gradle 8.7 + compileSdk 34 + Compose BOM 2024.06.00
+             Kotlin 保持 2.0.21 / KSP 保持 2.0.21-1.0.27（AGP 8.5 要求 Kotlin 2.0 只需 AGP ≥ 8.5）
```

**验证**：`./gradlew --version` 应输出 Gradle 8.9 / JVM 17；`./gradlew :app:assembleDebug` 首次同步无 "requires a newer version" 类报错。

---

## 2 & 3. 依赖关系矛盾 + 管线归属

**原问题**：v0.1 的架构图里画了 `:core-cv ◄── 依赖 ── :core-ai`，同时在文字里写「两者禁止互相依赖」——自相矛盾，且没说清 `ImagePipeline` 到底放哪。

**修正**：删掉那条箭头；新增 `:core-pipeline` 作为唯一编排层。

```diff
-┌───────▼────────┐                    ┌─────────▼────────┐
-│ :core-cv       │◄── 依赖 ──────────│ :core-ai         │
-│ 颜色迁移/阴影/融合 │                   │ 光照估计/抠图      │
-└───────┬────────┘                    └─────────┬────────┘
-        └────────────────┬────────────────────┘
+              ┌────────────▼────────────┐
+              │ :core-pipeline          │ ★ ImagePipeline 实现（编排层）
+              │ 唯一同时引用 core-cv+ai  │
+              └──────┬────────────┬─────┘
+                     │            │
+        ┌────────────▼──┐   ┌─────▼──────────┐
+        │ :core-cv      │   │ :core-ai       │  ← 两者之间【无任何依赖】
+        └───────┬───────┘   └───────┬────────┘
+                └──────────┬────────┘
+                 ┌─────────▼─────────┐
+                 │ :core-common      │
+                 └───────────────────┘
```

传统 CV 兜底估计器的归属按你的要求明确：**用纯 Kotlin 数组实现，放 `:core-ai`**（它属于「光照估计」这条业务线，且与 OpenCV 无关，放在 `:core-ai` 可以让兜底能力不依赖任何原生库）；需要 OpenCV 的算法（颜色迁移、阴影、融合、`KelvinRgb`）放 `:core-cv`；两边共享的数据只用 `:core-common` 的模型交换。

管线归属选 **独立 `:core-pipeline` 模块**，而不是放进 `:data`：

```diff
+# 为什么不是 :data？
+#   :data 的职责是"数据来源"（素材文件 / MediaStore / DataStore），不含图像计算。
+#   把编排层塞进 :data 会让 :data 依赖 :core-cv 与 :core-ai，职责立刻变浑浊，
+#   而且以后一旦想把素材换成别的来源（比如只读 SD 卡目录），会被迫连 CV 依赖一起动。
```

```diff
 # settings.gradle.kts
 include(":core-ai")
+include(":core-pipeline") // ★ 编排层：唯一同时引用 core-cv 与 core-ai 的模块
 include(":data")
```

```diff
 # app/build.gradle.kts —— app 也不再直接依赖 core-cv / core-ai
-    implementation(project(":core-cv"))
-    implementation(project(":core-ai"))
+    // 只有 :core-pipeline 允许同时看到两者
+    implementation(project(":core-pipeline"))
```

```diff
 # feature-editor/build.gradle.kts —— feature 只依赖接口与模型
-    implementation(project(":core-cv"))
-    implementation(project(":core-ai"))
     implementation(project(":data"))
```

feature 模块拿到的接口长这样（`core-common/pipeline/ImagePipeline.kt`，已生成）：

```kotlin
interface ImagePipeline {
    suspend fun estimateLight(scene: Bitmap): LightParams
    suspend fun render(request: RenderRequest, onStage: ((RenderResult.Stage) -> Unit)? = null): RenderResult
}
```
实现 `DefaultImagePipeline` 在 `:core-pipeline`，通过 Hilt `@Binds` 注入 —— **feature 在编译期不知道实现在哪**。

**验证**：`./gradlew :feature-editor:dependencies --configuration debugCompileClasspath | findstr core-cv` 应无输出。

---

## 4. Reinhard 颜色迁移公式

**原问题**：v0.1 写的是 `L' = (L-μ)/σ × σ_scene×k + μ_scene`，把融合强度 `k` 乘在 `σ_scene` 上。两个后果：
① `strength = 0` 时公式不退化（仍有 `μ_scene` 平移），"力度归零"居然还改图；
② 力度变化时均值与对比度耦合变化，用户拖滑杆会看到"又变色又变对比"的抖动。

**修正**：标准 Reinhard 照原式做，力度用 lerp 施加。

```diff
-// 每个通道 c
-c_out = (c - μ_frog) * (σ_scene / σ_frog) * strength + μ_scene
+// 每个通道 c ∈ {L, a, b}
+c_ref = (c_frog - μ_frog_c) * (σ_scene_c / σ_frog_c) + μ_scene_c   // 标准 Reinhard
+c_out = lerp(c_frog, c_ref, strength)                              // strength ∈ [0,1]
```

修正后的性质（已在架构文档 §10 写成回归测试）：
- `strength = 0` → 逐像素等于原图（严格恒等）
- `strength = 1` → 与手算的标准 Reinhard 结果一致
- 中间值连续可调，且均值/对比度不再耦合抖动

同时在 §6.3 保留了两条原设计：**只在 alpha 掩膜内统计**（透明区 premultiply 后接近黑，会把均值/方差拉偏）、**L 通道额外做 p5/p50/p95 分位数映射**（防止高光溢出成死白）。

---

## 5. 方向性阴影：标注为已知近似 + P3 升级路径

**修正**：在 §6.5 显式加了一段"已知近似"，并与 ROADMAP 对齐。

```diff
 > **已知近似（必须记录）**：shear + 压扁是**仿射近似**。阴影轮廓只是奶蛙掩膜的仿射变换结果，
 > **不会跟随奶蛙的局部形状**（跷起的腿、耳朵等），在形体复杂或地面有明显纹理时会偏「贴纸感」。
+> **P3 可选升级**（`shadow/DistanceFieldShadow.kt`）：
+>   对掩膜做**沿光方向的距离变换** → 再沿光方向做 **1D 高斯卷积**（核长 ∝ 1/sin(elevation)），
+>   让阴影轮廓跟随奶蛙形状并自然过渡到地面；与旧实现在同一接口下 A/B 对比。
```

另外补了一条 v0.1 漏掉的边界情况：

```diff
+- **极端情况处理**：`elevation < 10°` 时阴影长度趋于无限，实际按「阴影长度 ≤ 物体高度 × 4」截断，
+  避免生成一整片灰雾。
```

---

## 6. 边缘羽化参数

**原问题**：v0.1 写 `外扩 feather px`，`feather` 没有量纲也没有取值依据 —— 在不同分辨率、不同屏幕密度下完全不可复现。

**修正**：给出量纲公式 + 明确过渡带的颜色来源。

```diff
+feather_px = clamp(0.02 * min(w, h), 6, 24)             // 在 1080p 工作域计算
+feather_px = max(2, feather_px * workShortSide / 1080)  // 其他工作域按比例缩放
```

| 工作域短边 | 基准 feather | 说明 |
| --- | --- | --- |
| 1080 | 21.6 → 21 px | 落在 [6, 24] 区间内 |
| 720 | 14 px | 按 720/1080 比例缩放 |
| 540 | 11 px | 同上 |
| 360 | 6 px | 下限保护后仍 ≥ 2 |

过渡带颜色来源同时修正（这是消光晕的关键）：

```diff
-- 过渡带：α 从 1→0 平滑过渡，此处用「羽化直贴 + 轻微颜色迁移」的结果
+- 过渡带（外扩 feather_px）：权重 w 由 smoothstep 从 0 升到 1
+  out = (1 - w) * 羽化直贴结果 + w * 泊松融合结果
+  —— 纯羽化直贴会在过渡带留下色温断层（直贴没做梯度域求解），
+     纯泊松结果会在高频背景上糊一块（它替换了目标区所有像素），
+     两者加权才能同时消掉这两个问题
```

---

## 7. 融合性能预算：三档 + 明确验收线/保底

**原问题**：v0.1 写"融合 800 ms"，这是拿桌面/GPU 量级套到移动 CPU 上。`seamlessClone` 的泊松求解在 720p 域中端 ARM CPU 上实际要 **1.5~3.0 s**，按 800ms 排预算会让总耗时直接冲到 3s 以上而不自知。

**修正**：预算表拆三档，并明确哪档是验收线、哪档是保底。

```diff
-| 融合 | 800 ms | 720p 域内无缝克隆 |
+| 档位 | 实现 | 中端 ARM CPU 预期 | 定位 |
+| 档 1 | 720p 域 seamlessClone | 1.5 ~ 3.0 s | 高质量可选，**非默认** |
+| 档 2 | 540p 域 seamlessClone + 上采样回贴 | 0.6 ~ 1.3 s | ✅ **验收线档（默认）** |
+| 档 3 | 拉普拉斯多频段融合（工作域，纯卷积/金字塔） | 0.25 ~ 0.6 s | ✅ **保底档**，低端机/超时自动切换 |
```

总预算随之重排（1080p 输入，档 2）：解码 120 + 光照 150 + 奶蛙预处理 100 + 阴影 250 + **融合 1000** + 边缘与编码 600 = **≈ 2.2 s**（验收线 3 s，余量 0.8 s）。
档 1 合计 2.7–4.2 s（可能超线）；档 3 合计 1.5–1.9 s。

新增**运行时自适应策略**（避免"默认档在低端机上翻车"）：

```diff
+默认跑档 2 并计时：
+  < 0.6 s 且 isLowRamDevice == false 且核数 >= 6  → 升级到档 1
+  > 1.3 s 或低内存                              → 自动降档 3
+  实际档位写入 RenderResult.actualTier，供回归分析
```

代码侧已把档位固化为一等公民（`core-common/model/RenderRequest.kt`）：

```kotlin
enum class FusionTier(val shortSide: Int) { FUSION_720(720), FUSION_540(540), LAPLACIAN(0) }

data class RenderRequest(..., val requestedTier: FusionTier? = null)  // null = 自适应
```
`RenderResult.actualTier` 回报实际使用的档位，性能回归测试直接断言它。

---

## 8. 分块融合

**修正**：P0–P2 取消分块，大图保护改为两种更简单且无副作用的手段；分块移入 P3 并强制重叠。

```diff
-**大图保护**：工作副本长边超过 4096 时先缩到 4096；超过 2048 时按 2048 方块分块处理融合阶段
+- 工作副本长边 > 4096 → **先整图缩到 4096**
+- 长边 > 2048 → **整图降采样到 2048 域做融合，再上采样回贴**（一次缩放，不产生拼接痕迹）
+- **P0–P2 不引入分块**。分块仅在 P3 作为可选优化引入，且必须
+  **25% 重叠 + 重叠区加权融合**（汉宁窗权重），否则拼接缝在高频区清晰可见
```

理由写进文档：分块会在 tile 边界引入两个额外风险（透明度/梯度域不连续导致的缝、以及每个 tile 都要单独求解泊松方程的常数开销），在还没有性能基准之前引入等于同时排查"算法错"和"分块错"两类问题。

---

## 9. 色温反解

**修正**：新增一对互逆函数的明确契约（`core-cv/color/KelvinRgb.kt`，P1 落地）。

```diff
+object KelvinRgb {
+    /** Kelvin → 归一化 RGB 增益（6500 K 恒为 [1,1,1]） */
+    fun kelvinToRgbGain(kelvin: Int): FloatArray
+    /** RGB 增益 → Kelvin：对前向函数做单调二分搜索（1500~15000 K），
+     *  保证与 kelvinToRgbGain 严格互逆，误差 < 50 K */
+    fun rgbGainToKelvin(gain: FloatArray): Int
+}
+
+**自动估计出的色温**与**手动色温滑杆**都必须调用这一对函数。
```

关键点：反解**不是**另外写一套公式，而是对前向函数做单调二分搜索 —— 这样才能保证互逆性；另写一套近似公式会在中间色温段产生系统性偏移，表现为"自动估计出的色温，手动滑杆调不到同一个值"。

单测已写进测试策略：2000~12000 K 每 250 K 采样，`rgbGainToKelvin(kelvinToRgbGain(k))` 误差 < 50 K。

---

## 10. 补充项

### 10a. 预览三段式

```diff
-| 预览（拖动时） | 长边 ≤ 720 | ≤ 30 ms/帧 |
-| 预览（松手后） | 长边 ≤ 1080 | ≤ 250 ms |
-| 成品导出 | 原图 | ≤ 3 s |
+| 拖动中 | 长边 ≤ 720 | 变换 + α 直贴，无阴影无融合 | ≤ 30 ms/帧 |
+| **松手后 ≤ 200 ms** | 长边 ≤ 1080 | + 颜色协调 + 阴影（不含泊松） | ≤ 180 ms |
+| **静止 1.2 s 后 / 点保存前** | 长边 ≤ 1080 | 含 540p 域融合，接近成品 | ≤ 1.2 s |
+| 导出 | 原分辨率 | 全链路 | ≤ 3 s |
```
代码里已固化为 `RenderMode { DRAG, SETTLE, FINAL_PREVIEW, EXPORT }`，并用 `includesShadow` / `includesFusion` / `maxShortSide` 三个派生属性表达每档的计算内容。

### 10b. 色域管理

```diff
+### 6.7 色域管理（P0 起必须处理）
+- `BitmapFactory.Options.inPreferredColorSpace = ColorSpace.get(SRGB)`（API 26+）：解码即得 sRGB
+- 自建位图显式传 `ColorSpace.get(SRGB)`
+- 用 `bitmap.colorSpace` 兜底判断；非 sRGB 时 **P3 加显式 P3→sRGB 转换**，
+  且**必须在光照估计之前转换** —— 否则色温估计会基于错误的 RGB 值
+- 保存统一 sRGB
```

### 10c. 撤销/重做

已落地两个文件：

| 文件 | 内容 |
| --- | --- |
| `core-common/util/UndoRedoStack.kt` | 泛型定深栈，默认 `DEFAULT_CAPACITY = 20`，FIFO 丢弃最旧，`record()` 清空重做栈 |
| `core-common/model/EditorSnapshot.kt` | 快照 = `Placement` + `LightOverrides`（两个轻量 data class，单份几十字节） |

粒度约定（写进 KDoc）：拖动在**手指抬起时**记录一次；滑杆在**值稳定 300ms 后**记录一次 —— 否则 20 的深度会被一帧帧的移动瞬间吃光。

### 10d. 测试矩阵

```diff
-**兼容性矩阵**：Android 8 / 10 / 12 / 14 各一台 + 低端/中端/旗舰各一台
+| Android 8 (API 26)  | MediaStore 走 WRITE_EXTERNAL_STORAGE 分支；PickVisualMedia 回退 GET_CONTENT |
+| Android 12 (API 31) | 权限与相册流程 |
+| Android 14 (API 34) | 部分相册权限、前台服务限制 |
+| Android 15 (API 35) | **NNAPI 废弃起点 → 验证 GpuDelegate → XNNPACK 回退链** |
+| Android 16 (API 36) | 前瞻 + 新的内存限制行为 |
```

随之下调两个连带项：加速器链从 `GPU → NNAPI → CPU` 改为 **`GpuDelegate → XNNPACK CPU(4 线程)`**，文档里明确"NNAPI 自 Android 15 起废弃，本项目不再把 `NnApiDelegate` 作为备选"；`core-ai` 的依赖注释也同步改了。

### 10e. 素材授权门控（manifest.json）

```diff
-| 授权 | 仅限三选一：① 自绘 ② 自有版权 ③ CC0（在 README 表格里登记） |
+| 授权 | 必须写进 `manifest.json` 的 `license` 字段，取值仅 `CC0` | `self-drawn` |
+| 加载行为 | 缺失/非法 → **跳过 + Log.w，绝不进入素材列表** |
```

代码侧把"没有 license"这种状态**在类型层面排除**了：解析器 `FrogManifest.parse()` 返回的 `List<FrogManifestEntry>` 里，`license` 是非空枚举 `FrogAsset.License`，不存在"条目在列表里但 license 为空"的可能。

```kotlin
enum class License(val id: String) {
    CC0("CC0"), SELF_DRAWN("self-drawn");
    companion object { fun fromId(raw: String?): License? }   // 非法返回 null → 调用方跳过
}
```

已落地的 4 个文件：
- `data/src/main/assets/frogs/manifest.json`（含 6 条占位素材登记，schema 可直接照着加）
- `data/src/main/java/com/naiwa/data/frog/FrogManifest.kt`（解析 + 校验 + warning）
- `data/src/test/java/com/naiwa/data/frog/FrogManifestTest.kt`（6 个用例）
- `data/build.gradle.kts` + `testImplementation(libs.json)`（Android 的 org.json 在 JVM 单测里是空桩，必须替换成真实实现才能测）

校验项不止 license：非 PNG、文件名含 `/` `\` `..`（防目录穿越）、重复登记、文件缺失，全部跳过并给出可定位的 warning 文本。

---

## 本次同时落地的代码（非文档部分）

| 文件 | 作用 |
| --- | --- |
| `core-common/model/LightParams.kt` | 光照参数契约；含 `NEUTRAL` 兜底、`TRUST_THRESHOLD = 0.45`、`clamped()`、`normalizeDegrees()` |
| `core-common/model/LightOverrides.kt` | 手动滑杆覆盖值（全可空 = 不覆盖）；`lightIntensity` 是乘性、其余是替换 |
| `core-common/model/Placement.kt` | 归一化 平移/缩放/旋转 + `targetWidthPx()` |
| `core-common/model/EditorSnapshot.kt` | 撤销/重做快照 |
| `core-common/model/FrogAsset.kt` | 素材元信息 + License 白名单枚举 |
| `core-common/model/RenderRequest.kt` | `RenderMode`（三段式预览 + 导出）、`FusionTier`（三档） |
| `core-common/model/RenderResult.kt` | 分阶段耗时 + 实际档位 + 光照来源 |
| `core-common/pipeline/ImagePipeline.kt` | feature 与实现之间的唯一契约 |
| `core-common/util/UndoRedoStack.kt` | 深度 20 的撤销/重做栈 |
| `core-pipeline/build.gradle.kts` 等 3 个文件 | 新增编排层模块 |
| `data/.../frog/FrogManifest.kt` + 单测 + `manifest.json` | 授权门控 |

## 待你确认的一点

Plan A 的 `compileSdk = 35` 需要装 **Android SDK Platform 35**（Android Studio Koala 2024.1+ 即可）。
如果你本机 Studio 更旧、装不上 35，直接按 §2 的 Plan B 改 4 个值即可（`agp` / wrapper 的 Gradle 版本 / `compileSdk` / `composeBom`），Kotlin 与代码不用动。

---

# 附：P0 构建验证记录（2026-10-16）

## 结果

| 项 | 实测 |
| --- | --- |
| 构建 | `BUILD SUCCESSFUL`（186 tasks，Gradle 8.9 / JDK 17 / Android SDK 35 / Build-Tools 34.0.0） |
| 单元测试 | 25 个用例全绿：LightParams 7 / UndoRedoStack 6 / ColorKeyMatte 6 / FrogManifest 6 |
| APK | 12.65 MB，`com.naiwa.camera` 0.1.0，minSdk 26 / targetSdk 34 / compileSdk 35，ABI arm64-v8a + armeabi-v7a |
| 权限自查 | 仅 `CAMERA` + `WRITE_EXTERNAL_STORAGE`(maxSdk 28) + 系统自动加的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，**无 INTERNET** |
| 素材 | `assets/frogs/` 下 6 张透明 PNG + `manifest.json` + `README.md` 均已打进包 |

## 关键根因：Kotlin 块注释的嵌套规则（值得单独记录）

首次构建失败，报错是：

```
Could not resolve project :data.
> No matching variant of project :data was found. ... - No variants exist.
```

这个报错的**误导性极强**：它看起来像依赖版本/仓库问题，实际上是 **`:data` 模块的构建脚本根本没被执行**。

根因是构建脚本顶部块注释里写了这样一行：

```kotlin
 *  - 内置奶蛙素材库（assets/frogs/*.png + manifest.json 授权门控）
```

**Kotlin 的块注释支持嵌套。** 这里的"斜杠 + 星号"被识别为**嵌套块注释的开始**，
而后面只有注释结尾的一个收尾符号 —— 它关掉的是**内层**那个注释，外层注释仍在继续，
于是 `plugins {}`、`android {}`、`kotlin {}`、`dependencies {}` **整段被吞成注释**。

后果链条：
1. Android 插件没被应用 → 该模块**没有任何 variant**；
2. Gradle 不报语法错（"到文件结尾都没闭合的注释"是合法语法）；
3. `:data:help` 这类任务照常能跑（没有脚本的项目也能跑 help），所以看起来"模块是好的"；
4. 直到 `:app` 解析这个项目依赖时才炸，错误信息却指向"没有 variant 这个结果"，而不是"注释吞掉了脚本"这个原因。

**排查过程**（每一步都在排除一个错误方向）：
- 怀疑版本/仓库 → 换仓库无效；
- 怀疑目录名 → 把 `:data` 改名为 `:core-data` 无效（但命名统一成 `core-` 前缀这件事保留了）；
- 怀疑 Gradle 脚本缓存 → 移走 `caches/8.9/kotlin-dsl` 无效；
- 怀疑沙箱/权限 → 提权跑同样无效；
- 怀疑文件损坏（BOM / NUL / 编码）→ 文件 1687 字节、无 NUL、头部正常；
- **决定性实验**：在脚本末尾放 `error("PROBE-...")` —— 构建**依然成功**，证明脚本没被执行；
  再新建一个内容相同的模块 `:store`，同样的探针**立刻让构建失败**（脚本被执行），
  由此把范围锁死在"该文件的内容"而不是"模块身份或环境"；
- 最后用最小脚本二分，定位到块注释。

**防复发措施**：
1. `core-data/build.gradle.kts` 顶部写明这条踩坑记录，该文件只用行注释；
2. 全项目对 `--include=*.kts` 扫了一遍"块注释内出现斜杠星号"的情况，确认其余 `/*` 都是正常闭合的注释开头；
3. 以后判定"模块是否被正确配置"，用 `gradle :X:outgoingVariants` 而不是"构建能不能跑" ——
   前者能一眼看出"这个模块一个 variant 都没有"这个本质现象。

## 另外两个编译期问题（已在代码里修掉）

| 报错 | 原因 | 修法 |
| --- | --- | --- |
| `Const 'val' has type 'android.graphics.Bitmap.Config'` | Kotlin 的 `const val` 只能装基本类型与 String | 改为 `private val CONFIG` |
| `Const 'val' initializer should be a constant value`（MediaStoreSaver） | `Environment.DIRECTORY_PICTURES` 是 Java 静态字段，Kotlin 不视为编译期常量 | `RELATIVE_PATH` 改为 `private val` |
| `Argument type mismatch: Options, but Function1 expected`（BitmapDecoder） | 把 `Options` 直接当作 `use {}` 的 lambda 传参，写法本身不成立 | 改为 `readBounds { options -> ... }` 显式 lambda |
| `Cannot access 'companion object Companion': it is private` | `CameraViewModel` 的 companion 是 private，界面层引用了 `TAG` | companion 改为公开 |

## 单元测试期望值的修正（3 个用例）

首轮 `ColorKeyMatteTest` 有 3 个失败，逐个核对后**结论是测试期望写错，不是算法错**：

| 用例 | 现象 | 结论与修正 |
| --- | --- | --- |
| IoU > 0.9 | 实测 ~0.75 | `edgeShrinkPx=2` + `featherRadius=2` 是**故意**让掩膜小一圈、边缘软化，为 P2 融合留过渡带。测"分割本身"必须关掉这两项；另加一条用例专门验证它俩确实会缩小掩膜且不越界 |
| 绿幕 vs 前景区分 | IoU = 0.0 | 原用例的前景/背景只差约 7% 亮度，落在色距容差（`distanceLow=0.055`）之内 —— 而"亮度权重减半"是**有意**为抗光照漂移做的取舍。改成真实场景的"亮绿幕 + 明显更暗的深绿主体" |
| 前景占比 | 断言失败 | 原用例假设"纯色图 = 全前景"，但纯色图与边框同色，客观定义上就是**全背景**（占比 ≈ 0）。断言写反了，已改正为"纯色图 → 占比 < 5%；主体占画面多数 → 占比 > 50%" |

---

# 附：P1 / P2 / P3 实施记录（2026-10-16）

## 交付内容

| 阶段 | 交付 | 关键文件 |
| --- | --- | --- |
| **P1** | 光照估计（模型 + 传统 CV 双实现）、色温互逆函数对、颜色/亮度协调、轮廓光、OpenCV 接入 | `KelvinRgb`、`LightEstimator`/`CvLightEstimator`/`TfliteLightEstimator`、`ModelAssetInstaller`、`RuntimeProvider`、`OpenCvRuntime`、`LabColor`、`ColorTransfer`、`RimLight` |
| **P2** | 方向性阴影（沿光扫掠）、泊松融合（三档）、拉普拉斯多频段融合（保底）、边缘三段式 α 加权回贴 | `DirectionalShadow`、`PoissonBlender`、`LaplacianBlender`、`EdgeHaloFix`、`AlphaFeather`、`MaskUtils`、`BoxBlur` |
| **P3** | 手动调节滑杆（8 项）、融合档位自适应、分块融合（25% 重叠 + 汉宁窗） | `LightSlidersPanel`、`EditorViewModel` 滑杆接线、`DefaultImagePipeline` 的 `updateAdaptiveTier`、`TiledBlender` |

## 验证结果

- 构建：`BUILD SUCCESSFUL`
- **单元测试 54 个全绿**（新增 22 个）：光照估计 7、色温互逆 7、羽化量纲 6、分块融合 5、颜色迁移 4、原有 25
- APK：**59.23 MB**，权限仅 `CAMERA` + `WRITE_EXTERNAL_STORAGE`(≤28)，**无 INTERNET**
- 打进包的原生库：`libopencv_java4.so`、`libtensorflowlite_jni.so`、`libtensorflowlite_gpu_jni.so`（arm64-v8a + armeabi-v7a）

## ⚠️ 本轮最重要的回归：三方 AAR 把 INTERNET 权限合并进了包

**现象**：启用 OpenCV / TFLite 之后，APK 从 12.65 MB 涨到 90.7 MB，`aapt dump permissions` 里**多出了 `INTERNET` 与 `ACCESS_NETWORK_STATE`**。

**根因**：Android manifest 是**合并**的。三方 AAR（MediaPipe / TFLite 等）各自的 manifest 会声明 `INTERNET`，
合并进最终 APK 后，"不申请网络权限、物理上无法联网"这条硬约束被悄悄破坏 —— **而且构建不会报任何错、不会有警告**。

**修复（纵深防御，而不只是"删掉那个库"）**：在 `app/src/main/AndroidManifest.xml` 里显式剥离：

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

这样即便以后有人"顺手"加了一个联网依赖，重新构建后权限列表依然干净。

**教训**：`Manifest 不申请 INTERNET` 不能靠"我自己的 manifest 里没写"来保证，
必须**以 APK 实测为准**（`aapt dump permissions`），并且用 `tools:node="remove"` 把它变成构建期约束。

## 顺带修掉的其它问题（都是自查/自测发现，不是等出问题才改）

| 问题 | 后果 | 修法 |
| --- | --- | --- |
| `featherPx` 双重缩放 | 720p 算出 9px 而不是 14px，羽化随分辨率呈**平方**响应 | 去掉多余的 `workShortSide / 1080` |
| 色温反解喂错方向 | 估计器给的是"校正增益"，反解要的是"光源增益"，两者互为倒数 → **暖光会被估成 12000K 冷光** | 估计器取倒数后再反解；tint 仍用校正增益判断方向 |
| 色温反解距离函数阶梯化 | 距离里对 K 取整 → 三分搜索在平台上漂移，实测往返误差 953 K | 用浮点 K 的平滑距离；粗扫定位 + 三分细化。实测往返误差 < 5 K |
| `LaplacianBlender` 金字塔只按 R 通道建一次却拿去重建 G/B | 融合区出现明显色偏 | 逐通道独立构建金字塔 |
| `TiledBlender` 加权累加被截断 | 中间值 clamp 到 0~255，数学上不是加权平均 | 改为**逐行**浮点累加（全图累加器在 4096² 下要 300MB+） |
| `RimLight` 增益公式写成了自我抵消的表达式 | 轮廓光强度不可控 | 改为 `边缘带 × 朝向 × 主光强度` 的直白形式 |
| `SimpleBlender.renderFrogOnly` 输出尺寸用了奶蛙尺寸 | 掩膜与场景坐标系不一致（P0 未被调用，一直没暴露） | 改为输出场景尺寸 |
| `DirectionalShadow` 实参 Float/Double 不匹配 | 编译失败 | 显式转换 |

## 未交付项（明确说明，不含糊）

| 项 | 状态与原因 | 如何补齐 |
| --- | --- | --- |
| **MediaPipe 点选分割**（P2 的"点选抠图"） | **未接入**。三条理由：① 我没有把它接进 UI，引进来就是 30MB 死重量；② 它的 AAR 会把 INTERNET 合并进 manifest，破坏硬约束；③ 没有真机可验证其原生推理路径，不适合把未验证的原生代码塞进交付物。模型已从仓库移出，避免"带了模型却没有代码"的误导 | `core-ai/src/main/assets/models/README.md` 里保留了模型下载 URL 与启用步骤（加依赖 → 放模型 → 记得剥网络权限），当前 `ImagePipeline.prepareFrog` 就是唯一接入点 |
| **GLES 加速**（P3 的"GPU 加速"） | **未实现**。EGL/GLSL 代码无法在本环境验证（没有设备/模拟器），而"渲染结果对不对"只能在真机上看；交付一段没跑过的 GPU 代码，风险大于收益 | 建议在真机上按 §7 的管线把"颜色增益 + α 合成"两步先搬到 GPU，用 CPU 路径做 A/B 对照 |

## 已知的工程取值（不是缺陷，但需要知道）

- **光照模型未训练**：`light_estimator_int8.tflite` 不在仓库里，运行时模型路径会报告不可用并自动切到传统 CV。
  这是**设计如此**：CV 路径永远可用，模型只是"有则更好"。
- **阴影走的是"沿光扫掠"而非 shearing**：轮廓会跟随奶蛙形状，等于把原计划放 P3 的距离变换方案提前实现。
- **融合档位自适应只影响"下一次"**：保证同一张图的输出确定可复现，不会因为计时波动导致两次导出不同。

---

# 附：本轮修改记录（2026-10-16，v0.2 → v0.3）

本轮不重构、不重写，只做三件事：**按新性能标准调整口径**、**取消内置素材**、**UI 改暖黄**。
外加一份"清单逐项体检"，把"已满足"和"未满足"分清楚。

## A. 清单逐项体检结果

| # | 清单项 | 结论 | 依据 / 差异 |
| --- | --- | --- | --- |
| 1 | 版本矩阵真实性 | ✅ 已满足 | Plan A（AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21 / KSP 2.0.21-1.0.27 / compileSdk 35 / Compose BOM 2024.10.01）已本机 `BUILD SUCCESSFUL` |
| 2 | `:core-ai` 与 `:core-cv` 不互相依赖 | ✅ 已满足 | 传统 CV 估计器 `CvLightEstimator` 是**纯 Kotlin**，位于 `:core-ai`，不含任何 OpenCV 符号 |
| 3 | 管线归属 | ✅ 已满足 | `DefaultImagePipeline` 在 `:core-pipeline`；`feature-editor` 只依赖 `ImagePipeline` + `RenderRequest`/`RenderResult` |
| 4 | Reinhard 力度不乘在 σ 上 | ✅ 已满足 | `ColorTransfer` 先算标准 Reinhard 再 `lerp(c_frog, c_ref, strength)`；有"strength=0 逐像素等于原图"的回归测试 |
| 5 | 方向性阴影 | ✅ **超出要求** | 实现是**沿光方向扫掠 + 高斯 + 距离衰减**，轮廓跟随奶蛙形状，不是 shear 仿射近似；ROADMAP 描述已同步更正 |
| 6 | 边缘羽化量纲 | ✅ 已满足 | `featherPx = clamp(0.02×min(w,h), 6, 24) × featherScale`，按工作域缩放；过渡带为"羽化直贴 × 泊松"加权和，非纯直贴 |
| 7 | 融合性能预算 | ⚠️ **本轮修改** | 按新标准重排：档 1 升为首选、阈值从 600/1300ms 改为 2500/4000ms、文档预算改 3.5s/5s |
| 8 | 分块融合 | ✅ 已满足 | P0–P2 无分块；>4096 整图缩到 4096；>2048 走"整图降采样融合 + 上采样回贴"（`PoissonBlender` 的档位工作域）；分块仅在 P3 且**默认不启用** |
| 9 | `KelvinRgb` 位置 | ⚠️ **有意偏离，需说明** | 见下方 A-1 |
| 10 | 预览两段式 | ✅ 已满足 | 三段式（DRAG/SETTLE/FINAL_PREVIEW）；松手防抖 120ms + 渲染，目标 ≤300ms |
| 11 | 色彩空间 | ⚠️ **本轮加强** | 原先只依赖 `inPreferredColorSpace`（是**建议**不是保证）；本轮加"解码后实测 `colorSpace.isSrgb`，不是则 Canvas 重画转换" |
| 12 | 撤销/重做 | ✅ 已满足 | `UndoRedoStack` 深度 20；`EditorSnapshot` 同时含 `Placement` 与 `LightOverrides` |
| 13 | 素材校验 | ✅ 已满足（且**本轮取消内置素材**） | license 门控与单测保留；`assets` 清空为预期状态 |
| 14 | 测试矩阵 | ⚠️ **部分未验证** | 文档列出 Android 8/12/14/15/16；加速器回退链（GpuDelegate → XNNPACK）**需真机，本机无设备，未验证** |

### A-1. `KelvinRgb` 为什么放在 `:core-common` 而不是 `:core-cv`

清单要求"确保 `:core-cv` 有 `KelvinRgb.kt`"，但同时又要求":core-ai 和 :core-cv 不得互相依赖"。
而**自动色温估计在 `:core-ai`**、**手动色温滑杆的渲染在 `:core-cv`** —— 两边都要用它。

- 放 `:core-cv`：`:core-ai` 要用就必须依赖 `:core-cv` → **直接违反第 2 条**。
- 在 `:core-cv` 写一份、`:core-ai` 再抄一份：两份实现，正是"自动估计与手动滑杆必须走同一套函数"要避免的。
- 放 `:core-common`（当前做法）：它是**纯数学、零依赖**，`core-common` 本就是两个模块共享契约的地方。
  于是"两边共用同一套函数"这条要求反而更严格地成立了。

结论：**保留在 `:core-common`，不复制。** 如果你更希望它物理上出现在 `:core-cv`，
唯一自洽的做法是把 `:core-ai` 的光照估计也搬进 `:core-cv`（因为估计需要反解色温），——那是模块职责的重新划分，
不是一次小改动，需要你确认后我再动。

## B. 取消内置素材

**动机**：内置素材是"看起来能用"的占位图，但会让用户以为应用自带奶蛙；且素材一旦进包就成了分发内容，
授权链路要一直维护。改为「用户从相册导入自己的图」。

| 改动 | 文件 | 关键 diff |
| --- | --- | --- |
| 移出 6 张 PNG | `core-data/src/main/assets/frogs/*.png` | 已移到 `../.build-toolchain/quarantine/frogs-backup/`（**未删除，可随时取回**） |
| 清单清空 + 说明 | `assets/frogs/manifest.json` | `"assets": [ ...6 条... ]` → `"assets": []`，并写明"为空是预期状态、重新内置的三条要求" |
| 空清单不再报 warning | `BuiltInFrogRepository.kt` | 新增 `if (outcome.entries.isEmpty()) { Log.i(...预期...); return emptyList() }` |
| 生成脚本默认不入包 | `tools/gen_placeholder_frogs.py` | `DEFAULT_OUT` 从 `data/.../assets/frogs` → `tools/generated-frogs`；新增 `--to-assets` 且**必须显式指定**，否则拒写 |
| 空态提示改口径 | `EditorScreen.kt` | 由"请先运行 gen 脚本"改为"点『从相册导入』选一张奶蛙图"，并用 primaryContainer 底色做成可读的引导块 |
| 首页文案 | `NaiwaNavHost.kt` | 去掉过期的"P0 阶段是直接贴合"，改为说明不内置素材 |
| 文档 | `assets/frogs/README.md`、`README.md`、`ARCHITECTURE.md §10`、`ROADMAP.md` | 同步 |

**风险与影响（已评估）**：
- 首次启动素材条是空的 → 这是**预期行为**，界面有明确引导，不视为缺陷。
- 一次点击的体验成本：原本"点一下就有奶蛙"变成"先导入一张"。这是用户明确要求的方向。
- 未破坏任何已有功能：导入、抠图、摆放、撤销、保存、分享全部不变。

**验证**（实测）：`aapt list` 确认 APK 内 `assets/` 只剩 `frogs/README.md`、`frogs/manifest.json`、`models/README.md`，
6 张 PNG 已不在包内。

## C. UI 改暖黄

新建两套**写死色值**的色板（不用 `dynamicColor`，否则壁纸会把主色带成绿/蓝）：

| 角色 | 亮色 | 暗色 |
| --- | --- | --- |
| primary | `#F2B12B` 琥珀 | `#FFD479` 浅琥珀 |
| onPrimary | `#3A2B00` 深棕（**不能用白色**：白字压琥珀只有 1.9:1 对比度） | `#3A2B00` |
| background | `#FFFBF2` 暖奶油 | `#17130C` 暖褐黑 |
| surface / surfaceContainer 系列 | `#FFFCF5` / `#FBF0D9` 等 | `#201A11` / `#221C12` 等 |
| outline / outlineVariant | `#9A8C6E` / `#DCCFB2` | 同左 / `#4A4231` |

两个容易漏的点，已一并处理：
1. **`surfaceContainer*` 必须显式指定**。不指定的话 Material 会退回默认的紫灰中性色，
   而 Scaffold 底部素材条、卡片这类"带高度的容器"恰好用的是这组颜色 —— 结果是整体暖黄被这些容器破坏。
2. **`themes.xml` 的 `windowBackground` 要对齐**。它决定 Compose 首帧之前显示什么，
   留白色会在冷启动时闪一下冷调。已改为 `#FFFBF2`，并补 `windowLightNavigationBar` 与 `colorAccent`。

顺带把编辑页素材缩略图边框的硬编码 `Color(0x22000000)` 换成主题 `outlineVariant`。

## D. 性能口径调整（旧数字作废）

| 项 | 旧 | 新 |
| --- | --- | --- |
| 1080p 端到端 | < 3 s | **< 5 s**（档 1 预算 ≈3.7 s） |
| 4K 长边 4096 导出 | 未定义 | **< 12 s** |
| 峰值内存 | < 300 MB | **< 600 MB** |
| 预览拖动 | ≥ 30 fps | ≥ 30 fps（单帧目标 30ms → **33ms**） |
| 松手后中间预览 | ≤ 200 ms | **≤ 300 ms**（SETTLE 目标 180ms → 250ms） |
| 低端机判定 | `isLowRamDevice` 或核数 ≤ 4 | **总 RAM < 4GB 或核数 ≤ 4**（厂商标记仍尊重） |
| 降级工作分辨率 | 1280 | **1600** |
| 默认融合档 | 档 2（540p） | **档 1（720p）** |
| 档位阈值 | 600ms 升 / 1300ms 降 | **2500ms 升 / 4000ms 降** |

对应代码改动（`DefaultImagePipeline.kt`）：

```diff
-        const val DEGRADED_SHORT_SIDE = 720
+        const val DEGRADED_SHORT_SIDE = 1600
-        const val FAST_FUSION_MS = 600L
-        const val SLOW_FUSION_MS = 1300L
+        const val TIER_UPGRADE_MS = 2500L
+        const val TIER_TIMEOUT_MS = 4000L
+        const val LOW_END_RAM_BYTES = 4L * 1024 * 1024 * 1024
```

```diff
-            FusionTier.FUSION_540          // 默认走验收线档
+            FusionTier.FUSION_720          // 标准放宽后，档 1 成为默认
```

**新增防震荡机制（本轮发现并修掉的真问题）**：
按新阈值"档 2 跑得快就升档 1"，会出现死循环 —— 档 1 跑 6s → 降档 2 → 档 2 只跑 1s → 又升档 1 → 又跑 6s。
修法是**超时档位进黑名单，本进程内不再自动升回**（`degradedTiers`）：
"这台设备跑档 1 会超时"是设备固有属性，记住一次就够。

## E. 色彩空间兜底（清单第 11 项加强）

`BitmapDecoder` 原本只用 `inPreferredColorSpace = SRGB` 请求解码器输出 sRGB，
但**这只是建议**：部分解码器（尤其 HEIC 与厂商私有路径）会忽略它。
本轮新增 `ensureSrgb()`：解码后实测 `bitmap.colorSpace?.isSrgb`，不是就新建一张 sRGB 位图、
用 `Canvas.drawBitmap` 重画（Skia 在目标色域里做转换），成功后回收原图。
转换失败时**保住原图**（色偏总比丢图好）。作用点覆盖 `decodeUri` / `decodeFile` / `decodeAsset` / `decodeStream`。

## F. 本轮验证记录

```
$ bash tools/build-local.sh :app:assembleDebug      # BUILD SUCCESSFUL
$ bash tools/build-local.sh testDebugUnitTest       # 测试类 9 个 / 用例 54 个 / 失败 0 / 错误 0
$ aapt dump permissions app-debug.apk
  uses-permission: CAMERA
  uses-permission: WRITE_EXTERNAL_STORAGE (maxSdkVersion=28)
  （无 INTERNET）
$ aapt list app-debug.apk | grep assets/
  assets/frogs/README.md
  assets/frogs/manifest.json
  assets/models/README.md          ← 6 张内置 PNG 已不在包内
APK 大小 59.23 MB
```

**未验证项（如实说明）**：真机上的融合耗时、峰值内存、拖动帧率、Android 8/12/14/15/16 设备矩阵、
GpuDelegate → XNNPACK 回退链 —— 本机没有设备与模拟器，这些只能在你的真机上跑。


