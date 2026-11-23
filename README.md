# ROCKET

Forge 1.20.1 火箭升空与太空维度项目。

当前开发鞘翅飞行验证版：通过真实 IP 水平门户在主世界与太空之间滑翔往返，提供配置开关。火箭和行星渲染尚未实现。试玩方式见 [鞘翅验证版](docs/04-elytra-debug.md)。

## 技术决策

第一版采用 **Immersive Portals Forge 3.0.7 作为跨维度后端 + ROCKET 自研飞行、行星代理、大气渲染**。先验证跨维度往返与载具状态，再扩展视觉效果。该决策允许首版继续依赖 IP；移除依赖需要另立架构决策。

首版交付范围：单火箭实体、单驾驶员、单位坐标缩放、有限速度、受控飞行走廊、双向穿越、服务端权威状态、目标区块预热、客户端相机连续、与实际地形对齐的局部行星代理。

## 文档入口

| 文档 | 内容 |
| --- | --- |
| [鞘翅验证版](docs/04-elytra-debug.md) | 当前配置、试玩命令、调试桥与运行证据 |
| [当前基线](docs/00-project-baseline.md) | 已实现能力、历史运行证据、待验证事项 |
| [架构决策](docs/01-technical-direction.md) | 选型、坐标、状态机、网络与渲染边界 |
| [IP 核心技术研究](docs/02-immersive-portals-research.md) | 固定版本源码、可复用机制、限制与实验 |
| [开发路线与验收](docs/03-development-roadmap.md) | 阶段依赖、首批任务、测试与停止条件 |

## 工程入口

- 工程目录：`forge-1.20.1-47.4.29-mdk/`。
- 开发客户端：根目录 `Start-Minecraft.bat` 或工程内 `run-client.bat`。
- 构建：工程内 `build-mod.bat`。
- 脚本使用 `JAVA_HOME` 或 `ROCKET_JAVA_HOME`，代理使用本机外部配置。
- 调试命令：`/rocketmod flight kit`、`launch`、`status`、`return`，均需权限等级 2。

研究日期：2026-11-23。00 文档是实施前快照；当前功能和验证状态以 04 文档为准。完整开发路线仍有未完成阶段。
