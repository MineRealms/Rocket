# ROCKET

Forge 1.20.1 火箭升空与太空维度项目。

当前为鞘翅飞行验证版：使用真实 IP 水平门户、目标预热与 Ready 握手往返太空。近地轨道显示近乎平直的地形颜色代理，高轨道使用像素贴图方块地球。下降返回不要求鞘翅，另有防虚空兜底。火箭实体待后续实现。

## 技术决策

第一版采用 **Immersive Portals Forge 3.0.7 作为跨维度后端 + ROCKET 自研飞行、行星代理、大气渲染**。先验证跨维度往返与载具状态，再扩展视觉效果。该决策允许首版继续依赖 IP；移除依赖需要另立架构决策。

当前 P0/P1 优先验证玩家跨界、失败恢复和高度视觉；火箭模型与载具物理属于 P2。版本边界和实测结果见 [P0/P1 验收](docs/05-p0-p1-acceptance.md)。

## 文档入口

| 文档 | 内容 |
| --- | --- |
| [P0/P1 实现与验收](docs/05-p0-p1-acceptance.md) | 当前实现、运行证据、返回保护和后续边界 |
| [鞘翅验证版](docs/04-elytra-debug.md) | 当前配置、试玩命令、调试桥与运行证据 |
| [当前基线](docs/00-project-baseline.md) | 已实现能力、历史运行证据、待验证事项 |
| [架构决策](docs/01-technical-direction.md) | 选型、坐标、状态机、网络与渲染边界 |
| [IP 核心技术研究](docs/02-immersive-portals-research.md) | 固定版本源码、可复用机制、限制与实验 |
| [开发路线与验收](docs/03-development-roadmap.md) | 阶段依赖、首批任务、测试与停止条件 |
| [大气与门户矩形问题](docs/06-atmosphere-research.md) | 固定 IP 源码根因、融合视图、背景绘制策略 |
| [真实升空视觉参考](docs/07-ascent-visual-reference.md) | NASA / ESA 依据、近轨道与同步轨道映射 |
| [审查逐项核实](docs/08-review-findings.md) | 已确认问题、修复及不成立的推断 |

## 工程入口

- 工程目录：`forge-1.20.1-47.4.29-mdk/`。
- 开发客户端：根目录 `Start-Minecraft.bat` 或工程内 `run-client.bat`。
- 构建：工程内 `build-mod.bat`。
- 脚本使用 `JAVA_HOME` 或 `ROCKET_JAVA_HOME`，代理使用本机外部配置。
- 调试命令：`/rocketmod flight kit`、`launch`、`status`、`return`，均需权限等级 2。

更新：2026-11-25。00 文档是实施前快照，当前实现与验收以 05 文档为准。详细操作见 [试玩说明](docs/04-elytra-debug.md)。
