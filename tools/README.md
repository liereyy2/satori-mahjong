# 开发与资源维护

所有命令均以本仓库为起点。日常构建只需 JDK 21，执行 `./gradlew buildAll`；已生成的纹理、模型、音频及测试夹具均已入库。Forge 构建还会自动解析 Java 25 工具链，依赖版本与校验值由 Wrapper 和 Gradle 配置固定。

## 牌面、模型与材质

`asset-sources/` 保存原 Web 项目中实际参与生成的源文件。其内部保留 `services/web/packages/...` 的相对路径，便于与现有资源清单中的来源和 SHA-256 对照；构建和生成过程均在本仓库内解析这些路径。

- `tile-kit/src/assets/*.svg`：牌字的原始矢量图。
- `mahjong-board-3d/src/engine/tile-appearance.ts`、`tile-surface.ts`：材质、绘制函数与派生着色器。
- `board-consts.ts`：宽高厚、圆角及细分参数。
- `tile-lens.ts`、`hand-projection-reference.json`：镜头公式和参考值。
- `tile-occlusion.ts`：共享遮蔽算法，用于重新生成 Java 数值对照夹具。

重建需要 Node.js 20 或更高版本。依赖直接声明在 `tools/package.json` 并由 `package-lock.json` 锁定：

```bash
cd tools
npm ci
npx playwright install chromium
npm run assets
```

`npm run assets` 依次重建原生模型、材质参数、着色器、家具纹理、普通牌面、缺门牌面及纹理清单。只改牌字时可执行 `npm run tiles`。遮蔽算法变化时，执行 `npm run occlusion`，随后运行共享 JUnit。

修改后一起提交 `asset-sources/` 中的源文件及 `src/` 下相应产物。两加载器的 `verifyTileTextures` 在资源打包前核对源 SVG、材质、镜头、着色器、几何、PNG 哈希和采样元数据。

## 其他工具

| 工具 | 开发依赖与输入 |
| --- | --- |
| `generate_table_assets.py`、`generate_tile_materials.py`、`generate_center_glyphs.py` | Python 和 Pillow。字形图集使用 Noto Sans CJK Bold，字体路径按脚本声明提供。 |
| `generate_call_voices.py`、`generate_voice_samples.py` | `voicevox-core==0.17.0`、NumPy、SoundFile，以及命令行明确指定的 ONNX Runtime、词典、声库；编码需要 FFmpeg。模型与推理运行库单独安装。 |
| `export_*.py`、`verify_help_examples.py` | 原 satori 项目的 Rust / Python `libsatori`。用于生成或核验规则与投影夹具，普通 Mod 构建直接使用已提交的 JSON。 |
| `verify_help_tile_frame.py`、`verify_indicator_previews.py` | Python、Pillow，输入真实测试截图，参数见脚本的 `--help`。 |
| `../verify_forge.py` | Python 标准库和 JDK，执行真实 Forge 专用服务器、客户端及世界重载验证，需要图形环境和显式 EULA 参数。 |

## 验证

从本仓库根目录运行：

```bash
./gradlew buildAll
```

这会运行两套加载器的现有 JUnit，校验正式 JAR 元数据，检查测试类和测试资源没有进入发行 JAR，并在 `build/distributions/<mod_version>/` 输出两个安装包和 SHA-256 清单。

图形与真实游戏测试按首页的命令运行。它们具有各自的图形环境、EULA 和运行时输入要求，`buildAll` 的成功不代表已完成这些测试。
