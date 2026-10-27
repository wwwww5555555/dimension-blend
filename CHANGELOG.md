# 变更记录（Changelog）

所有版本均为**离线优先、无 INTERNET 权限**的本地 APK，单测全绿（数量见各版本条目）。

---

## 次元溶图 v1.0.0 — 素材列表管理 + Release 构建

### 一、素材栏彻底删除

- **数据结构**：`EditorUiState.materials: List<MaterialItem>` + `selectedMaterialId`。
  `MaterialItem` **故意不放 Bitmap**（10 张 1024² 就是 40MB 常驻），
  分三层：列表项只存元数据 → 缩略图 128px 随组合释放 → 全尺寸位图只有选中那一张。
  原 `frogAsset` 字段删除（与 `selectedMaterialId` 会构成两个事实来源）。
- **`removeMaterialFromList(id)`**：列表**立即**移除 → 后台删文件（只有导入素材有文件）→
  若删的是选中项则接续。接续规则优先"**被删项的前一个**"（用户视线还在原位置），
  删到空则 `clearForegroundMaterial()`（清画布 + 位置归零 + 回收）。
- **修掉一个真 bug**：`retireBitmaps` 原来 delay 600ms 后直接 recycle，
  而管线**没有取消检查点**，一张在算的图仍在读 `frogBitmap` → native 读到已释放内存
  （SIGSEGV，无 Kotlin 堆栈）。新增 `awaitPipelineIdle()`：`renderJob.join()` + `fuseMutex.withLock{}`，
  超时（5s）则**跳过回收**，宁可漏一张也不野指针。
- **UI**：`MaterialStrip` 每项右上角加 ×（`Icons.Default.Close`），
  角标放在缩略图裁切层**之外**（否则被圆角切掉），并自带 `clickable` 抢走这次点击
  （否则点 × 会先触发选中、画布闪一下换人）。空列表有引导文案。
- **契约测试** `MaterialListContractTest`（9 条），反向验证 2 次（改接续规则 / 去掉 `!builtIn` 判断 → 4 处失败）。

### 二、Release 构建

- **日志**：实测分布 127 处 = `Log.i` 33 + `Log.w` 94，**`Log.d`/`Log.e` 各 0 处**，
  且 **`:app` 模块一处都没有**（全在 7 个库模块）→ `if (BuildConfig.DEBUG)` 在 app 里写
  根本覆盖不到。改用 R8 `-assumenosideeffects`（v/d/i），它在字节码层删调用**连同参数求值**，
  比 `if` 更彻底，且一次规则覆盖全部模块。**保留 `Log.w`**（全在失败分支，开销 0，
  却是现场诊断的唯一线索）。
  **实测**：dex 27.5MB → 3.1MB；`Log.i` 的字符串在 release dex 中**消失**，`Log.w` 仍在。
- **ProGuard**：补 `org.wysaid.**`（gpuimage-plus **没带 consumer-rules.pro**）、
  `native <methods>`、qnn/npu 委托的 keep+dontwarn、`SourceFile,LineNumberTable`（崩溃栈可读）。
  GLES 类 keep 是**刻意的保守**：技术上不需要（无反射/JNI 注册），但它是唯一无法 CI 验证的运行路径。
  **首次构建真实踩坑**：`GpuDelegate` 引用了 `GpuDelegateFactory$Options`，而
  `tensorflow-lite-gpu:2.16.1` 没打包这两个类 → R8 `Missing classes`。按 AGP 生成的
  `missing_rules.txt` 补 `-dontwarn`；安全性由 `tryCreateGpuDelegate` 的 `catch (Throwable)` 兜底证明。
- **签名**：`local.properties` 读四个键（不进仓库），四个键齐全才创建 `signingConfig`，
  否则产出 unsigned（CI 仍能验证 R8）。`enableV1Signing = false`（minSdk 26）。
  **用临时 keystore 实测通过 v2/v3 验证**，随后删除，仓库无密钥残留。
- **体积**：APK 75.9MB → **66.0MB**（dex 27.5→3.1MB），其中 **.so 45.7MB 占 69%**
  （OpenCV 两 ABI 共 34.2MB）。要进一步瘦身只能走 ABI split / AAB，已给出可选代码块。
- **应用信息**：`application-label:'次元溶图'`；权限实测只有 `WRITE_EXTERNAL_STORAGE(≤28)`，
  无 INTERNET/CAMERA/READ_MEDIA_IMAGES。Photo Picker 导入本身**零权限**；
  `aapt dump badging` 里的 `READ_EXTERNAL_STORAGE` 是 aapt1 的隐式推导，清单里没有。
- **423 用例 / 60 测试类全绿**。
- 详情：[docs/RELEASE.md](docs/RELEASE.md)。

## 次元溶图 v1.0.1 — 启动图标 + 素材清空

### 一、启动图标（纯 Vector Drawable，零位图）

- **自适应图标**：`ic_launcher_background.xml`（对角深色渐变 + 青/橙两团辉光）+
  `ic_launcher_foreground.xml`（青环左下、橙环右上、交点一点白光）+ `ic_launcher_monochrome.xml`
  （Android 13+ 主题图标），由 `mipmap-anydpi-v26/ic_launcher{,_round}.xml` 组装。
  `minSdk = 26` → **只出 anydpi-v26 一份**，不按 dpi 出 png。Manifest 补 `android:icon` / `roundIcon`。
- 图形全部落在中心 66×66 安全区内（实测最大半径 **30.73** < 33），圆形/圆角/任意遮罩都不切边。
- 新增 `tools/sim_launcher_icon.py`：逐 path、逐 gradient 端点复刻两个 XML 并用真实遮罩裁切，
  输出 `docs/launcher-icon.png`（四个视角 + 48/72/96dp 观感），同时断言安全区与"两环确实相交"。
- **踩坑**：Android 的 8 位颜色是 `#AARRGGBB`，预览脚本按 CSS 的 `#RRGGBBAA` 解析，
  把"透明橙"读成了"不透明青绿" → 前景 alpha 铺满画布、安全区报半径 75.66。XML 没写错，脚本错了。
- **顺手修启动割裂**：`themes.xml` 还是转型前的浅色（`#FFFBF2` + `lightStatusBar=true`），
  深色应用点开图标会先闪一下奶油白、状态栏图标在浅色系统下不可见。改为深色宿主主题，
  并在 `MainActivity` 显式 `enableEdgeToEdge(SystemBarStyle.dark(...))` ——
  `enableEdgeToEdge()` 的默认策略**跟随系统深浅色**，不是跟随应用主题。

### 二、素材清空

- `EditorViewModel.clearForegroundMaterial()`：取消在途任务 → 重置 `frogBitmap` / `previewBitmap` /
  `placement` → 清空撤销历史 → 延迟回收位图。**顺序不能换**（先改状态再取消渲染 = "人像自己回来"）。
- **不能立即 `recycle()`**：编辑页 `Crossfade(300ms)` 期间旧位图仍在被绘制，同步回收会抛
  `Canvas: trying to use a recycled bitmap`。新增 `retireBitmaps()`：登记 → `delay(600ms)` → 统一释放，
  并跳过与 `scenePreview` / `sceneDrag` 同一实例的位图。
- **管线不检查取消点**：`fuseJob` 保存引用并取消，`renderOnce` / `commitRenderResult` 在写 state 前
  再核对一次 `frogBitmap == null`（清空之后到达的结果直接丢弃并回收）。
- **空渲染拦截**：没有人像时「一键融合」**不禁用**（禁用态说不出原因），由 `triggerFullRender()`
  拦截并提示「请先导入人像素材」。
- UI：顶栏「导入」右侧新增红色垃圾桶（`Icons.Default.Delete`），
  `AnimatedVisibility(visible = state.frogBitmap != null)` + 横向展开/淡入；删除不弹二次确认，
  结果由 Snackbar 回报。
- 新增 `ClearMaterialContractTest`（9 条，源码扫描）钉住上述时序与"禁止同步回收"。
  **三次反向验证**：cancel 挪到 state 更新之后 → 失败；清空里塞 `recycle()` → 失败；
  把 `frogBitmap == null` 改成 `false` → 3 处失败。
- **414 用例 / 59 测试类全绿**；APK 79.62MB，权限仍只有 `WRITE_EXTERNAL_STORAGE(≤28)`，无 INTERNET。
- 详情：[docs/UI-POLISH.md](docs/UI-POLISH.md)。

## v0.36 — 修复「背光面/底部边缘半透明、像融化」

**先排除三个常见原因**（都查过，都不是）：
① 光效强度乘进了 alpha —— `FRAG_P2` 输出的是 `vec4(c, aOut)`，`aOut = p.a` 原始 mask；
② 预乘 alpha 多乘一次 —— 全程不开 `GL_BLEND`（`GlState.ensure2dPass` 显式 disable），
`glBlendFunc` 不参与；`FRAG_KEY` 非预乘、`FRAG_P2B` 预乘但只取 `.rgb` 做 Screen；
③ 最终 Bitmap 带透明区 —— 后处理链 6 个 pass 全部输出 alpha = 1.0。

**真凶：大气透视（bleed）把背景 Screen 进人物内侧 20px。**
`innerProx` 的探针半径就是 `bleedPx`（背光侧 20px），在这个半径内它恒为 1，
于是背景以 `uBleedStrength`(0.35) 灌进一条 20px 的内侧带；背光侧没有光包裹去盖住它，
看起来就是半透明。实测（新增 `tools/sim_alpha_lock.py`）：
**实心区（mask≥0.99）人物纯度只有 0.650**，内侧 2~20px 平均 0.653。

- **bleed 限制在 alpha 过渡带**：`edgeOnly = 1 - smoothstep(0.90, 0.99, p2.a)`，
  `bleedW = innerProx² · inside · edgeOnly · (1 - bottomSolid)`。深度由 alpha 自己给，
  不再用"探针半径"当代理量。→ 纯度 0.653 → **0.984**
- **核心区 alpha 锁定**：`blend = max(blend, smoothstep(0.90, 0.97, p2.a))` ——
  mask ≥ 0.97 的像素 100% 是人物，与光效/法线/光源位置全无关。
- **底部实心**：`bottomSolid = smoothstep(0.86, 0.96, uv.y)`，底部羽化宽度压到 25%
  （`uv.y = 1` 是本工程的画面底部）。
- **腐蚀回填**：`a = max(a, smoothstep(0.95, 0.99, p.a))` —— Shrink Mask 只许削过渡带，
  不许把实心内部吃掉 1~2px（腐蚀值只用于光效门控，不进 `fragColor.a`）。
- **没有加"底部兜底补光"**：底部单薄是上面那条背景渗透冲淡造成的，修掉即自然变实；
  再 Screen 一层会把边缘重新提亮，与"实心"目标相反。理由写在文档里。
- 契约测试 +3（核心区锁定 / 背景渗透受限 / 腐蚀不吃核心），并把"后处理全不透明"从 2 个 pass 扩到 6 个；
  **反向验证**：删掉 `edgeOnly` 与 `blend=max` → 4 处失败。
- **405 用例 / 58 测试类全绿**；APK 81.16MB，权限未变。
- 详情：[docs/EDGE-SOLIDITY.md](docs/EDGE-SOLIDITY.md)。

## v0.35 — 按要求回退到 v0.30 的画面逻辑

用户指定「直接回退到 0.30」。**仓库没有 git，`dist/` 也没有 v0.30 的源码包**
（唯一的 source.zip 是 v0.7），所以不是字节级还原，而是按 CHANGELOG 记录逐段手工还原。
回退前已把当前（v0.34）源码打包成 `dist/NaiwaCamera-v0.34-source.zip` —— 要回到 v0.34 解压覆盖即可。

### 已回退（画面相关，全部恢复 v0.30 写法）

| 位置 | v0.31~v0.34 | 现在（= v0.30） |
| --- | --- | --- |
| `FRAG_EMIT` ③ | `ambientWeight(lit)` + `ambLit` + `uKeyBoost` 增益 | `mix(uAmbientLight * 0.45, uKeyLight, smoothstep(-0.1, 0.55, facing))` |
| `FRAG_LIGHTWRAP` ④ | 硬覆盖主光色 + 蓝污染兜底 | `keyW` 双色插值 + `clamp(uColorMix)` + `uBgBlur` 亮度调制 + `uAmbientLift` |
| `EmissiveColor` | 冷环境色不参与补色 | 沿环境色色相补色（冷环境会让中性主光偏蓝） |
| `LocalLightSample` | 只取亮度前 30% | 邻域**整圈** luma² 加权平均 |
| `AmbientSaturation` / `LightMix` | 环境限饱和、三段环境权重 | **文件已删除**，管线不再调用 |
| `GlesFusionRenderer.keyBoost` | 字段存在 | 已删除 |

同时删除 `LightMixTest` / `AmbientSaturationTest` / `ShaderKeyColorContractTest`，
并把 `EmissiveColorTest`（冷环境→偏蓝）、`LocalLightSampleTest`（大半径更中性）恢复成 v0.30 的断言
—— 测试必须描述代码，不能留着守护已被回退的行为。

### 保留（不影响画面，属于 v0.32 的健壮性）

- `GlReadback`（回读方向统一 + 2×2 实测）、`GlState`（每 pass 关闭深度/剔除/混合/裁剪）：
  回退它们只会把**素材翻转**带回来，对画面没有收益。
- 逐 pass 容错（可选光效失败只丢那一层）、DAG 的逐节点 try-catch 与 `fallbackFor`。
- ⚠️ 因此**降级链仍是安全的**：即使某个 pass 失败，也不会再出现"翻转 + 光影消失"。

### 验证

- `402 用例 / 58 测试类`全绿（比 v0.34 少 31 条 = 被删除的三组测试）。
- `:app:assembleDebug` 通过，APK **81.06MB**（v0.34 是 81.16MB），权限未变。
- 逐段 diff 核对：`FRAG_EMIT ③` 与 `FRAG_LIGHTWRAP ④` 的代码与 v0.30 记录**逐字符一致**。

### 会回来的两个已知问题（这是 v0.30 的原始行为，不是新 bug）

1. 蓝调背景下暖色主光的光包裹**仍会偏冷/发灰蓝**（v0.33/v0.34 就是为了修它）。
2. 若图层管线失败降级到 legacy，**不会再翻转**（因为保留了 `GlReadback`），
   但旧版 legacy 只有 3 个 pass，光效会明显变少。

## 渲染修正 v0.34 — 光包裹硬覆盖主光色

**决定**：停止调"环境光占多少"。v0.31 分层 + v0.33 恢复 0.45 衰减之后，交界带仍会发灰蓝
（实测 R−B 只剩 +0.2，压到 10% 权重也只是"淡灰蓝"）—— 蓝背景彩度太高，权重法没有终点。

**只改 `FRAG_LIGHTWRAP` 的颜色段**（阴影 / P2-c / EMIT / 像素提取 / DAG / 回读坐标系全部未动）：

```glsl
const vec3 FALLBACK_KEY_SRGB = vec3(1.0, 0.8, 0.5);
vec3 keyColor = uKeyLight;
if (keyColor.b > keyColor.r) keyColor = pow(FALLBACK_KEY_SRGB, vec3(2.2));
float litRatio = smoothstep(-0.10, 0.40, facing);
vec3 tinted = keyColor * litRatio;
```

- **删除**：`uAmbientLight` 混色（连同 `ambientWeight` 函数）、`uColorMix` 双色插值、
  `uBgBlur` 亮度调制、`uAmbientLift`。uniform 声明保留并标「已废弃」（编译器会优化掉，
  传值退化为安全空操作）。
- **兜底**：主光蓝通道 > 红通道 → 强制换成默认暖黄。⚠️ 兜底色按 sRGB 定义，本 pass 在线性空间，
  必须 `pow(2.2)`，否则最终编码出来是偏白的 (1.00, 0.91, 0.73) 而不是 `#FFCC80`。
- **叠加方式**：光包裹仍走 composite 的 **Screen**（`1-(1-c)(1-p2w)`），无 Multiply；已加静态断言。
- **契约测试**：新增「光包裹颜色只能是主光色」（禁止颜色段出现 `uColorMix/uAmbientLift/uBgBlur/uMaxLuma/ambientWeight`）、
  「主光被污染成蓝时必须有暖色兜底」、「光包裹必须 Screen 不得 Multiply」；删除两条 v0.31 时代的混色断言。
  **两次反向验证**：把 `mix(uKeyLight, uAmbientLight, 0.2)` 加回去 → 3 处失败；删掉兜底 → 3 处失败。
- **实测**（`tools/sim_ambient_pollution.py`）：litRatio 1.0/0.5/0.2 时 R−B 恒为 +35/+17/+7
  （颜色比例恒定，只有亮度变化）；主光被污染成 `#A1ADCF` 时兜底为 `#FFCC80`（R−B +128）。
- **代价**：背光侧包裹光也变暖（物理上该偏冷，这里选了"宁可全暖不要一处荧光"）；
  包裹强度不再跟随背景亮度（暗景若太亮调 `lightWrapStrength`）。
- **测试**：433 用例 / 61 测试类全绿。
- 详情：[docs/KEY-COLOR.md](docs/KEY-COLOR.md) 第十节。

## 渲染修正 v0.33 — 荧光蓝：环境色去毒 + 主光不再被冷环境补色

**要求**：只动「颜色提取」与「光包裹/发光 shader」，**不改 DAG 与回读坐标系**。

**先纠正一个判断**：蓝污染不是"环境色饱和度太高"。用 `tools/sim_ambient_pollution.py`
（蓝天空 + 薄雾暖夕阳）逐段复刻公式实测：光源邻域取到的 key 是暖的（R−B **+35**），
lit=1.0 处 R−B **+34.7**（正确），但 **lit≈0.4（明暗交界带，占可见轮廓面积最大）只有 +0.2**、
lit≈0.1 处 **−58.9**。所以问题在"过渡带灌进了多少环境色"和"主光自己是不是已经变蓝"。

- **恢复丢失的 0.45 衰减**（`FRAG_EMIT`）：v0.30 是 `uAmbientLight * 0.45`，v0.31 重写颜色分层
  时写成裸 `uAmbientLight` → 过渡带环境占比 0.10 → 0.30（**3 倍**），蓝背景沿整条轮廓灌入。
  修后 lit≈0.4 回到 **+13.6**、lit≈0.1 回到 **−22.7**（比 v0.30 的 −39.8 更不刺眼）。
- **冷环境色不再给主光补彩度**（`EmissiveColor`）：主光接近中性白（chroma<20）时旧逻辑
  "沿环境色色相"补色，蓝背景会把过曝白太阳补成 `(161,173,207)` 蓝白 —— 进 shader 后
  怎么调权重都是荧光蓝。规则改为「环境方向偏冷 → 用 `warmDirection()` 兜底」，
  **暖环境色照旧参与**（否则废掉"跟着场景走"，已加单测保护）。
- **环境色去毒按规格混 40% 灰**（`AmbientSaturation`）：新增 `desaturate()`（`0.6c+0.4·luma`），
  `GRAY_MIX` 0.30→0.40，`cap()` 改为「先混灰柔化 → 再以亮度为轴闭式压到 sat≤0.55」。
  ⚠️ 只用混灰不行：纯蓝 sat 只从 1.000 降到 **0.929**；蓝天空 `#436BCC`（0.671）混灰后
  0.499 —— 够用，但接近纯原色必须靠闭式解兜底。两步都以 luma 为轴，**亮度严格不变**。
- **没有回退 v0.32**（回读统一 / DAG 容错 / GL 状态基线）。理由：那正是上一轮你报的
  「素材翻转 + 光影消失」的修复，回退会把翻转带回来；而本轮要求是"不要改坐标系"，
  保持现状即为不改。v0.30 的源码也没有归档（`dist/` 只有 APK，仓库无 git）。
- **验证**：新增 `tools/sim_ambient_pollution.py`（逐段复刻 Kotlin+GLSL，含断言）；
  `ShaderKeyColorContractTest` 新增「环境色必须带 0.45 衰减」并做反向验证（改回裸环境色 → 4 处失败）；
  `EmissiveColorTest` 反转"冷环境补蓝"的旧契约、新增"暖环境仍可补色"；`AmbientSaturationTest`
  新增 3 条锁住"混灰只降 7% 饱和度"。**432 用例 / 61 测试类全绿**。
- 详情：[docs/KEY-COLOR.md](docs/KEY-COLOR.md) 第九节。

## 渲染稳定性 v0.32 — 素材翻转 + 管线中断

**现象**：点「一键融图」后素材上下颠倒；紧跟其后的光影/阴影像被跳过，人物飘在空中。

**这不是两个 bug**：一键融图走 `GlesLayerPipeline`（9 个全分辨率 pass、v0.26+ 全套光效），
任何一步返回 null 就降到 `GlesFusionRenderer`（legacy 3-pass）—— 而后者**回读多翻了一次**，
且**本来就没有发光链/向内包裹/接触阴影**。所以"翻转"和"光影消失"是同一个降级的两个后果。

- **回读收敛到一个入口**：新增 `GlReadback`（core-common）。三个渲染器原本各写一份，
  方向还不一致（图层管线不翻、另外两个翻）。现在首次调用用 2×2 图案（上红下蓝）
  跑一遍真实的「上传→绘制→回读」**实测方向**并缓存，结果写进 `DebugReport.readbackFlip`；
  探测失败才退回规范推导（不翻转）。回读同时改为**显式绑定 FBO** + `PACK_ALIGNMENT=1`。
- **可选 pass 不再拖垮整帧**：发光链（EMIT/高斯/上采样）、GLOW、P2-b、LIGHTWRAP、
  DOF/调色/Looks/暗角 失败只记日志并把该层换成 1×1 黑纹理；只有 key/p1/p2/p2c/composite/fxaa
  失败才整帧降级。后处理乒乓链失败**不再交换**纹理（旧版换过去之后每个 pass 都在读未初始化的像素）。
- **DAG 逐节点容错**：`execute` 的 pass 循环从 `forEach + return null`（非局部返回，一个槽位失败就整张图放弃）
  改成 `for + try/catch + continue`；`renderNode` 改为返回 `Boolean`；失败节点走 `fallbackFor`：
  ① 直通上游 → ② 用调用方位图 → ③ 清成透明，**保证像素良定义**。
  第三方滤镜的 `unbind → apply → makeCurrent` 补上 `finally`（旧版 apply 抛一次，
  上下文永久停在解绑态，之后所有 GL 调用失效且不抛异常）。
- **每个 pass 强制 2D 状态基线**：`GlState.ensure2dPass()` 关掉 DEPTH_TEST / CULL_FACE /
  BLEND / SCISSOR_TEST。全屏 quad 是**顺时针**，一旦被背面剔除整体消失且 `glGetError` 不报错 ——
  这正是"改了矩阵/翻转就黑屏、后续 pass 读空白纹理"的经典成因。
- **可观测性**：`FboPool` 记账显存峰值（同屏 9 张全分辨率 RGBA8，2048² ≈ 150MB）；
  降级到 legacy 时打印明确日志并置 `DebugReport.lastPipeline = "legacy-gpu"`。
- **验证**：`tools/sim_orientation.py` → `docs/orientation-sim.png`（正常 / 双翻 / 两组 2× 局部放大），
  并断言"回读首行 = 原图第 0 行"。新增三组契约测试（`GlReadbackTest` 6、
  `GlesReadbackOrientationContractTest` 9、`RenderGraphResilienceTest` 6），
  **三次反向验证**（注入手工翻行 / 改回只传宽高 / 改回 return null 均立即失败）。
- 详情：[docs/ORIENTATION-PIPELINE.md](docs/ORIENTATION-PIPELINE.md)。
- **测试**：426 个用例 / 61 个测试类全绿。

## 渲染迭代 v0.31 — 主光锁定（蓝背景暖光去荧光）

**问题**：蓝调背景下，暖色主光的光包裹渲染成了"蓝色荧光"。

**根因**：不是"环境光整体过高"，而是**权重曲线在迎光面没归零** ——
`mix(uAmbientLight*0.45, uKeyLight, smoothstep(-0.1,0.55,facing))` 在 `facing≈0`（轮廓侧面，占环带很大比例）
时环境色占 **87%**。另外两处也在污染：LIGHTWRAP 固定混 25% 蓝色背景模糊；`LocalLightSample` 取邻域整圈平均，暖光被紧邻的冷背景稀释。

- **权重严格分层**：`ambientWeight(lit)` 三段曲线 —— 迎光 **0** / 过渡 **≤0.30** / 背光 **1.0**；颜色改单向 `mix(uKeyLight, uAmbientLight, aW)`。
- **环境色限饱和**：新增 `AmbientSaturation`。**不用反复混灰**（sat=(max-min)/max，max 降得快 min 升得慢，纯蓝要 k=0.92 或 10 轮），改以亮度为轴缩放色度，闭式解 `s = T·luma/[(max-min) - T·(max-luma)]` 一次到位且**亮度严格不变**。实测 `(0.10,0.30,0.95) → (0.256,0.303,0.457)`，sat 0.89→0.44。
- **主光提纯**：`col *= 1 + uKeyBoost * lit`（增益只加迎光侧，背光侧加了会把暗部点亮相）；LIGHTWRAP 的 `colorMixW` 在迎光时 → 1.0（原来固定 0.75 意味着 25% 蓝背景被插进暖色包裹）。
- **光源取色只取亮部**：`LocalLightSample` 从"整圈平均"改成"只取亮度前 30%"（`BRIGHT_KEEP_RATIO`），排除紧邻的冷背景。Bloom 高光色走同一条链（EMIT → 金字塔 → GLOW），无需另取。
- **新增契约** `ShaderKeyColorContractTest`（7 条）：禁止 `mix(uAmbientLight * ...)`、禁止 `uColorMix` 直接 clamp、禁止旧的双色插值方向、必须调 `AmbientSaturation.cap`、必须 `sorted.sort()`。已反向验证（恢复旧公式 → 立刻失败）。
- 离线对照 `tools/sim_key_color.py` → `docs/key-color-sim.png`：光晕本身平均 R−B **+0.081 → +0.103**（正=暖）。
- 详情：[docs/KEY-COLOR.md](docs/KEY-COLOR.md)。
- **测试**：406 个用例 / 58 个测试类全绿（新增 `LightMixTest` 8、`AmbientSaturationTest` 8、`ShaderKeyColorContractTest` 7）。

## 渲染迭代 v0.30 — 明暗交界过渡带（Penumbra）

**问题**：迎光面与背光面的切换像刀切，缺少半影过渡。

### 反直觉的根因

代码里**早就在用 smoothstep**，也没有 `step()` 或 `if (dot>0)` 的硬分支 —— 按"排查硬切分"的思路会一无所获。
病根是**空间尺度**：法向由 1 texel 差分求出，而抠图边缘只有 2~3px，在这 2~3px 内法向翻转 180°、`N·L` 从 +1 跌到 -1。smoothstep 只把这 2px 变成"斜率为 0 的 2px"。
离线实测：旧写法 `litRatio` 从 1 降到 0 只用 **1px**。

- **宽基线法向**：新增 `FRAG_ALPHA_LOW`（1/4 域模糊 alpha），在它上面做 ±`uPenumbraPx`（默认 7px）的差分。EMIT 用 `uBandPx`、LIGHTWRAP 用 `uWrapPx`、P2B/COMPOSITE 用 3 texel 作基线。**采样次数没增加**，只是偏移变大。
  ⚠️ 不能复用 `uPersonLow` —— 它输出的是模糊后的 RGB（alpha 恒 1），那是给 Unsharp 用的。
- **`litRatio` + 严格互补**：`litRatio = smoothstep(-0.20, 0.30, N·L) * eAmp`，`shadowW = 1 - litRatio`。乘 `eAmp` 是必须的：内部没有法向，`N·L` 恒 0，`smoothstep(-0.2,0.3,0)` = 0.35 会给内部一个"微受光"，人物就压不黑。
- **环境光桥梁**：`bridge = 4·l·(1-l)`（钟形，两端导数为 0），用 **SoftLight** 叠背景代表色（0.35 强度）。线性权重会在纯光/纯暗两端各留一个台阶 —— 那正是断层本身。
- **投影末端柔化**：`mix(0.30, 1.0, 1-distK)`（线性，末端导数不为 0）→ `1 - smoothstep(0.35, 1.0, distK)`；接触阴影边界 0.40~0.70 → 0.28~0.75，外圈权重 0.40 → 0.55。
- 离线对照 `tools/sim_penumbra.py` → `docs/penumbra-sim.png`：过渡带宽度 **1px → 5px**（512 图，折合 1080p ~10.5px），采样序列 `0.06,0,0,0…` → `1.0,1.0,1.0,1.0,0.51,0.24,0.04,0…`。
- 契约断言：`litRatio` 区间必须跨 0、`shadowW = 1-litRatio`、桥梁钟形、**法向基线禁止 1 texel**、投影末端必须 smoothstep。
- 详情：[docs/PENUMBRA.md](docs/PENUMBRA.md)。
- **测试**：382 个用例 / 55 个测试类全绿（新增 `PenumbraTest` 12 个）。

## 渲染迭代 v0.29 — 暗部层次 + 底部投影复活

**问题**：逆光暗部依旧不够暗、死灰一片没有层次；底部阴影完全消失，人物飘在空中。

### 暗部：三个独立原因

1. **全身一个固定暗度**（0.32）→ 换成**三级高度阶梯**：头 0.40 / 腰 0.20 / 脚底 0.05。
   用绝对目标亮度而不是"再乘一次系数"（`0.32×0.4` 会叠乘成死黑）。
2. **没有吸收环境色** → `absorb = mix(vec3(uBacklitDarken), uDeepShadowColor, 0.70)`，逐通道乘。
   `uDeepShadowColor` 由新纯函数 `DeepShadowTint` 生成：**色相归一 × 0.42**。
   ⚠️ 背景最暗部原始值线性 ≈0.012，直接乘就是纯黑；⚠️ 必须用 `uni3f` 直传，走 `uniSrgbLinear` 会变 0.14。
3. **压完又被 Screen 提回去** → 新增 `litGate = 1 - backW`，乘到伪立体光照与环境亮部 Screen；
   反弹光保留 25%（物理上确实打到下摆）。另修 `backW` 从 0.75 提到 **1.0** ——
   否则 `mix(0.82, target, backW)` 里 0.82 会漏进 25%，把层次压平（实测 1:0.76:0.59 → 1:0.60:0.33）。

### 阴影：三个原因，**都不是 Alpha**

先排除：P2-c 输出的是 **Multiply 因子图**（`alpha=1`），在 composite 做 `p1 * p2c`，
从不承载人物透明度，与"Alpha 锁定"无关。

1. **接触阴影采样方向反了（关键）**：`uv + uContactPx*uTexel.y` 在本工程是**向下**看，
   而人物正下方的背景再往下还是背景 → 采样恒为 0 → **接触阴影从未生效过一次**。
   改为 `-uContactPx`（向上朝脚底），并做双圈：4px 死黑核心 + 12px 过渡。
2. **强度被当暗度直接乘**：`uStrength` 默认 0.3（光照模型的相对权重），直接乘上去根部最多 0.3 暗度。
   改为先算本体深度（硬核 0.95 / 软层 0.55）再乘；pipeline 端重映射到 [0.55, 1.0]（拖到 0 仍可关闭）。
3. **投影太短**：`shadowOffsetPx` 24 → 60；远端不再归零（保留 30%，否则影子断成一截）。

- 节点存活链路已核对：P2-c → `p1 * p2c` → 人物覆盖只动人物区 → 光包裹在暗部已熔断到 15% → GLOW 对背景只给 35%（实测提亮 ≈10%）。顺序上 **P2-c 必须最先画**（EMIT/P2-b/LIGHTWRAP 都读它的遮挡），已有索引断言。
- 新增契约 2 条并反向验证：**接触阴影必须向上采样**（禁止 `+uContactPx`，改回去立刻失败）、**主投影必须有 0.95 硬核层**。
- 离线对照 `tools/sim_shadow_deep.py` → `docs/shadow-deep-sim.png` 四格：旧 contact 项几乎全白（只在轮廓内侧留 1px 黑线）；新版裙摆下方出现明显弧线。实测头部 0.151 → 0.076、腰部 0.045、脚底 0.025。
- 详情：[docs/SHADOW-DEPTH.md](docs/SHADOW-DEPTH.md)。
- **测试**：372 个用例 / 54 个测试类全绿（新增 `DeepShadowTintTest` 12 个）。

## 渲染迭代 v0.28 — 动态光影跟随（指哪打哪）

**问题**：光源移到画面左侧/右侧/正中间，光包裹区域不跟随。

**根因**：法向和点积都没错，错的是**点积的第二个操作数是常数**。
`uLightPos`（NMS 最亮连通区质心）与 `uLightDir`（亮部方位直方图主峰）是**两套独立统计**，
后者被当成全局方向整图共用。单光源 + 人物居中时两者大致一致，所以平时看不出来；
多光源 / 光源偏心 / 人物被拖到一角时方向就与位置脱节 —— 而且它**不崩溃、不报错**，属于静默指错。

- **5 个光效 pass 全部改为逐像素动态光向**：`lightDirPx(uv) = normalize((uLightPos - uv) / uTexel)`。
  除以 `uTexel` 即乘 `(w,h)`，修掉宽高比导致的方位偏移（16:9 下「左上 45°」在 uv 空间实际是 60°）。
- **法向同步改到像素空间**：`vec2 gPx = vec2(aR-aL, aD-aU) / (2.0*uTexel)`。uv 空间差分在 x/y 上长度不等，直接归一化会让 45° 边缘偏角。零梯度显式归零，仍取负。
- **顶光偏置**：光源水平居中（`abs(uLightPos.x-0.5) < 0.06~0.20`）时，`N·L` 左右同时趋 0 会糊成对称大白边。改为把 80% 权重压到「上边缘」（`-nPx.y`）→ 光集中到头顶/肩线，留 20% 保留方向感。
- **光源色跟随位置**：新增纯函数 `LocalLightSample`（半径 = 长边 5%、`luma²` 加权、跳过 `luma<8`）。原来取全图高光均值，多光源下冷暖互相抵消成灰；现在光在左边就取左边的夕阳色。样本 < 12 时回退全图高光色。
- **方向性羽化一并跟随**（COMPOSITE）：否则「迎光侧硬边」会留在旧方位。
- **新增契约测试** `ShaderDynamicLightContractTest`（6 条）：**禁止任何 `dot(...)` 里出现 `uLightDir`**、禁止 `vec2 l2 = uLightDir.xy`、法向必须像素空间且取负、`lightDirPx` 必须除以 `uTexel`。已反向验证（把 `lightDirPx(uv)` 换成 `normalize(uLightDir.xy)` → 立刻失败）。
- 离线对照 `tools/sim_dynamic_light.py` → `docs/dynamic-light-sim.png`：旧写法三位置的光晕质心恒为 **0.538**（完全不动），新写法 **0.461 / 0.510 / 0.546**（跟随）。
- 详情：[docs/DYNAMIC-LIGHT.md](docs/DYNAMIC-LIGHT.md)。
- **测试**：357 个用例 / 53 个测试类全绿（新增 `LocalLightSampleTest` 9 个、`ShaderDynamicLightContractTest` 6 个）。

## 渲染迭代 v0.27 — 逆光暗部压暗 + 阴影边缘漏光熔断

**问题**：逆光场景人物正面不够暗；部分素材的阴影边缘仍有一圈不符合物理的光包裹。

**根因**（两条互不相关，都必须独立修）：
- 逆光压暗原本只压 `dot(N,L)<0` 的区域，但**平涂人物内部没有法向** —— 用径向伪法线替代会在画面上切出斜分界线；
- 漏光不是门控写错，而是**门控的输入不可信**：投影与人物交界处 alpha 梯度≈0，`N·L` 落在 0 附近被判成"略微迎光"。

- **背光面判据重做**：不再用 `N·L`，改为"**在不在迎光轮廓上**"——内部（梯度≈0）= 背光面 → 压到 **0.32**；迎光侧窄轮廓 → 0.82（保留金边）。物理依据：逆光下人物整个正面都背着光。
- **暗部染色带亮度还原**：`c * chroma` 后按 luma 归一，实测色度 **+0.0284**、亮度只变 **+0.0001**（证明只改色相不改亮度）。只染 `luma<0.35` 的像素，中间调不染。颜色取自背景暗部中位色（夜景偏蓝紫、黄昏偏暖褐）。
- **阴影漏光双判据熔断**：`occlusion = max(背光权重, 投影遮罩)`，任意一条成立就掐掉，不再依赖伪法线。三处同时切断：`FRAG_EMIT`（阴影里不产生发光源，否则金字塔会把光晕推出几十像素）、`FRAG_LIGHTWRAP`（主光归零，保留 15% 环境散射地板）、`FRAG_P2B`（立体反光）。
- **Pass 顺序修正**：P2-c 提前到 EMIT 与 P2-b 之前（两者现在都读它的遮罩）。顺序错了不会编译失败，只会读空白纹理。
- **顺手修 P2-b 三个叠加的 bug**：① alpha 梯度法向**没取负**（`+grad` 是内法向 → 从 v0.20 起立体反光一直只在**背光侧**出现，与设计相反）；② `+vec2(1e-5)` 在零梯度处伪造 45° 法线；③ `uLightDir` 没 normalize。
- **规格伪代码的 UB**：`smoothstep(0.2, -0.2, N·L)` 的 `edge0 >= edge1` 在 GLSL 里是未定义行为，多数驱动返回 0 → 压暗/熔断整段静默失效。改为 `1 - smoothstep(-0.2, 0.2, x)`。
- **新增静态契约测试** `ShaderShadowCutoffContractTest`：扫描**全部 shader**，把任何 `smoothstep(字面量, 字面量, ...)` 的 `e0 >= e1` 报出来（本项目第三次踩这个坑）；并钉死熔断公式、法向取负、pass 顺序、常量单一来源。三条都做了反向验证（注入错误 → 测试失败 → 恢复）。
- 离线对照：`tools/sim_backlit_cutoff.py` → `docs/backlit-cutoff-sim.png` 六格（旧 / 只压暗 / 只熔断 / 新版）。实测人物实心区平均线性亮度 **0.4828 → 0.2330（压暗比 0.482）**，第 5 格证明"只熔断不压暗"时正面依然亮——两件事彼此独立。
- 详情：[docs/BACKLIT-CUTOFF.md](docs/BACKLIT-CUTOFF.md)。
- **测试**：342 个用例 / 51 个测试类全绿（新增 `BacklitShadeTest` 13 个、`ShaderShadowCutoffContractTest` 10 个）。

## 渲染迭代 v0.26 — 边缘光与泛光重构（废弃「白描边」）

**问题**：光包裹看起来是一圈透明的白色描边。根因三条，与参数无关：
① 环带取自固定的 alpha 过渡区（抠图边缘处处等宽）→ 等宽带；② 颜色取背景高光**中位色**，
阴天/白墙统计出来接近纯白；③ 加完直接 clamp、没有任何扩散 → 光只能贴边。

**方案**：换成「提取高光 → 多级高斯 → 混合」三步物理渲染，旧的一行式叠加代码已删除。

- **EMIT（发光源提取）**：环带改用**形态学梯度**（8 方向 alpha 的 max−min），宽度由 `rimWidthPx` 显式控制且对半透明发丝同样有效；迎光门控改为 `smoothstep(0.0, 0.35, N·L)`（背光面严格归零，旧版 `max(0,dot)` 导致全周发光）；**不乘 alpha**（内侧 1.0 / 外侧 0.62），光才能真正溢出轮廓。
- **光源色不要纯白**：新增纯函数 `EmissiveColor`（压最亮通道 ≤235 + 沿环境色色相补彩度），阴天自动得到暖白偏橙、夜景偏青。
- **高斯金字塔（取代「单 pass 多次高斯」）**：`FRAG_GAUSS`（5-tap 线性采样等效 9 抽头，权重和恰为 1）+ 逐级降采样 + `FRAG_BLOOM_UP`（3×3 tent 上采样累加）。级数由纯函数 `GlowPyramid.plan` 反解 `spread → levels`。**成本对比：24px 光晕全分辨率高斯需 306M 次采样，金字塔 ≈ 26M，少 11 倍且拿到 4 个尺度。**
- **Screen 改到 composite 之后**（`FRAG_GLOW`）：放在 P2 里只能写进人物像素，光晕越过轮廓就断；现在可渗入背景空气（人物 100% / 空气 35%）。混合用 Screen（保高光细节），单次注入上限 0.85。
- **光包裹方向反转**（`FRAG_LIGHTWRAP` 重写）：旧的 `dilate(mask,5px)` 是向外扩 → 必然描边。新版用 8 方向 × 3 半径 alpha 探针估向内距离，`pow(depth,1.8)` 把权重压到最外 5~10px，并用 `smoothstep(0.45,0.80,a)` **严格限制在人物内部**。
- **Alpha 锁死**：四个新 pass 全部输出 `vec4(rgb, 1.0)`；光晕在合成之后 Screen，物理上不存在改 alpha 的路径；`ShaderAlphaContractTest` 新增静态断言。
- **新增静态契约测试** `ShaderUniformSendContractTest`：解析每个 `draw(programs["x"]) {}` 块，检查 Kotlin 传来的每个 uniform 都在该 shader 声明。`glUniform(-1, v)` 是静默忽略，本轮重命名 20+ 个 uniform，肉眼 review 兜不住。（已做反向验证：注入一个笔误立即失败。）
- Debug 通道扩到 0~8：5 = 向内渗透带、7 = 发光源图、8 = 泛光金字塔。
- **离线验证工具** `tools/sim_rim_glow.py`（numpy 逐段复刻四个 pass，输出八格对照图 `docs/rim-glow-sim.png`）。这一步抓到两个读代码看不出的缺陷：`GlowPyramid.plan` 多算一级导致峰值摊平、`uWeight=0.72` 使最粗两级几乎不可见。两处已修正。
- **测试**：317 个用例 / 49 个测试类全绿（本迭代新增 `EmissiveColorTest`、`GlowPyramidTest`、`ShaderUniformSendContractTest`，并扩充 `ShaderAlphaContractTest`）。
- 详细设计与调参见 [docs/RIM-GLOW.md](docs/RIM-GLOW.md)。

## v0.7.0 — 跨域融合（规格 8）

- **3D LUT 颜色分级**：`.cube` 解析 + CPU 三线性采样；4 套内置 LUT（暖光室内 / 冷光夜景 / 阴天户外 / 逆光，由 `tools/gen_luts.py` 纯标准库生成）；选 LUT 规则（色温分档 + 过渡带插值 + 逆光优先）下沉为纯函数 `LutChoice`。
- **MKL 最优传输和谐化**：纯 Kotlin 闭式解（均值 + 协方差 → 12 维仿射 A/S，3×3 特征分解 Jacobi 迭代），无需权重、永不失败，是默认增强。
- **PCT-Net 端侧和谐化**（备选）：CVPR 2023，权重免申请直接下载；低分辨率预测逐像素颜色变换参数、应用到全分辨率前景；仅高端机启用，8s 超时回退 MKL。结构就位，权重（PyTorch `.pth`）需转 LiteRT，本仓库不提供转换后权重。
- **NPU 探测链**：`RuntimeProvider` 升级为 QNN→NPU→GPU→CPU，QNN/NPU 用反射探测（加 Google Maven litert-* 依赖后反射命中、代码零改动），结果缓存，禁止 NNAPI。
- 执行顺序：颜色协调 → 3D LUT → PCT-Net（可选）→ 四色渐变 → 边缘打光 → 辉光 → 泊松 → 回贴 → 阴影。
- **已排除模型**：FLUX.2-klein-4B（4B 需约 13GB VRAM）、DreamLite（权重需邮件申请 + 依赖文本编码器，已换 PCT-Net）。

## v0.6.0 — 高级光影融合（规格 7 + 7.1a）

- **四色渐变环境光**（`LightWrapGradient`）：四区中位色 → 垂直 + 水平 + 四角双线性三路加权 → Soft Light，让"上蓝下绿"这类复杂环境色进得来。
- **边缘打光**（`RimLight`）：距离变换梯度求外法向 + 3D 光源方向（elevation 通过 cos(elev) 衰减水平分量）+ 点积^1.5 权重。
- **辉光**（`Glow`）：高亮掩膜 → 高斯 → Screen；**阴影动态投位**（`ShadowPlacementRule`）：逆光投前方 / 侧光沿光 / 顺光接触阴影。
- 规则全部下沉为纯函数 `LightFxRules` / `ShadowPlacementRule`（可 JVM 单测），UI 与管线共用同一套。
- 修掉 RimLight 法向取反 bug（`∇soft` 应取负才是外法向）与多余的拉普拉斯过滤。
- 辉光与环境色仅导出且非低端机执行；三个新滑杆（边缘光/辉光/环境色）拖 0 即关。

## v0.5.0 — 边缘预处理（规格 6，白边修复）

- 反预乘 → 去白（只改 RGB）→ alpha 柔化（只改 alpha）→ 重新预乘，专治"透明玻璃白边"。
- `feather_px = clamp(0.015×min(w,h), 4, 20)`；`prepareFrog` 早期"已带 alpha 就 return"的白边高发场景也已修复。
- 白边验收 **35.9 → 0.8**（验收线 <15），`FrogEdgeQualityTest` 把"放大目测"变成断言。
- 把之前"死代码"（分块融合、偏好高质量）真正接进产品。

## v0.4.0 — 融图机制（双轨制 + 结构重参数化）

- **双轨制**：经典（泊松 + α 回贴）/ 细节保真（重参数化），低端机无条件走经典，中/高端默认细节保真可关闭，`RenderResult.backend` 如实上报。
- **结构重参数化**（`RepConv`）：异构多分支 5×5/3×3/1×3/3×1/1×1 → 折叠单路径，含有效核自动收缩。
- **细节保真**（`RepDetailRefiner`）：52 参数闭式多尺度高通，DC 增益严格 0，只在过渡带生效。
- **一维 LUT**：cbrt/pow 查表（实测 2.81× 加速、ΔE76 0.0006）。

## v0.3.0 — P3（手动调节 + 档位自适应 + 分块融合）

- 手动调节面板（8 项滑杆，可逐项"恢复自动"）；融合档位运行时自适应（超时降档且不回升）。
- 档位选择器（自动 / 720p / 540p / 多频段 / 全分辨率分块，选择持久化）；分块融合（25% 重叠 + 汉宁窗）。

## v0.2.0 — P2（方向性阴影 + 泊松融合）

- 方向性阴影（沿光扫掠，轮廓跟随形状）；泊松融合三档；边缘三段式 α 加权回贴；掩膜腐蚀/羽化。

## v0.1.0 — P0/P1（骨架 + 光照协调）

- 相机拍照、相册选图、素材库（授权门控）、色键抠图、拖动缩放旋转、撤销/重做、保存分享。
- 光照估计（CV + LiteRT 双实现）、色温互逆函数对、颜色/亮度协调（Reinhard + lerp）、轮廓光。
