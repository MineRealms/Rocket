# 鞘翅飞行验证版

日期：2026-11-23。目标是验证 IP 的世界可见性、相机跨界、玩家速度和滑翔状态。火箭模型、真实行星球面与大气 Shader 尚未实现。

## 配置与入口

开发客户端配置：`forge-1.20.1-47.4.29-mdk/run/config/rocketmod-common.toml`。
普通客户端/专用服务器：各自游戏目录的 `config/rocketmod-common.toml`；跨界资格由服务端配置决定。

```toml
debugElytraSpaceFlight = true
flightTransitionAltitude = 512.0
```

调试开关默认开启。关闭后移除两侧临时门户，普通飞行不再进入太空；火箭入口待后续实现，不会把任意坐骑视作火箭。Forge 配置重载后服务端自动协调，最迟约 1 秒更新；服务端每次跨界也检查开关。

边界为主世界 Y=512 与太空 Y=64，x/z、方向、尺度保持一致。IP 以眼睛/相机跨平面检测，所以 F3 脚部高度和触发点有眼高差。只有存活、非旁观、未骑乘、正在鞘翅滑翔的玩家可穿越。只穿戴鞘翅、创造悬空或普通掉落均不满足资格。

本验证版使用覆盖默认世界范围的全局水平门户，方便从任意发射地点验证。它会改变可见天空；尚未完成大气遮罩和行星代理。门户尺寸不意味着加载整个世界，IP 按观察者视距加载，另有临近边界的半径 2 区块预加载。当前没有客户端 mesh Ready 握手，冷区块卡顿仍是后续验收项。

原服务端高度扫描和 `/rocketmod flight altitude` 已移除，以免与客户端自然门户穿越发生双重迁移。

## 试玩

管理员命令：

| 命令 | 用途 |
| --- | --- |
| `/rocketmod flight status` | 查看开关、边界、当前维度/高度/滑翔和门户总数 |
| `/rocketmod flight kit` | 空胸甲槽时装备不可损坏鞘翅，并给予 64 个三级烟花；不覆盖其他胸甲 |
| `/rocketmod flight launch` | 主世界内抬升到地面上方并开始滑翔，玩家继续右键烟花爬升 |
| `/rocketmod flight return` | 太空中的管理员应急返回；开关关闭时仍可脱困 |

主世界正常滑翔爬升到边界即可进入太空，不需要命令触发。太空维度仍有原版重力，停止推进会下落。向下保持滑翔穿过 Y=64 自动回到主世界高空。

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
