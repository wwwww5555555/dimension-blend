# 内置奶蛙素材目录

## 当前状态：**空**

本应用**不再内置任何奶蛙素材**。这是刻意为之，不是缺文件：

- 用户从相册导入自己的奶蛙照片即可；
- 导入图自带透明通道 → 直接使用；
- 导入图没有透明通道 → 自动走色键 + 形态学 + 连通域抠图（纯 Kotlin，不依赖 OpenCV），
  并如实报告前景占比，效果不理想时给出提示。

因此启动后素材条是空的，界面会提示「点『从相册导入』选一张奶蛙图」。这属于预期行为。

## 如果以后想重新内置素材

内置素材有硬性合规要求，必须同时满足：

| 要求 | 说明 |
| --- | --- |
| 授权 | 只能是**自绘 / 自有版权 / CC0**。禁止抓取未授权网络图片 |
| 登记 | 必须写入本目录的 `manifest.json` 的 `assets` 数组 |
| `license` 字段 | 取值只能是 `"CC0"` 或 `"self-drawn"`。缺失或非法 → **不加载并打 warning** |
| 格式 | 仅 `.png`；文件名不得含 `/`、`\` 或 `..`（防目录穿越）；带透明通道 |
| 尺寸 | 长边 512 ~ 1024 之间 |

`license` 门控不是"建议"，是红线：`FrogManifest.parse` 会丢弃不合格条目，
`core-data/src/test/java/com/naiwa/data/frog/FrogManifestTest.kt` 里有专门的单测守住它。

### 生成自绘占位素材（可选工具）

`tools/gen_placeholder_frogs.py` 能用程序画出 6 张自绘 PNG（属于「自绘」范畴，合规）。

```bash
# 默认输出到 tools/generated-frogs/，【不会】进入 APK
python tools/gen_placeholder_frogs.py

# 明确要装进内置素材目录时才加 --to-assets（生成后记得登记 manifest.json）
python tools/gen_placeholder_frogs.py --to-assets
```

之所以默认不落在 assets 目录，是为了避免"随手跑一次脚本，素材就悄悄进了安装包" ——
素材进入 APK 应当是一个需要显式确认的动作。
