# 发布指南：素材列表管理 + Release 构建

> 对应产物：`dist/dimension-blend-v1.0.0-release-unsigned.apk`（66.0MB）、
> `dist/dimension-blend-v1.0.0-step3-debug.apk`（75.9MB）
> 相关文件：`app/build.gradle.kts`、`app/proguard-rules.pro`、
> `feature-editor/.../MaterialItem.kt`、`EditorViewModel.kt`、`EditorScreen.kt`

---

## 一、素材列表的彻底删除

### 1. 数据结构：为什么 `MaterialItem` 里**没有** Bitmap

需求原话是「MaterialItem 包含 Uri、Bitmap、Id」。这条我改了，理由是内存：

```
10 张 1024² 的 ARGB_8888 素材 = 10 × 1024 × 1024 × 4B ≈ 40MB 常驻
而列表里真正要显示的只有 56dp 的缩略图
```

所以分三层：

| 层 | 内容 | 生命周期 |
| --- | --- | --- |
| `MaterialItem` | id / 名字 / `builtIn` / uri | 几十字节，随列表常驻 |
| 缩略图 | 128px，UI 里 `remember(item.id)` 持有 | 跟随组合，滚出屏幕即释放 |
| 全尺寸位图 | **只有选中那一张**（`frogBitmap`） | 换素材/删除时回收 |

好处是「删除要回收内存」这件事变得可判定：被删项本身没有位图，
需要回收的永远只有「它如果正被选中」的那一张。

### 2. 状态

```kotlin
data class EditorUiState(
    val materials: List<MaterialItem> = emptyList(),
    val selectedMaterialId: String? = null,   // 唯一的选择事实来源
    val frogBitmap: Bitmap? = null,           // 它的物化结果
)
```

原 `frogAsset` 字段已删除 —— 它和 `selectedMaterialId` 会是两个事实来源，
"列表高亮的是 A、画布上是 B"这种不同步在编辑器里最难查。

### 3. 删除：先出列表，再做 IO

```kotlin
fun removeMaterialFromList(id: String) {
    val index = materials.indexOf { it.id == id }; if (index < 0) return
    val wasSelected = selectedMaterialId == id
    val remaining = materials - item

    // ① 列表立刻消失（同时清掉指向已删 id 的选中态）
    _state.update { it.copy(materials = remaining,
                            selectedMaterialId = if (wasSelected) null else it.selectedMaterialId,
                            message = "已删除「${item.displayName}」") }

    viewModelScope.launch {
        // ② 落盘：只有导入素材有文件；内置素材只从本次会话移除
        if (!item.builtIn) importedFrogRepository.delete(item.id)
        if (!wasSelected) return@launch
        // ③ 接续：优先"被删项的前一个"（用户视线还在原位置），否则第一个；空了就清画布
        if (remaining.isEmpty()) { clearForegroundMaterial(); return@launch }
        installMaterial(remaining.getOrNull(index - 1) ?: remaining.first())
    }
}
```

**为什么先更新列表**：IO + 解码要几十到几百毫秒。等它们做完再更新，
用户会看到"点了 ×，缩略图还杵在那儿"—— 最典型的"点了没反应"。

### 4. 回收时序：三道保险

```kotlin
retireBitmaps(bitmap)          // 登记，不立即释放
  → delay(600ms)               // ① 让 Crossfade(300ms) 把旧帧画完
  → awaitPipelineIdle()        // ② 等真的读完位图的渲染退出（超时 5s 就放弃回收）
  → recycle()
```

第 ② 步是本轮补的**真 bug**：`Job.cancel()` 只是请求取消，而渲染管线内部没有取消检查点，
一张已经在算的图会继续读 `frogBitmap`。此时 `recycle()` 让 native 层读到已释放内存 ——
**SIGSEGV，不是 Java 异常，抓不到 Kotlin 堆栈**。现在 `awaitPipelineIdle()` 用
`renderJob.join()` + `fuseMutex.withLock{}`（融图全程持锁）确认管线退出；
超时则**跳过回收**：泄漏一张位图可恢复，野指针崩溃不可恢复。

### 5. UI：角标必须自己吃掉点击

```
Box(56dp)                                 ← 不裁切的容器
  ├─ Box(fillMaxSize().clip(RoundedCornerShape(10.dp)))   ← 缩略图（被裁切）
  └─ Box(align = TopEnd).clickable { onRemoveMaterial(id) } ← × 角标（在裁切之外）
```

两个细节：角标若放在裁切层里，右上角会被切掉；外层 `Column` 上有"选中"的
`clickable`，角标自带 `clickable` 才能在命中测试里抢到这次点击 ——
否则"点 ×"会先触发选中，画布闪一下换人。

### 6. 契约测试

`MaterialListContractTest`（9 条）钉住：列表项无 Bitmap、选中只存 id、
删除先出列表再 IO、选中项被删要清选中态、接续优先前一个、只有导入素材落盘、
换素材回收旧图、回收前等管线空闲、角标自带点击消费。
**反向验证**：把接续改成"直接选第一个"、去掉 `!item.builtIn` 判断 → 4 处失败。

---

## 二、日志：为什么不用 `if (BuildConfig.DEBUG)`

### 实测分布（127 处）

| 模块 | 数量 | | 级别 | 数量 |
| --- | --- | --- | --- | --- |
| core-cv | 37 | | `Log.i` | 33 |
| core-ai | 22 | | `Log.w` | 94 |
| core-render | 20 | | `Log.d` | **0** |
| core-data | 15 | | `Log.e` | **0** |
| feature-editor | 14 | | `Log.v` | **0** |
| core-pipeline | 11 | | | |
| core-common | 8 | | | |
| **app** | **0** | | | |

三个结论：

1. **没有 `Log.d` / `Log.e`**（需求点名的那两个），只有 i/w。
2. **`app` 模块一处都没有** —— 所以"在 app 的 BuildConfig.DEBUG 里包一层"根本覆盖不到这些日志。
   `BuildConfig` 是**模块级**的，库模块默认不生成（AGP 8 起 `buildFeatures.buildConfig = false`），
   要逐点包装就得给 7 个库模块全部打开 buildConfig。
3. 全部 i/w 都在**失败/降级分支**或**整次渲染结束后的汇总**里，不在 per-pass / per-pixel 循环内。

### 采用方案：R8 `-assumenosideeffects`

```proguard
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
```

它比 `if (BuildConfig.DEBUG)` 更彻底：在**字节码层**删掉整个调用，**连同参数求值**
（`Log.i(TAG, "x=${a}")` 的字符串拼接与临时对象都不生成）；而 `if` 只是执行时跳过，
代码与常量池仍然在包里。

**保留 `Log.w`**：它们全在失败分支（运行时开销 0），却是现场定位
"为什么这台手机光影没了"的唯一线索。要绝对静默，把规则里 `public static int w(...);`
那行取消注释即可。

### 实测证据（不是推断）

| | dex 大小 | `"FBO 池：峰值"`（Log.i） | `"P2-b 立体反光失败"`（Log.w） |
| --- | --- | --- | --- |
| debug | 27.5 MB | 存在 | 存在 |
| release | **3.1 MB** | **不存在** | 存在 |

---

## 三、R8 / ProGuard

`buildTypes.release` 原本已开 `isMinifyEnabled` / `isShrinkResources`，本轮补的是**规则本身**：

| 类别 | 规则 | 说明 |
| --- | --- | --- |
| JNI | `-keepclasseswithmembernames class * { native <methods>; }` | 名字被改 = `UnsatisfiedLinkError`，且只在首次调用时炸 |
| OpenCV | `-keep class org.opencv.**` | JNI + 静态初始化 |
| gpuimage-plus | `-keep class org.wysaid.**` | **该 AAR 没带 consumer-rules.pro**，不兜就是裸奔 |
| LiteRT/MediaPipe | `-keep class com.google.** / org.tensorflow.lite.**` | 端点与 native 绑定 |
| 反射 | `-keep` + `-dontwarn` `org.tensorflow.lite.qnn/npu.**` | `RuntimeProvider` 用 `Class.forName` 探测可选 NPU 委托，这些类在公开 AAR 里不存在 |
| 光影链路 | `-keep class com.naiwa.core.cv.gles.** / core.render.**` | **技术上不必要**（无反射、无 RegisterNatives、shader 是字符串常量），保留是因为它是全项目唯一无法在 CI 验证的运行路径，一次重命名事故 = 用户手机花屏 |
| 崩溃栈 | `-keepattributes SourceFile,LineNumberTable` | 否则线上只有 `a.a.a(Unknown Source)`；配合 `mapping.txt` 可还原到行 |
| 噪音 | `-dontwarn`（slf4j / javax.annotation / conscrypt …） | 不写会让 R8 直接构建失败 |

上游模块已各自声明：`:core-cv` 全量 keep OpenCV + `com.naiwa.core.cv.**`，
`:core-ai` keep LiteRT/MediaPipe，`:core-common` keep model，`:core-pipeline` keep 自身。
**新增库时先看它有没有 consumer-rules.pro，没有就在这里补。**

### 首次构建真实踩到的坑

`GpuDelegate` 的字节码引用了 `GpuDelegateFactory$Options`，但 `tensorflow-lite-gpu:2.16.1`
的公开 AAR 里**没打包这两个类** → R8 报 `Missing classes detected`。
按 AGP 生成的 `missing_rules.txt` 补 `-dontwarn`。**为什么这样安全**：
我们只调用无参构造 `GpuDelegate()`，而 `RuntimeProvider.tryCreateGpuDelegate()` 是
`catch (t: Throwable)` 兜住的 —— 即便将来内部改到那条路径，`NoClassDefFoundError`
也只会让它降级到 CPU，不会闪退。

---

## 四、签名：从 keystore 到安装包

### 1. 生成 keystore（一次性，**丢了这个文件就再也无法更新应用**）

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias dimension \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name, OU=Mobile, O=Your Org, L=Shanghai, ST=Shanghai, C=CN"
```

- `-validity 10000` ≈ 27 年。Google Play 要求有效期至少到 2033 年。
- 生成过程会交互式询问密码；**别把密码写进任何提交到仓库的文件**。
- 立刻做两件事：① keystore 放进加密备份（丢了就不能推送更新，只能换包名重发）；
  ② 密码存进团队密码管理器。

### 2. 写进 `local.properties`（`.gitignore` 已包含，不进仓库）

```properties
RELEASE_STORE_FILE=release.jks          # 绝对路径，或相对"工程根目录"
RELEASE_STORE_PASSWORD=********
RELEASE_KEY_ALIAS=dimension
RELEASE_KEY_PASSWORD=********
```

### 3. `app/build.gradle.kts`（已配置好，无需再改）

```kotlin
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStorePath = localProps.getProperty("RELEASE_STORE_FILE")?.trim()?.takeIf { it.isNotEmpty() }

android {
    signingConfigs {
        if (releaseStorePath != null) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
                enableV1Signing = false      // minSdk 26，v1 无意义
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")   // 没配就是 null → unsigned
        }
    }
}
```

**只在四个键齐全时才创建签名配置**：否则没配签名的协作者/CI 连"验证 R8 能不能跑通"都做不了
（`storeFile` 为 null 会让 `assembleRelease` 直接失败）。

### 4. 打包

```bash
./gradlew :app:assembleRelease      # APK → app/build/outputs/apk/release/app-release.apk
./gradlew :app:bundleRelease        # AAB → app/build/outputs/bundle/release/app-release.aab（上架 Play 用这个）
```

没配签名时产物叫 `app-release-unsigned.apk`（不能直接安装，可用于验证 R8）。

### 5. 校验（本轮已实测）

```bash
apksigner verify --print-certs -v app-release.apk
```

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate DN: CN=…, C=CN
```

> 本轮用一把**临时** keystore 跑通了整条链路（v2/v3 均通过），验证后已删除，
> 仓库里没有留下任何密钥。你只需按上面三步换成自己的 keystore。

---

## 五、体积：R8 省的是 dex，不是 so

| 项 | debug | release | 变化 |
| --- | --- | --- | --- |
| dex | 27.5 MB | **3.1 MB** | −89% |
| APK | 75.9 MB | **66.0 MB** | −13% |
| 其中 .so | — | **45.7 MB** | 占包体 69% |

APK 只降 10MB 是因为原生库压不动：`libopencv_java4.so` 两个 ABI 共 **34.2MB**
（arm64 19.9 + armv7 14.3），`libtensorflowlite*.so` 共 8.4MB。
**dex 已经从 27.5MB 砍到 3.1MB，"再靠混淆省体积"没有空间了，剩下的全在 ABI。**

两条可选路线（**未默认开启**，因为会改变产物命名，需要你确认分发方式）：

```kotlin
// 方案 A：按 ABI 拆包（发给别人只发 arm64 那一个，约 45MB）
android {
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true      // 仍产出一份全量包兜底
        }
    }
}

// 方案 B：直接只打 arm64（包最小，但 32 位老设备装不上）
defaultConfig { ndk { abiFilters.clear(); abiFilters += "arm64-v8a" } }
```

上架 Google Play / 国内商店时用 **AAB**（`bundleRelease`），商店会按设备 ABI 自动下发，
等效于方案 A 且不用自己管矩阵。

---

## 六、应用信息核对（本轮实测）

| 项 | 实测值 | 结论 |
| --- | --- | --- |
| `android:label` | `application-label:'次元溶图'` | ✅ |
| `android:icon` / `roundIcon` | `res/mipmap-anydpi-v26/ic_launcher.xml` | ✅ 自适应图标（青橙双环） |
| 权限（`aapt2 dump permissions`） | `WRITE_EXTERNAL_STORAGE`（maxSdk 28）+ androidx 的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | ✅ 无 INTERNET / CAMERA / READ_MEDIA_IMAGES |
| `versionCode` / `versionName` | 100 / 1.0.0 | ✅ |
| minSdk / targetSdk | 26 / 34 | ✅ |
| 签名 | （待你配置）unsigned | ⬜ 需你补 keystore |

**关于"读取相册权限"**：不需要。导入走 Photo Picker（`PickVisualMedia`），
它在所有版本都由系统进程代理，**应用侧零权限**；保存走 MediaStore，
Android 10+ 免权限，只有 Android 8/9 需要 `WRITE_EXTERNAL_STORAGE`。
`aapt dump badging` 会多打一行 `READ_EXTERNAL_STORAGE` ——
那是 aapt1 的**隐式推导**（看到 WRITE 就补 READ），`aapt2 dump permissions`
与 `xmltree` 都证明清单里**没有**这条。

---

## 七、发布前手工验收（真机，10 分钟）

1. **图标**：桌面/最近任务/通知栏三处都应是青橙双环，圆形遮罩不切边。
2. **冷启动**：不应闪白（`windowBackground` 已改 `#0B0E14`）；状态栏图标为浅色。
3. **素材删除**：导入 3 张 → 删中间那张 → 应自动选中它前一张；删到空 → 画布回纯背景、位置归零。
4. **空渲染拦截**：删空后点「一键融合」→ 提示"请先导入人像素材"，不崩、不黑屏。
5. **连续快速删**：连点 ×，不应崩（回收有 600ms + 管线空闲双闸）。
6. **降级可见性**：`adb logcat | grep NaiwaCamera`，release 包应**看不到** `Log.i` 的内容，
   但 `GL 错误` / `图层管线失败`（w）仍可见 —— 这是刻意的。
7. **AI/GPU 路径**：首次融合日志里确认后端（GPU Shader / CPU）与档位，避免"没报错但一直在跑 CPU"。

## 八、对外分发：GitHub Release + 自动发布

目标：别人打开仓库页面 → 右侧 **Releases** → 点进去直接下载 APK 安装。不需要 clone、不需要装 Android Studio。

### 1. 为什么不能直接把 APK 提交进仓库

- `dist/` 与 `*.apk` 在 `.gitignore` 里，且单文件 69MB —— 进 git 会让仓库从几 MB 变几百 MB（且永久留在历史里）。
- GitHub Release 的附件是**独立存储**，不占仓库体积，还自带下载计数。

### 2. 一次性配置：4 个签名 Secrets

**keystore 永远不进仓库**（`*.jks` 已在 `.gitignore`）。CI 需要它时才从 Secrets 取。

```bash
# 在你本机执行（keystore 放在仓库之外，例如 ~/.release-keystore/）
base64 -w0 ~/.release-keystore/dimension-release.jks > /tmp/ks.b64
cat /tmp/ks.b64   # 复制全文
```

仓库 **Settings → Secrets and variables → Actions → New repository secret**，加 4 个：

| Secret | 值 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | 上一步 `ks.b64` 的全文（一行，无换行） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | `dimension` |
| `RELEASE_KEY_PASSWORD` | 同 keystore 密码（PKCS12 两者相同） |

> ⚠️ **keystore 与密码丢了就再也无法给已安装用户推送更新**（签名不同 → 系统拒绝覆盖安装，只能卸载重装）。至少备份两处。

没配这 4 个 Secret 也能跑 CI，只是产出 `unsigned` 包：能验证 R8 与单测，**不能安装、也不会发布**（`配置 release 签名（可选）` 这一步会打 warning 说明）。

### 3. 发版：推一个 tag 就够

```bash
git tag v1.0.1
git push origin v1.0.1
```

CI 会自动：

1. **拉取 LFS 真模型**（`lfs: ${{ startsWith(github.ref, 'refs/tags/v') }}`）。
   普通分支构建保持 `lfs: false` 省带宽，但 tag 必须拉真文件，否则 APK 里是 132 字节的 LFS 指针，
   装上去和谐化直接失效。
2. 用 tag 生成版本号：`VERSION_NAME=<tag 去 v>`、`VERSION_CODE=<run_number>`。
   **versionCode 必须单调递增**，否则手机端覆盖安装会报「应用未安装」。
3. `:app:assembleRelease` 用 Secrets 里的 keystore 签名。
4. **发布前硬校验**（任一不满足就红，宁可不发）：
   - 产物不是 `unsigned`
   - `apksigner verify` 通过
   - `aapt2 dump permissions` 里**没有 `INTERNET` / `CAMERA`**
   - APK 内 `pct_net_vit.tflite` > 1MB（不是 LFS 指针）
5. 建 Release，附件为 `DimensionBlend-v1.0.1.apk` + `.apk.sha256`。

### 4. 手动兜底（不想等 CI 时）

本地构建并上传到网页：`bash tools/build-local.sh :app:assembleRelease`，
产物在 `app/build/outputs/apk/release/app-release.apk`（已签名）。
GitHub → **Releases → Draft a new release** → 选/建 tag → 拖入 APK → Publish。

### 5. 用户侧看到什么

Release 说明里已写明：Android 8.0+、arm64-v8a / armeabi-v7a、需允许「安装未知应用」、
**无 `INTERNET` 权限**、`sha256sum -c` 校验方法。装完桌面图标「次元溶图」。
