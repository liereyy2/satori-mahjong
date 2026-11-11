# SatoriMahjong for Minecraft

Minecraft Java **1.21.11 · Fabric / Forge · Java 21**，当前构建 **20261105a**，官网正式下载为 **20261105a**。

管理员在 SatoriController 配置实例共享 Satori 账号和可选 AI Key。玩家点击 SatoriTable 入座准备，四席齐备且所有真人准备后自动开局；每桌独立运行，整场结算确认后回到原桌准备。准备、规则和对局状态均在云端 Rust，参与者客户端仅接收本人暗手与公共信息，未参与本场的旁观者可查看四家暗手。

完整安装、操作和中文实机图见[公开使用指南](https://mj.satori-ai.cn/docs/web-features/minecraft-mahjong/)。本仓库从 satori 总仓库整理而来，来源版本见 [SOURCE.json](SOURCE.json)，历史记录见 [CHANGELOG](docs/CHANGELOG.md)。

## 构建与安装

安装 JDK 21 并配置 `JAVA_HOME`，从本仓库根目录执行：

```bash
./gradlew buildAll
```

Windows 使用 `gradlew.bat buildAll`。两个正式包和 `manifest.json` 统一生成到 `build/distributions/0.1.0+mc1.21.11.20261105a/`：

| 目标 | 安装包 | 依赖 |
| --- | --- | --- |
| Fabric Loader 0.19.5 | `satori-mahjong-fabric-0.1.0+mc1.21.11.20261105a.jar` | Fabric API 0.141.6+1.21.11。 |
| Forge 61.2.0 | `satori-mahjong-forge-0.1.0+mc1.21.11.20261105a.jar` | 无需 Fabric API。 |

客户端和 MC 服务端均安装 **20261105a**，并匹配 Minecraft、加载器和依赖；每个实例只保留一份 Satori 安装包。Fabric 与 Forge 使用各自正式包，Forge 包不适用于 NeoForge。控制器状态协议要求本版两端配套，加载器和 MC 版本不能混用。

原正式发行包 SHA-256（保留自总仓库记录；本地重建以生成的 `manifest.json` 为准）：

| 安装包 | SHA-256 |
| --- | --- |
| Fabric | `9148429a5419003d2025c77d1f07df72b61ee877a6bc5bb256f1c5524326ce29` |
| Forge | `ea11689a04727fc53a8d2c9775076e198123981b5a6ce930820e1362e4747051` |

Fabric 固定 Gradle 9.2.1 / Loom 1.14.10；Forge 固定 Gradle 9.5.0 / ForgeGradle 7.0.29。Forge 的 Mavenizer 使用由 Foojay 自动解析的 Java 25 构建工具链，游戏与 Mod 运行仍使用 Java 21。Wrapper 校验发行包 SHA-256，首次构建需要下载依赖。

单独构建 Fabric 使用 `./gradlew build`，Forge 使用 `cd forge && ./gradlew build`。编译/归档会清理两端 libs、devlibs 和 distributions 的旧版产物，仅保留当前版；依赖缓存、世界和已发布官网资源不属于清理范围。

## 当前使用流程

1. 管理员放置 **SatoriController**，单人服主或 OP 2 及以上管理员在附近空手右键，登录共享 Satori 账号；界面显示 credits 额度，打开期间每 5 秒自动刷新；提交操作期间保持按钮亮度，拦截重复点击。
2. 需要 AI 时从已有 Key 列表选择；缺少 Key 通过网页新建后刷新。纯真人不需要 AI Key。
3. 放置 **SatoriTable** 自动生成桌体与四把椅子，预留至少 5×5 平面和椅子上方空间。
4. 玩家空手右键椅子或对应桌沿入座，准备页显示实际 MC 名单。选择立直或川麻；立直选择东风/半庄，川麻选择4/8/16局，再为空位添加云端 AI，各真人准备后自动开始。
5. 川麻换三张直接点选原手牌中的同花色三张，再点换牌按钮；定缺使用手牌上方的缺万、缺筒、缺条按钮。定缺后，各家可见的缺门牌排到紧凑手牌区最右侧，左上角标“缺”；新摸牌不参与这次排序，始终独立放在紧凑手牌右侧并留有间隔，点击摸牌和手牌保持各自的摸切、手切身份。直接点击手牌出牌，其他合法决策显示在手牌上方。吃、碰、杠、立直、和牌、流局等决策按钮不播放咔哒声，动作经云端确认后直接播放对应语音；跳过保持安静。菜单提供视角调整、反馈 Bug、复制对局 ID 和离开座位。
6. 准备页与对局牌桌右上角的圆圈问号打开麻将规则帮助，默认显示当前桌的立直或川麻模式，顶部可单独切换阅读。立直说明和牌条件、宝牌、振听及完整役种；川麻说明清缺、血战续局、番型与荒庄清算，牌型页均提供真实牌面和搜索。滚轮或方向键滚动，Esc 返回原牌桌；阅读期间对局继续。
7. 小局确认后继续；整场终局展示顺位、分数和 pt。确认后回到准备，全部在座者确认或离开后可以再开局。

SatoriController 配方为中央红石粉、周围八个铁锭；SatoriTable 为中央任意原木、周围八个任意羊毛毯。物品 ID 分别为 `satori_mahjong:control` 和 `satori_mahjong:table_controller`。

共享账号保存于 MC 服务端 `config/satori-mahjong-control.json`，包含密码与选中的 Key 标识，应由管理员保管。普通客户端不会收到共享密码、账号 Cookie 或 AI Key 明文；客户端以本人座位令牌连接云端，因此 MC 服务端和玩家客户端均需能访问 Satori。

管理员新注册 Satori 账号可获得 5.00 元初始额度，验证码首次登录自动开户同样适用；纯真人对局不消耗 AI 额度，添加 AI 后按共享账号的现行计费规则使用额度。已有账号登录不重复赠送。

各桌绑定随世界保存。准备时离座移除玩家并重置准备；活动对局保留席位状态，真人离座后空位可由其他玩家接手，原凭据撤销；本场参与者离座后继续观看摸打鸣牌动画和分数，只能看自己席位的暗手，其他暗手隐藏；未参与本场者才可旁观四家实际手牌。暗手权限按本场参赛身份确定，被替换的原参与者只看公开牌面，换椅子须由云端确认新的席位归属。控制器对局页列出本服各维度活动牌桌，右侧高亮按钮关闭窗口并用粗黄线框闪烁五秒。摧毁桌面会取消活动云端对局并释放房间，服务端持久保存未确认释放并重试；重新放置产生新桌。反馈使用共享账号提交已有工单，普通玩家获取自己的反馈编号，ops-admin 可处理。

对用户可见的功能、安装、权限、联网、费用及升级变更，必须同步更新上述公开指南与相关 API 文档；版本和下载校验值以正式发行清单为准。当前 JAR 的许可证元数据保持保留所有权，第三方发行与收益调研不自动改变代码或素材授权。

## 验证与取图

`buildAll` 执行双加载器共享 JUnit、检查元数据与正式 JAR 排除测试类，再收集大小和 SHA-256；不会自动执行图形测试或上传平台。

图形环境执行 `./gradlew runTablePerformance`，测量1080p、无光影、0/1/4/16桌与16桌换三张的牌桌通道CPU/GPU耗时及空闲桌服务端tick。`./gradlew runProjectionPerformance` 单独测量公开元信息与真实Rust观察者事件的复制/序列化。结果保存在 `build/run/`；图形设备、原始样本和测量边界见[历史性能记录](benchmarks/20261031d/README.md)。集成服空闲桌和序列化测试不能当作生产多人TPS，软件渲染FPS不能当作玩家显卡表现。

- **Fabric 客户端：** 在图形环境执行 `./gradlew runProductionClientTest`。正式重映射 JAR 加独立测试 Mod；该任务固定使用官方 `disableNetworkSynchronizer` 参数保留真实异步网络调度，验证 SatoriController 登录/Key、真实入座、准备、AI、自动开局、私有令牌、反馈、终局确认和离座。`HostedGuideCaptures` 通过实际按钮截取当前中文流程，截图在 `build/run/productionClientTest/screenshots/`。
- **Fabric 服务端：** 阅读 Minecraft EULA 后执行 `./gradlew runProductionServerTest -PacceptMinecraftEula=true`，检查正式 JAR 的独立服务端加载、结构、NBT 与同步隐私。
- **Forge：** 从本仓库根目录执行 `python3 verify_forge.py --accept-eula`，依次运行回环专用服务器、客户端和世界重载。报告和截图在 `forge/build/smoke/`，使用与发行包相同的生产源码及独立测试源集。

测试使用本地合成账号与 Rust 授权投影，不调用线上登录、验证码或付费 AI。测试代码、夹具及预览样本不进入发行 JAR。四真人与双桌的云端隔离由真实 Rust / 隔离 PostgreSQL 集成测试另行验证。

同一世界中未参与本场牌局的玩家可在桌边旁观四家手牌，64 格内持续同步；普通云端座位接口仍只暴露对应席位的手牌。未验证大型整合包、着色器和四台公网客户端压力场景。1.7.10、1.12.2 等旧版需要独立移植和验证，尚未发布安装包。

牌面和材质源文件保存在 `asset-sources/`，原有来源路径与 SHA-256 保留。常规构建直接使用已提交的 PNG、Ogg 和模型；Fabric 与 Forge 均校验牌面来源和纹理哈希，构建 Mod 无需 Node 或浏览器。资源重建方法见 [tools/README.md](tools/README.md)。

管理员从在线管理删除云端对局后，旧请求返回对局不存在；MC 清空该局展示并回到准备页，玩家需要重新准备开局。物理牌桌和其他对局保持。

## 仓库内容

| 路径 | 内容 |
| --- | --- |
| `src/main`、`src/client` | 两个加载器共享的 Java 代码与运行资源。 |
| `src/fabric`、`src/fabricClient`、`forge/src` | Fabric / Forge 加载器入口和元数据。 |
| `src/test`、`src/testFixtures`、`src/gametest` | JUnit、集成测试代码和已生成的离线夹具。 |
| `asset-sources/` | 从 Web 项目保留的 SVG、材质、镜头和几何源码快照。 |
| `tools/` | 资源生成、夹具导出和图片校验工具，以及锁定的 Node 开发依赖。 |
| `voice_samples/`、`benchmarks/` | 原始语音试听、署名与历史性能记录。 |
| `gradle/`、`forge/gradle/` | 构建脚本及两个经过 SHA-256 校验的 Gradle Wrapper。 |

云端对局服务和 Rust 规则引擎属于独立服务。Mod 构建使用本仓库已提交的测试夹具；需要重新导出 Rust 夹具时，另行安装原项目的 `libsatori`，参见工具说明。

资源总量较小，SVG、PNG、音频及 Wrapper JAR 直接纳入普通 Git，克隆后即可取得完整资源，无需 Git LFS。`.gitattributes` 将媒体和 JAR 按二进制处理；`.gitignore` 排除构建产物、Gradle / Node 缓存、运行世界、账号配置与本地日志。发布 JAR 与校验清单由 `buildAll` 生成，作为发行附件管理。

## 素材与授权

保留现有 Mod 的 `All Rights Reserved` 授权标记。本次整理未更改代码或素材的授权。鸣牌语音署名 **VOICEVOX:四国めたん**；使用与转发语音时保留随附的署名和条款。资源与第三方文件的说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
