# 体验细节：启动图标 + 素材清空

> 对应版本：`dimension-blend-v1.0.0-step2-debug.apk`
> 相关文件：`app/src/main/res/drawable/ic_launcher_*.xml`、`app/src/main/res/mipmap-anydpi-v26/`、
> `feature-editor/.../EditorViewModel.kt`、`EditorScreen.kt`、`tools/sim_launcher_icon.py`

---

## 一、启动图标（纯 Vector Drawable，零位图）

### 1. 设计

| 元素 | 含义 | 为什么这么画 |
| --- | --- | --- |
| 青环（Teal，左下） | 背景 / 次元 | 描边环在 48dp 下仍能看出"两个图层叠着"，实心圆叠起来只会糊成一坨 |
| 橙环（Orange，右上） | 人像 / 光照 | 青橙对比是这个产品的色彩语言（暖主光 + 冷环境），也天然区分两层 |
| 交点白光 | 两束光的交点 = "溶"发生的地方 | 全图**唯一**接近纯白的地方，把视线钉在中心 |
| 背景双色辉光 | 青在左下、橙在右上 | 与前景同侧同色相 → 环像"浮在光里"，而不是贴在死黑板上 |

图形全部落在中心 **66×66 安全区**内（实测最大半径 **30.73** < 33），
所以圆形 / 圆角方形 / squircle 任何遮罩下都不会被切边。

### 2. 文件

```
res/drawable/ic_launcher_background.xml   对角深色渐变 + 两团辉光（radial gradient）
res/drawable/ic_launcher_foreground.xml   两个环 + 交点高光（stroke 用 linear gradient）
res/drawable/ic_launcher_monochrome.xml   Android 13+ 主题图标（纯白剪影，只取 alpha）
res/mipmap-anydpi-v26/ic_launcher.xml     <adaptive-icon> 组装
res/mipmap-anydpi-v26/ic_launcher_round.xml
```

`minSdk = 26`，所以**只需要 `anydpi-v26` 一份**，不需要按 dpi 出一堆 png ——
那是 API 26 之前的包袱。Manifest 里 `android:icon` / `android:roundIcon` 都指向它。

### 3. 两个容易踩的坑

**① 颜色是 `#AARRGGBB`，不是 `#RRGGBBAA`。**
第一版预览脚本按 CSS 顺序解析，把"透明橙 `#00FFC078`"读成了"不透明青绿"，
于是前景 alpha 铺满整张画布、安全区检查报出半径 75.66。XML 本身是对的，脚本错了。
**看到"整块色板"式的异常，先怀疑颜色顺序。**

**② 单色图层不能带渐变。**
系统只取 alpha 再整体染色，渐变在单色化之后会变成深浅不一的灰，反而脏。

### 4. 验证

`python tools/sim_launcher_icon.py`（numpy 逐 path、逐 gradient 端点复刻两个 XML，
再用真实启动器遮罩裁切）→ `docs/launcher-icon.png`：

```
前景图形最大半径：30.73（安全区 33.00）  OK
两环圆心距：20.59（半径和 34）→ 相交，有叠加区
叠加区宽度：13.41px（占单环直径 39%）
两环内侧净间距：6.91px（48dp 时 ≈3.04px，仍分得开）
```

图中上排：圆形遮罩 / 圆角方形 / 背景层 / 前景层；下排：48 · 72 · 96 dp 逐级观感。
**改图标必须同步改这个脚本**，否则预览会骗人。

### 5. 顺手修掉的启动割裂

`values/themes.xml` 还是转型前的浅色主题（`windowBackground #FFFBF2` +
`windowLightStatusBar true`）。深色应用点开新图标的第一眼会闪一下奶油白，
系统处于浅色模式时状态栏图标还会是深色（压在深色画布上等于看不见）。

现在：`Theme.Material.NoActionBar` + `windowBackground #0B0E14`（与图标底色一致）+
两个 `windowLightXxxBar = false`；`MainActivity` 侧同步
`enableEdgeToEdge(SystemBarStyle.dark(...))` —— `enableEdgeToEdge()` 的默认策略是
**跟随系统深浅色**，不是跟随应用主题，所以必须显式指定。

---

## 二、素材清空（删除已导入的人像）

### 1. 为什么不是"把字段置 null"就完事

四件事，**顺序不能换**：

| 步骤 | 不做会怎样 |
| --- | --- |
| ① 取消在途任务（`renderJob` / `settleJob` / `fuseJob` / `sliderJob`） | 清空之后才完成的那一帧会把人物又贴回来 —— "点了删除，人像自己回来了" |
| ② 状态重置（`frogBitmap = null`、`previewBitmap = null`、`placement = DEFAULT`） | 画布上留着上一张融合结果；下次导入落在上一张的位置和缩放上 |
| ③ 清历史（`history.clear()`） | 历史只存 `placement`/`overrides`，撤销它等于"在空画布上恢复一个坐标"，按了一下什么都没发生 |
| ④ 延迟回收 | 见下 |

`previewBitmap` 置 **null** 而不是塞 `scenePreview`：UI 的
`previewBitmap ?: scenePreview` 本来就会回落到纯背景；塞同一个引用会让
"这张能不能回收"的判断立刻变复杂（要和 scene/drag 做同一性比较）。

### 2. 为什么不能立即 `recycle()`

编辑页用 `Crossfade(300ms)` 切换预览位图。刚被换下来的那张，在这 300ms 内**每帧仍在被绘制**；
对正在绘制的 Bitmap 调 recycle 会抛 `Canvas: trying to use a recycled bitmap`，直接崩编辑器。

所以回收走 `retireBitmaps()`：先登记，`delay(600ms)`（= 300ms 动画 + 一帧余量 + 抖动空间）
后统一释放，并且跳过与 `scenePreview` / `sceneDrag` 同一实例的位图（那两个是全程共用的，
回收等于把背景也删了）。

### 3. 还有一层竞态：管线不检查取消点

`Job.cancel()` 只是"请求取消"。本工程的渲染管线内部没有取消检查点，一张已经在算的图
照样会算完并回调。所以 `renderOnce` / `commitRenderResult` 在**写 state 之前**各加了一道：

```kotlin
if (current.frogBitmap == null) {      // 清空之后到达的结果 = "清空之前的世界"
    runCatching { if (!resultBitmap.isRecycled) resultBitmap.recycle() }
    _state.update { it.copy(isFusing = false, isRendering = false) }
    return
}
```

### 4. 空渲染拦截

没有人像时**不禁用**「一键融合」按钮：禁用态说不出原因，用户只会以为界面坏了。
按钮点得动，由 `triggerFullRender()` 第一行拦截并提示「请先导入人像素材」。

### 5. UI

顶栏「导入」按钮右侧新增红色垃圾桶（`Icons.Default.Delete`），
`AnimatedVisibility(visible = state.frogBitmap != null)` + 横向展开/淡入。

用 `AnimatedVisibility` 而不是 `if`：它紧挨着「导入」按钮，突然多一个按钮容易误点，
横向展开给了眼睛一个提示。删除不弹二次确认 —— 素材是"重新选一张"就能恢复的东西，
弹窗只会让清理变慢，结果由 Snackbar 回报（`message = "已清除人像素材"`）。

### 6. 契约测试

`feature-editor/src/test/.../ClearMaterialContractTest.kt`（9 条，源码扫描）：

- 取消渲染必须发生在状态重置**之前**（顺序反了就是"人像自己回来"）
- `clearForegroundMaterial` 内**禁止**出现 `.recycle(`
- `retireBitmaps` 必须先 `delay` 再 recycle；必须排除 scene/drag
- 回收窗口必须 > 300ms（淡出动画）
- 位置与 `previewBitmap` 必须一起复位
- `fuseJob` 必须被取消；`renderOnce` / `commitRenderResult` 提交前必须核对素材是否已被清空
- `triggerFullRender` 必须拦截空渲染
- 删除按钮必须由 `frogBitmap != null` 控制显隐

**三次反向验证**：把 cancel 挪到 state 更新之后 → 失败；在清空里塞一句 `oldFrog?.recycle()` → 失败；
把 `frogBitmap == null` 改成 `false` → 3 处失败。改完恢复，414 用例 / 59 类全绿。
