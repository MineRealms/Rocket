# 当前项目基线

核查日期：2026-11-23。工程路径以下简写为 `forge-1.20.1-47.4.29-mdk/`。状态依据当前源码与已有日志；没有本轮游戏验收。

## 1. 工程状态

| 项目 | 已核实情况 |
| --- | --- |
| 平台 | Minecraft 1.20.1、Forge 47.4.29、Java 17 |
| Mod 标识 | `rocketmod`，版本配置 `1.0.0` |
| Git | `master` 无提交，原有工程全部未跟踪 |
| Java | `com.example.examplemod` 下仅 3 个类 |
| 依赖 | IP Forge 3.0.7、Cloth Config 11.1.136，均为必需依赖 |
| 资源 | 太空 dimension/type 两份 JSON；示例资源为 3 个模型和 1 个 blockstate |
| 构建环境 | Java 路径与本地代理写入 Gradle 属性和启动脚本 |

未发现项目级 `AGENTS.md` 或 `CLAUDE.md`。模块名 `examplemod`、Java 包名和示例物品仍保留 MDK 命名。

## 2. 实际实现

| 能力 | 状态 | 本地证据 |
| --- | --- | --- |
| 主世界玩家达到高度阈值后传送 | 已实现服务端条件判断 | `src/main/java/com/example/examplemod/FlightDimensionTransition.java:37` |
| 默认阈值 512 | 已配置 | `src/main/java/com/example/examplemod/Config.java:31` |
| 虚拟高度入口 | 仅一次性方法参数，无持久状态 | `FlightDimensionTransition.java:62` |
| 落点计算 | x/z 原样，y 为 64 加超出阈值高度并 clamp | `FlightDimensionTransition.java:76` |
| 玩家传送 | 调用 IP `PortalAPI.teleportEntity` | `FlightDimensionTransition.java:82` |
| 返回 | 管理员命令回主世界公共出生点 | `FlightDimensionTransition.java:86` |
| 太空维度 | 平坦虚空，Y=0 一层 barrier，固定夜晚，无天光 | `src/main/resources/data/rocketmod/` |
| 火箭、驾驶、燃料 | 未实现 | 当前三个 Java 类中不存在 |
| 项目侧乘客事务、预热和 Ready 协议 | 未实现 | 当前传送调用前后没有这些逻辑 |
| 行星、大气、跨帧相机处理 | 未实现 | 无自定义客户端渲染器或 shader |
| 自动下降返航 | 未实现 | 只有管理员返回命令 |

高度扫描面向所有主世界玩家，未检查正在驾驶火箭、飞行阶段或上升方向。创造飞行、鞘翅以及其他模组也可能触发。未来正式流程必须由火箭会话驱动。

`space_type` 的高度为 2032、`min_y=0`，建造范围为 Y=0..2031。该配置没有实现零重力、真空或无限轨道空间。

当前 clamp 会丢失超出范围的高度信息；返回出生点也不是坐标逆变换。不能在此基础上声明可逆连续飞行。`onVirtualAltitudeChanged` 返回 true 仅表示已调用 API，不是项目完成了传送确认。

## 3. 历史证据与限制

已有 `run/logs/latest.log` 的会话时间为 2026-11-17：

- 第 1-2 行记录 Forge 47.4.29、MC 1.20.1、Java 17.0.21。
- 第 20-24 行可见 IP refmap 重映射；第 38、71 行可见 IP 初始化与 commonSetup。
- 第 130、177 行记录 `rocketmod:space` 注册与 dimension type 同步。
- 第 167、191-193 行记录玩家进入主世界和主世界位置包。
- 第 214-229 行记录正常退出和各维度保存。

这些记录支持“历史开发客户端曾进入世界且识别太空维度”。检索现有日志未找到项目的 `Auto flight transition` 成功记录，不能据此确认高度跨越、骑乘传送或画面连续性。

存在更早的 `run/crash-reports/crash-2026-11-17_00.25.24-client.txt`，其中有注册表加载失败；后续成功进世界说明不能直接把旧崩溃当作当前仍会复现的问题。`build/libs/` 下存在历史 jar，也不能证明当前源码刚刚通过构建。

## 4. 原分析需要修正的部分

1. `mods.toml` 与 `pack.mcmeta` 的源文件 token 是正常模板。`build.gradle:106-118` 的 `processResources` 会展开；已有处理后资源也包含具体值。
2. 项目侧 Mixin 配置被注释，不代表 IP 的 Mixin 没有工作；历史日志已经显示其加载和重映射。
3. 项目未写速度/乘客处理，不等于 IP 必然清零速度或掉坐骑。固定版本源码对玩家直接坐骑有特殊路径；复杂火箭仍需验收。
4. “不出现加载界面”只是连续体验的一项条件，还需目标数据、相机插值和实体状态都连续。
5. Minecraft 以区块列和 section 管理地形；单纯把实体升高不等于沿途生成几十万格实体地形。分离物理高度与视觉高度主要是为坐标范围、精度、玩法和渲染成本服务。

## 5. 本轮交付边界

本轮只新增 README 和设计文档，未修改 Java、依赖、维度 JSON、存档或启动脚本，未提交 Git。所有后续里程碑均为计划状态；验证要求见 [开发路线](03-development-roadmap.md)。
