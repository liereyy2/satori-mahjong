# 资源与第三方文件说明

本仓库保留原项目的素材、署名和授权标记。本次整理没有为代码或素材新增许可。

| 文件 | 来源与随附说明 |
| --- | --- |
| `asset-sources/` 的 SVG 和 TypeScript | 来源为 satori Web 项目，版本记录在 [SOURCE.json](SOURCE.json)。纹理与材质清单保留原来源路径和 SHA-256。 |
| `src/main/resources/assets/satori_mahjong/sounds/calls/` | **VOICEVOX:四国めたん**。保留 [credits.txt](src/main/resources/assets/satori_mahjong/sounds/calls/credits.txt) 与 [license-voicevox.txt](src/main/resources/assets/satori_mahjong/sounds/calls/license-voicevox.txt)。 |
| `src/main/resources/assets/satori_mahjong/sounds/tiles/` | 原项目独立合成的麻将牌接触音，说明见 [credits.txt](src/main/resources/assets/satori_mahjong/sounds/tiles/credits.txt)。 |
| `voice_samples/20261016a/` | VOICEVOX 试听原稿和生成参数，保留 [CREDITS.txt](voice_samples/20261016a/CREDITS.txt)；试听与运行资源分开存放。 |
| `gradlew`、`gradlew.bat`、两套 `gradle/wrapper/` | Gradle Wrapper；脚本保留上游 Apache License 2.0 声明。下载的 Gradle 发行包与游戏依赖由构建工具管理。 |

Node 开发工具通过 `tools/package-lock.json` 固定版本，安装后保留各包内附的许可文件；`node_modules/` 不入库。Minecraft、Fabric、Forge、Java 工具链及语音模型不打包进源码仓库。
