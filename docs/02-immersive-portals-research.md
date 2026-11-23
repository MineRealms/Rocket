# Immersive Portals 核心技术研究

日期：2026-11-23。结论来自官方源码、官方说明和 Modrinth 发布元数据。源码结论与待运行验证项分开记录。

## 1. 锁定研究对象

| 对象 | 固定信息 |
| --- | --- |
| 项目当前依赖 | `maven.modrinth:zf4Szzx2:Q6Z7eOLL` |
| 发布 | IP for (Neo)Forge 3.0.7，MC 1.20.1，Forge loader |
| 发布日期 | 2025-03-30 |
| 必需配置库 | Cloth Config；本项目指定 11.1.136 |
| 发布 jar | `immersive-portals-3.0.7-all.jar` |
| 发布元数据 SHA-1 | `b4a6d71ca7dc07a1da33e7272befabc6ce95057b` |
| Forge 研究仓库 | `iPortalTeam/ImmersivePortalsModForForge`，显式选择 `1.20.1` 分支 |
| 固定研究提交 | `f60c277f8971d64a580faa5a110e410742b489e9` |

[官方发布元数据](https://api.modrinth.com/v2/version/Q6Z7eOLL)确认版本与 loader；[固定源码属性](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/gradle.properties)声明 3.0.7、Forge 编译基线 47.2.30。源码提交时间与发布对应，但本次未对发布 jar 做字节码比对，不能声称已经证明二者逐字节同源。47.4.29 的具体兼容性仍以项目运行实验为准。

本次官方 API 查询显示 [Forge 仓库](https://github.com/iPortalTeam/ImmersivePortalsModForForge)未归档，默认分支为 `1.18`；[Fabric 上游](https://github.com/iPortalTeam/ImmersivePortalsMod)显示已归档。两个仓库的状态不能混用，未归档也不等于有人承诺持续维护。

早期 [Implementation Details 历史页](https://github.com/iPortalTeam/ImmersivePortalsMod/wiki/Implementation-Details/d19625715e0ee61eafa2497630c512e11f0d1c86)用于解释思想；具体方法和版本限制以本页固定提交为准。

## 2. 世界管理：一个 FBO 远远不够

[ClientWorldLoader](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/ClientWorldLoader.java)按维度管理 `ClientLevel`、`LevelRenderer` 和 `DimensionRenderHelper`，同时涉及远端 tick、光照、粒子、资源重载和销毁。`withSwitchedWorld` 使用作用域恢复上下文。

ROCKET 的吸收方式：让 IP 管理远端真实世界，业务层只申请所需视野和读取必要上下文；自己的星球 mesh 是另一个视觉数据源。若只想画程序化球体，不必创建第二个真实世界渲染器。

必须验证：退出存档、资源重载、重复进入世界不会保留旧 ClientLevel、renderer 或 GPU 资源；项目最终大气 pass 不在每个远端世界 pass 重复执行。

## 3. 区块和数据包：带维度上下文的兴趣管理

[NewChunkTrackingGraph](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/chunk_loading/NewChunkTrackingGraph.java)管理玩家对不同维度的区块关注关系。[PacketRedirection](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/network/PacketRedirection.java)将网络包放入正确维度上下文处理。

[PortalAPI](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/api/PortalAPI.java)提供 `addChunkLoaderForPlayer` / `removeChunkLoaderForPlayer`。移除需要保留原 loader 对象，不能重新创建一个数值相同的对象代替。

ROCKET 的吸收方式：以 `ChunkLease` 绑定玩家/航线/transitionId，在有限半径内提前关注目标区块，列出成功、取消、死亡、断线、超时和世界卸载的释放路径。服务端已加载、已发送、客户端已接收、GPU mesh 可绘制是不同状态。

初始实验半径 2 chunks：目标中心 5×5 区域，每位准备中的驾驶员最多 25 个显式附加区块；这不包含 IP 自身门户可见性引入的加载。真正总成本需要实测并限制并发、视距和准备会话数量。

## 4. 客户端传送：以相机跨平面为事件

[ClientTeleportationManager](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/teleportation/ClientTeleportationManager.java)在渲染帧中比较眼睛位置，检测穿过门户平面，变换当前位置、上一 tick 状态、速度、视角与 vehicle，然后通知服务端。

ROCKET 的吸收方式：同一几何映射贯穿物理和相机，保留上一帧/上一 tick 插值历史；不要在服务端 20Hz 高度判断之外再叠加一套不一致的客户端手工换世界。

必须验证：第一/第三人称、侧向看、向下看、高帧率与低帧率、同 tick 跨越较长距离时，速度与相机都连续；站在边界不会反复抖动。

## 5. 服务端传送：玩家、普通实体和坐骑不同路径

[ServerTeleportationManager](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/teleportation/ServerTeleportationManager.java)是研究重点：

- 玩家变更维度时对直接 vehicle 有解除骑乘、迁移和重新挂载的特殊处理。
- 普通实体和 vehicle 可能由实体类型重新创建并 `restoreFrom`，旧对象引用不再可靠。
- 常规门户路径与公共 `teleportEntityGeneral` 路径不相同；公共 API 对非玩家走 `teleportRegularEntityTo`，不能据此承诺完整乘客树随行。
- 常规服务端实体门户流程会避开含玩家的实体群，由玩家客户端跨门流程参与驱动。
- `startTeleportingRegularEntity` 有名为 motion 的位移平方大于 20 时拒绝的逻辑。它不是所有路径统一的速度上限，但足以要求独立测高速和无人驾驶。

`PortalAPI.teleportEntity` 返回 void；玩家路径的无加载界面能力，不等于目标世界已经可绘制或任意载具状态有保障。对本项目当前代码应描述为“调用了具备无加载屏能力的 API，整体连续性待验收”。

ROCKET 的吸收方式：首版只支持一个实体加一个直接驾驶员，完整保存自定义 NBT；通过 UUID 确认目标实体。对一次自然门户穿越只设一个迁移执行者，业务协调器观测和恢复，避免重复传送。

若未来采用 Forge `ITeleporter` 做独立后端，[Forge 官方接口](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.x/src/main/java/net/minecraftforge/common/util/ITeleporter.java)也明确区分玩家和可能新建实例的普通实体。它不会自动提供 IP 的全部客户端世界管理能力。

## 6. 高度连接：先有限平面，后全局连接

[VerticalConnectingPortal](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/portal/global_portals/VerticalConnectingPortal.java)能够连接上下维度，但默认 `connect()` 使用维度真实 min/max 高度。主世界上限约为 Y=320，不是项目配置的 512。

因此 `/portal global connect_ceil` 只能验证默认堆叠能力，不能直接作为 512→64 的实现。`createConnectingPortal(...)` 可接受自定义高度参数，需由版本适配层封装，并实测世界建造高度之外的可见性、剔除、碰撞和区块行为。

选定实验顺序：

1. 普通有限水平门户连接两个测试位置，确认朝向与单向穿越。
2. 创建反向门户，确认几何逆变换与往返。
3. 将来源平面移至 Y=512、目标平面 Y=64。
4. 验证单驾驶员载具与有限走廊后，再实验 global portal。

全局连接可能让普通飞行玩家和其他实体也穿越，并改变整片天空视野；它是额外玩法决策。资格过滤接口是否足够、如何防止未 Ready 穿越，必须先证明。首版用受控航道、提前制动和会话管理限制火箭，不假定 IP 已提供本项目需要的完整权限门。

## 7. 渲染：Stencil 与 Framebuffer 都已有实现

[RendererUsingStencil](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/render/RendererUsingStencil.java)通过门户区域 stencil、局部深度清理、目标世界渲染和门户面深度恢复组合空间。透明物体的绘制时机也参与正确性。

[RendererUsingFrameBuffer](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/render/RendererUsingFrameBuffer.java)已有单层门户的 FBO 路径。选择 FBO 可以限制递归/合成复杂度，但仍依赖世界数据、网络与相机机制。

ROCKET 的吸收方式：首版限制门户层级与视野，复用 IP 世界渲染，自己只增加星球代理与最终合成。Shader 先在主视图验证，再测试门户目标视图、透明驾驶舱、深度遮挡和资源重载。

## 8. Voxy：研究范围与版本边界

本次读取的 [Voxy dev 构建属性](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/gradle.properties)使用 Fabric 工具链，不能当成 Forge 1.20.1 依赖直接接入。这里只核实该分支，不以此推断历史所有发行版本。

吸收多级空间组织、距离选择细节、异步缓存和绘制预算等思想。先做 CPU patch/Cube Sphere，再根据测量考虑 GPU 驱动绘制；Compute Shader、GL4.6 和类似 Voxy 的完整体素缓存均不是首版要求。

[固定版本 LICENSE.md](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/LICENSE.md)保留权利并限制再分发，因此本路线不复制其实现。IP [固定版本许可](https://github.com/iPortalTeam/ImmersivePortalsModForForge/blob/f60c277f8971d64a580faa5a110e410742b489e9/LICENSE)为 Apache-2.0；若未来采用其源码，需保留适用的许可、声明和修改记录。首版先使用外部依赖。

## 9. 研究转为代码的顺序

| 优先级 | 源码 | 应产出的本项目能力 |
| --- | --- | --- |
| 1 | PortalAPI、VerticalConnectingPortal | 可重复创建/清理的双向测试边界 |
| 2 | Server/ClientTeleportationManager | 单驾驶员状态连续与相机证据 |
| 3 | ChunkTrackingGraph、PacketRedirection | 有预算、能释放的目标世界预热 |
| 4 | ClientWorldLoader、两个 Renderer | 正确 pass 上下文与深度合成 |
| 5 | 行星专用数据结构 | 不额外生成全地图的局部真实地形代理 |

每项完成都要附对应版本、测试配置、日志和录像/帧数据。源码阅读支持机制判断，不能替代游戏内验证。
