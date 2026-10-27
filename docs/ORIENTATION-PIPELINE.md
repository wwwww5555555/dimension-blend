# 素材翻转 + 管线中断：根因与修复（v0.32）

> 现象：点「一键融图」后①素材上下颠倒；②后面的光影/阴影像是被跳过了，人物飘在空中。

这两个症状**不是两个 bug**，而是同一件事的两面：

```
一键融图
 └─ GlesLayerPipeline（9 个全分辨率 pass，v0.26+ 全套光效）
      └─ 任何一步返回 null  ──►  降级 GlesFusionRenderer（legacy 3-pass）
                                     ├─ 它的 readPixels 多翻了一次   → 画面上下颠倒
                                     └─ 它本来就没有发光链/向内包裹/接触阴影
                                                                     → "光影被跳过"
```

关键点：**"走哪条路径"由一个运行时 GL 错误决定**，两条路径的回读方向还相反。
单元测试不可能同时覆盖，所以这个问题只会在真机上以"素材翻了"的形式暴露。

---

## 一、Y 轴翻转：为什么"三份实现都对"却还是错

### 1.1 三份回读，两个方向

| 文件 | 旧实现 | 实际效果 |
| --- | --- | --- |
| `GlesLayerPipeline.readPixels` | 直接 `copyPixelsFromBuffer` | **正确** |
| `GlesFusionRenderer.readPixels` | 逐行倒序后再 copy | **上下颠倒** |
| `RenderGraphExecutor.readPixels` | 逐行倒序后再 copy | **上下颠倒**（当时 DAG 还没接进主链，所以没人发现） |

三份代码各自都有"看起来很有道理"的注释，但方向的真相只有一个。

### 1.2 正确的推导（三步已经把方向抵掉了）

```
① 上传：GLUtils.texImage2D 把 Bitmap 第 0 行（顶部）放到纹理 t = 0
② 顶点：vTexCoord = p；gl y = -1（帧缓冲**底部**）对应 t = 0
        → 帧缓冲底部画的是原图顶部
③ 回读：glReadPixels 从帧缓冲**底部**开始逐行返回
        → 缓冲区第 0 行 = 原图第 0 行
```

三步首尾相接，**整圈是恒等的**：直接 `copyPixelsFromBuffer` 就是正的，再翻一次才是颠倒。
`tools/sim_orientation.py` 把这三步写成了索引映射并断言结果（`回读首行 = 原图第 0 行`）。

### 1.3 但不再依赖推导：首次调用做一次真实探测

推导依赖两个驱动行为。它们都是规范行为，可个别国产 ROM 的 GLES 驱动确实翻过车。
所以 `GlReadback` 在**首次调用**时用 2×2 的已知图案跑一遍真实的「上传 → 绘制 → 回读」：

```
图案：第 0 行纯红 / 第 1 行纯蓝（左右对称，只测纵向）
读回缓冲区第 0 个像素：
  红 → 首行就是原图首行 → 不翻转（= 规范推导的结论）
  蓝 → 驱动行序与规范相反 → 翻转
  其它/失败 → 退回规范推导（不翻转），并写一条 Log
```

探测约 1ms、进程内只跑一次，结果同时写进 `DebugReport.readbackFlip`。
**方向从此只有一个来源**，任何渲染器都不允许再自己倒序行（有静态契约测试拦着）。

### 1.4 顺手修掉的两个隐藏问题

- **回读不再依赖"当前绑定"**：`GlReadback.read(fbo, w, h)` 自己 `glBindFramebuffer`。
  旧版图层管线依赖"上一 pass 恰好还绑着正确的 FBO"，一旦乒乓交换被跳过就会读到另一张纹理
  （表现为整图错位，而不是报错）。
- **`glPixelStorei(GL_PACK_ALIGNMENT, 1)`**：RGBA8 每像素 4 字节本来不会触发，但换个格式复用
  这个函数时行会被补齐错位 —— 提前堵住。

---

## 二、管线中断：四种"静默中断"的写法

### 2.1 一个 GL 错误就让整帧放弃

```kotlin
// 旧
draw(programs["emit"]!!, ...) { ... } ?: return null      // ← 整帧返回 null
```

`draw()` 只要 `glGetError() != GL_NO_ERROR` 就返回 null。于是**任何可选光效**的一个 GL 错误
（某个驱动不接受某条指令、某张纹理分配失败）都会让整帧降级。

现在按"必需 / 可选"分级：

| 类别 | pass | 失败后 |
| --- | --- | --- |
| 必需 | `key` / `p1` / `p2` / `p2c` / `composite` / `fxaa` | 整帧返回 null（GPU 不可信，交给 CPU） |
| 可选 | 发光链（EMIT/高斯/上采样）、`glow`、`p2b`、`lightwrap`、`dof` / `grade` / `looks` / `vignette` | 记录日志 + 该层换成 1×1 黑纹理，**其余照常** |

可选层失败必须换成黑纹理而不是"留着不动"：刚分配的纹理内容是**未定义**的，
直接 Screen 进来会变成彩色噪点或上一帧残影，比黑屏更难判断。

### 2.2 后处理乒乓链失败后仍然交换

```kotlin
// 旧
draw(prog, altFbo, ...) { ... }     // 返回值没人看
swap(cur, alt)                      // 无论成功与否都换
```

`altTex` 没写完就换过去，后面每个 pass 都在读脏数据，最终图是残影/纯色块。
现在失败**不交换**，沿用上一 pass 的结果继续跑。

### 2.3 DAG 里一个节点失败就中止整张图

```kotlin
// 旧（RenderGraphExecutor.execute）
plan.passes.forEach { pass ->
    val slot = ensureSlot(pass.node.id, width, height) ?: return null   // ← 非局部返回
    renderNode(pass, project, sources, slot)                            // 返回值 Unit：失败也说不出话
}
```

两个问题叠在一起：

1. `return null` 在 `forEach` 的 inline lambda 里是**非局部返回**，直接退出 `execute` ——
   一个槽位分配失败（显存不足）就整张图放弃 GPU，而那正是最需要"降级继续画"的时刻；
2. `renderNode` 返回 `Unit`，内部 `?: return` 是**局部返回**：节点没画，槽位保持未初始化，
   DAG 却认为它成功了，下游一路读脏数据。

现在的结构（`execute`）：

```kotlin
var failed = 0
for (pass in plan.passes) {                    // 普通 for：没有非局部返回
    if (reusable) continue                     // 命中缓存
    val slot = ensureSlot(...)
    if (slot == null) { failed++; continue }    // ① 建不出槽位：跳过这个节点，继续
    val ok = try { renderNode(...) }            // ② 节点异常：捕获，不冒泡
             catch (t: Throwable) { Log.w(...); false }
    if (!ok) { failed++; fallbackFor(...) }     // ③ 失败节点：直通上游 / 清透明
}
if (failed > 0) Log.w("DAG 有 $failed 个节点降级为直通（共 N 个）")
readPixels(slots[outputNodeId])                 // ④ 无论如何把能画的画出来
```

`renderNode` 改为返回 `Boolean`（成功/失败），失败分支一律 `return false`。
`fallbackFor` 的顺序是"**先保证有东西**"：

1. 有上游 → `blit` 上游纹理（语义 = "这个效果没生效"，图还是完整的）；
2. 无上游（SOURCE 节点本身失败）→ 用调用方给的位图上到该槽位；
3. 连位图都没有 → `glClear` 成透明（下游 `over` 混合会保留底图）。

### 2.4 第三方滤镜切上下文没有 finally

```kotlin
// 旧
egl?.unbind()
val filtered = GpuImagePlusNode.apply(...)   // 这里抛一次
egl?.makeCurrent()                            // ← 永远执行不到
```

上下文停在解绑态之后，**每个 GL 调用都失败但不抛异常**，`glGetError` 在没有上下文时也靠不住。
表现就是"后面的光效节点全被跳过，日志里只有一条 filter 异常"。现在恢复动作在 `finally` 里。

---

## 三、FBO 绑定与深度/剔除

`GlState.ensure2dPass()` 在**每个 pass 开始前**强制执行：

```kotlin
glDisable(GL_DEPTH_TEST);
glDisable(GL_CULL_FACE);
glDisable(GL_BLEND);        // 混合全部在片元里显式做，开 GL_BLEND 会让输出 alpha 参与混合
glDisable(GL_SCISSOR_TEST); // 一旦被打开且矩形没重置，只有一小块被绘制 —— 看起来像"渲染被跳过"
glDepthMask(false);
glColorMask(true, true, true, true);
```

**为什么 `GL_CULL_FACE` 必须点名**：全屏 quad 的顶点序是 `(0,0)→(2,0)→(0,2)→(2,2)`，这是
**顺时针**。一旦哪里开了背面剔除，整个 quad 被剔除，FBO 保持上一次内容或全黑，
而 `glGetError()` **不会报错**（剔除是合法行为）。这就是"矩阵/翻转一改，画面就黑，
后续 pass 全读空白纹理"的经典成因。

新建 EGL 上下文里这四项默认都是关的，所以"只设一次"在今天够用；但第三方滤镜自建 EGL、
将来任何一处 `glEnable(GL_BLEND)`，都会让这个前提失效。六个状态位写入的代价远低于一次同步，
每个 pass 设一遍。

---

## 四、顺手加的观测

`FboPool` 现在记账显存：

```
Log.i("FBO 池：峰值 148MB / 22 张 …")
```

整条管线同时持有 9 张全分辨率 RGBA8（2048² 时约 150MB）。如果现场日志里这个数字贴着
设备的 GL 上限，那"某个 pass 分配不到纹理"就是中断的直接原因，而不是 shader 写错了。

---

## 五、验证

### 离线

```
python tools/sim_orientation.py
回读缓冲区首行 = 原图第 0 行（末行 = 原图第 7 行）
  不翻转 → 恒等：OK
  再翻一次 → 变成上下颠倒（首行会变成原图末行）：OK
```

`docs/orientation-sim.png` 四格：上排左＝正常（阴影在脚下）、右＝legacy 双翻（阴影跑到头顶上方，
人倒立）；下排是 2× 局部放大，这一格最接近用户的观感描述"光影好像没了"。

### 静态契约（426 用例 / 61 类全绿）

| 测试 | 钉住什么 |
| --- | --- |
| `GlReadbackTest`（6） | 方向判定、默认不翻转、非法尺寸不碰 GL |
| `GlesReadbackOrientationContractTest`（9） | 三个渲染器必须走同一回读；**禁止任何 `h - 1 downTo 0`**；回读必须显式绑 FBO；每个 draw 必须设状态基线；可选层失败不得拖垮整帧；后处理失败不得交换 |
| `RenderGraphResilienceTest`（6） | pass 循环不得 `return null`；节点必须 try/catch；`renderNode` 必须返回 Boolean；失败必须先直通再清透明；第三方滤镜必须 `finally` 恢复上下文 |

**反向验证做过三次**（注入错误 → 测试失败 → 恢复）：

1. 在 `GlesFusionRenderer` 里加回 `for (y in h - 1 downTo 0)` → 3 处失败；
2. 把 `readPixels(curFbo,…)` 改回只传宽高 → 失败；
3. 把 pass 循环改回 `return null` → 失败。

### 真机检查清单

1. 点一次「一键融图」，看日志里 `回读方向实测：…` 这行 —— 是"无需翻转"还是"已自动纠正"；
2. 若出现 `图层管线失败 → 降级 legacy 3-pass`，说明必需 pass 挂了，把同屏的
   `图层管线中止` / `GL 错误 0x…` / `纹理创建失败` 一起抓下来；
3. 若出现 `发光链…失败` / `P2-b…失败` / `LIGHTWRAP 失败` 而画面其余正常 —— 那是**按设计降级**，
   只少了一个效果，不是管线中断。

---

## 六、遗留

- **DAG 的 `RenderGraphExecutor` 还没接进主渲染链**（`renderGpu` 走的是 `GlesLayerPipeline`）。
  这一轮先把它按同样的容错标准补齐，接进来的时候不用再修一遍。
- `GlesLayerPipeline` 同时持有 9 张全分辨率纹理。真正降峰值需要给中间纹理做生命周期管理
  （P1/EMIT/LIGHTWRAP 在 composite 之后就可以释放），这一轮只加了记账，没动分配策略 ——
  改分配顺序的风险高于收益，等真机日志确认是显存问题再做。
