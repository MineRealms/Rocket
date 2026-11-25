# Voxy 与真实世界远景 LOD

更新：2026-11-25。当前环境：Forge 1.20.1 / 47.4.29、Java 17、IP Forge 3.0.7、Oculus 1.8.0、Embeddium 0.3.31。

## 选择

使用 ROCKET 自有的真实地表采样和 shader 投影。Voxy 保留为可选兼容目标，不作为当前依赖。

| 项目 | 核实结果 |
| --- | --- |
| 上游 Voxy | 官方版本页列出 Fabric，未提供 Forge 1.20.1 构建；依赖 Fabric/Sodium。见[官方版本](https://modrinth.com/mod/voxy/versions)、[上游元数据](https://github.com/MCRcortex/voxy/blob/dev/src/main/resources/fabric.mod.json)。 |
| 社区 Neo Voxy | README 列出 Forge 1.20.1、Java 17、Embeddium/Oculus 客户端构建，并声称 shader 支持；不能因此推断已兼容 IP。见[社区仓库](https://github.com/NHblock-Johnsnow/neo-voxy-multiversion/blob/multiversion/README_EN.md)。 |
| 许可 | 上游和上述 fork 保留 ARR 文本。当前只研究架构，不复制或捆绑其源代码/构建产物。见[上游许可](https://github.com/MCRcortex/voxy/blob/dev/LICENSE.md)。 |
| 数据接口 | 未确认稳定公开的 LOD 数据提供接口。不能把内部 WorldEngine 当成 ROCKET 的固定 API。 |
| shader / IP | Oculus 能加载该模组不代表 LOD 会参与当前 pack 的深度、IP 远端维度 pass、裁剪和融合；必须针对固定版本实测。 |

借鉴的是“分层数据、分级细节、持久缓存、有界上传”的组织方式。ROCKET 当前使用 2.5D 颜色/高度地图；Voxy 的体素 LOD 与原生几何绘制复杂得多，不能称当前实现已经等同 Voxy。

## 当前实现

1. 玩家位于主世界 Y>=300 或 Space 时，服务端为其建立以主世界 x/z 为中心的 32,768 方块宽 atlas。
2. 按粗到细顺序取真实 FULL 区块，将该区块 16×16 个地表列的地图色、生物群系色与高度聚合，不用 shader 噪声伪造大陆。现有区块直接读取，未生成区块走当前世界的真实生成器。
3. 同时最多一个待完成请求，每秒最多发起 5 次。通过 region ticket 驱动生成，每 tick 非阻塞查询结果；不调用会在服务端等待的 `getChunkFuture`。完成、超时、换中心、登出和关闭时释放本模组 ticket。失败最多尝试三次，重试队列有 64 条上限。缓存按本存档保存，无跨存档混用。
4. 先建立 16×16 的粗样本，再细化为 32×32、64×64、128×128。最终每格跨度 256 方块；客户端在相邻实际样本间插值，不将一个粗样本直接铺成 2048 格色块。已表示格数不等于实际采样数。
5. 本地已有区块的 4 方块/格地图叠加近处细节。Oculus shader 按真实 x/z 读取宽域 atlas，计算高度明暗、曲率和大气。Y1400 至 Space 任意高度沿用同一近轨道场景。
6. 主世界时间驱动地面到大气边缘的光照；H1240–1400 平滑切换到固定轨道太阳方向。进入近地轨道后，Space 的 `/time` 不改变地表或大气光照。shader 仅处理主世界/Space 的天空像素，不替换水下视图或其他维度。

## 操作与限制

- `rocketmod-common.toml` 的 `generateOrbitalTerrain` 控制实际区块生成，默认 true。
- `/rocketmod terrain status` 查看真实采样进度；`/rocketmod terrain refresh` 清除持久样本并重新采集。
- 冷缓存需要真实生成耗时和磁盘空间。该节流是请求数上限，不保证固定 MSPT；单次区块生成及其依赖仍可能较重。
- 真实来源不代表逐方块完整复现：每 256 格的远景格使用一个 16×16 区块的地表聚合，结构和细窄地形可能遗漏。后续应提高近处 LOD 密度、扩大区域聚合和几何重投影。
- 已缓存区域的自然变化、爆炸或模组改方块可能过时；显式刷新可重新采样。break/place 会失效对应格并为活动视图排入重采样。
- 在每秒 5 个新请求的上限下，16,384 个样本完全冷生成的理论下限约 55 分钟；实际取决于生成耗时。首轮 256 个粗样本先提供全范围预览，后续不断加细。
- 资源重载保留 CPU 地图，重建 GPU 纹理。当前宽域地图仅接入 Oculus 路径，无光影的原生回退仍使用局部地图。
- 有限 atlas 外围保持中性未采样底色，不冒充真实海洋。它不是无限世界生成器或完整星球地理。

## 后续兼容门禁

Neo Voxy 单独验证：Forge 启动、Oculus 开关、ROCKET pack 深度顺序、主世界远景、Space 回看、IP 双向自然穿越、资源重载与登出清理。通过后才能列入兼容清单；找到许可允许、版本固定的公开接口后，再考虑共享数据。
