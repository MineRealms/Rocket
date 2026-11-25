# 鞘翅飞行验证版

更新：2026-11-25。当前验证 IP 世界可见性、相机跨界、Ready 与故障恢复。已加入高度驱动天空，以及近乎平直的低轨道代理和高轨道方块地球。当前运行验收见 [P0/P1](05-p0-p1-acceptance.md)，火箭实体待 P2。

## 配置与入口

开发客户端配置：`forge-1.20.1-47.4.29-mdk/run/config/rocketmod-common.toml`。
普通客户端/专用服务器：各自游戏目录的 `config/rocketmod-common.toml`；跨界资格由服务端配置决定。

```toml
debugElytraSpaceFlight = true
flightTransitionAltitude = 1600.0
```

调试开关默认开启。关闭后移除两侧临时门户，普通飞行不再进入太空；火箭入口待后续实现，不会把任意坐骑视作火箭。修改文件后重启客户端/服务器生效。代码也处理 Forge 配置重载事件，但本环境文件修改未自动触发重载，因此不能依赖热更新。

边界默认主世界 Y=1600 与太空 Y=64，x/z、方向、尺度保持一致。IP 以眼睛/相机跨平面检测，所以 F3 脚部高度和触发点有眼高差。升空要求存活、非旁观、未骑乘且正在鞘翅滑翔；只穿戴鞘翅或创造悬空不算升空资格。太空普通下降可无鞘翅返回。旧配置不会被默认值覆盖，本机测试配置已更新为 1600。

本版使用全局水平门户，门户尺寸不等于加载全世界。附加预热半径 2 区块，客户端检查目标世界、25 个区块和抵达区域 mesh 后回复 Ready；非空抵达区域另需最近目标绘制 pass。未就绪时在边界前等待，超时清理后可以重新尝试。

原服务端高度扫描和 `/rocketmod flight altitude` 已移除，以免与客户端自然门户穿越发生双重迁移。

## 试玩

管理员命令：

| 命令 | 用途 |
| --- | --- |
| `/rocketmod flight status` | 查看开关、边界、当前维度/高度/滑翔和门户总数 |
| `/rocketmod flight kit` | 空胸甲槽时装备不可损坏鞘翅，并给予 64 个三级烟花；不覆盖其他胸甲 |
| `/rocketmod flight launch` | 主世界内抬升到地面上方并开始滑翔，玩家继续右键烟花爬升 |
| `/rocketmod flight return` | 太空中的管理员应急返回；开关关闭时仍可脱困 |

主世界正常滑翔爬升到边界即可进入太空，不需要命令触发。太空仍有原版重力，停止推进会下落。下降穿过 Y64 返回主世界高空；无需保持滑翔。无滑翔返回附带一次首次落地保护，最长 1200 tick。低于太空 Y40 的异常位置会直接恢复到主世界出生点地表，debug 开关关闭也可脱困；太空虚空伤害会被拦截。

新生成太空区块没有屏障地板。旧区块的屏障不批量删除，正常返回与兜底都在地板上方处理。

测试存档目录 `ROCKET-Flight-Test` 从已有世界复制，原存档不用于自动飞行测试。

## 构建与调试桥

`build-mod.bat` 构建；`run-client.bat` 或根目录 `Start-Minecraft.bat` 启动。使用已安装 JDK/JAVA_HOME，可通过 `ROCKET_JAVA_HOME` 覆盖。机器代理配置使用外部环境或用户 Gradle 属性，不再在仓库写死端口。

开发时可选接入已有 Rosetta jar，并由 Renamer 转换到开发环境名称：

```powershell
.\run-client.bat "-ProsettaBridgeDir=H:/MinecraftMods/RainJava-work/RosettaRemoteDebugBridge/build/libs" "-PquickPlay=ROCKET-Flight-Test"
```

进入世界后可使用本地工具，不输出 token：

```powershell
python tools/flight_probe.py ping
python tools/flight_probe.py state
python tools/flight_probe.py console "execute as Dev run rocketmod flight status"
python tools/flight_probe.py exec tools/probes/eligibility.java
```

桥仅作为可选本机开发依赖，不打包到 ROCKET jar。运行证据与尚未验证项目在本文末尾记录。

自动画面采集可用 JVM 参数 `-Drocket.validation.hide=true` 隐藏真实 GLFW 窗口；这不是 headless，渲染、shader 和截图仍实际运行，普通启动脚本不启用此参数。隔离试玩完成后运行 `python -B tools/prepare_playtest.py --show` 恢复创造模式、装备与 HUD 并显示窗口。该命令会重置测试角色位置，只用于隔离存档；玩家开始试玩后不要再次运行。

## 本机环境处理

首次构建补齐了 ForgeGradle 7 的工具缓存。Temurin 8 自动下载收到无效文件，改用 Azul 官方 JDK 8 并核对 SHA-256；游戏仍使用现有 Temurin 17.0.18。该 JDK 8 仅用于旧版映射工具。

原先写死的 Java 路径不存在、代理 15236 未监听。本次通过已运行的本机代理 7890 下载；该端口只注入本次进程环境，不写入项目默认配置。

C 盘空间不足时，将资源和本次 Forge 工具缓存迁至项目 `.local-cache/`，原路径通过 Windows junction 指向新位置：

- `%APPDATA%/.minecraft/assets` -> `ROCKET/.local-cache/minecraft-assets`
- `%USERPROFILE%/.gradle/caches/minecraftforge/forgegradle/mavenizer/caches` -> `ROCKET/.local-cache/forge-tool-cache`

该目录已被 Git 忽略，仍用于本机后续构建和启动。不要把它当成可随意移除的单次测试输出；移动工程时需要同步调整 junction。

`run-client.bat` 禁用 Gradle configuration cache，确保 IP refmap 路径在首次工具缓存建立后重新解析。

## 首轮历史证据

以下为 2026-11-23 首版快照，hash 与尚未完成项目不代表当前版本。后续完整验证结果以 [05 文档](05-p0-p1-acceptance.md) 为准。

2026-11-23，Forge 47.4.29 + IP 3.0.7 + Cloth 11.1.136 + 可选 Rosetta，Java 17.0.18：

- `build` / `renameJar` 成功。没有 JUnit 用例；本轮通过游戏内探针验收行为。
- 默认 `build-mod.bat --offline`（不传 Rosetta 参数、不注入代理）也成功；Java 编译目标仍为 17。发行 jar 的 SHA-256 为 `f3d2e5ba91e12e758a5543b1f239d46268e8fa6791691cd703a395ad4328ac93`。
- 集成服务器实际进入 `ROCKET-Flight-Test`，客户端创建 `rocketmod:space`，同步两个自定义全局门户。
- `tools/probes/eligibility.java` 返回 PASS：重复协调维持两个门户、非滑翔拒绝、滑翔允许、单位平移映射正确、运行开关关闭立即拒绝且协调后清理门户/租约。
- 修改真实 TOML 为 false 并重启客户端，Rosetta 返回 `debug=false portals=0 leases=0`，确认文件开关实际生效；交付时恢复 true。
- `tools/probes/ascent.java` 在主世界 Y490 设置滑翔初始状态并生成普通附着烟花，随后由实际运动自然跨界，没有调用跨维度 API。
- 21:04:11，IP 客户端自然跨越后服务端确认主世界 -> 太空，`fallFlying=true`，速度 `(0, 1.50897, 0.13523)`。
- 21:04:16，失去推进后下降自然返回主世界，`fallFlying=true`，速度 `(0, -2.16253, 0.02840)`。
- Rosetta 内置游戏截图成功生成，确认客户端实际渲染非空画面。尚未做跨界逐帧录像、20 次往返压力测试、独立专用服务器或冷区块 Ready 验收，不宣称 G0-G4 全部通过。

太空入场脚部 Y 可暂时小于 64：IP 的相机/眼睛跨平面时点与脚部位置不同。验证以维度、连续运动和眼睛跨越为准。

当前试玩入口仍为 `/rocketmod flight launch`，继续使用烟花爬升即可验证。自动测试只操作隔离存档；交付位置和当前验收版本见 05 文档。
