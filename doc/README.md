# 文档索引

> 唯一入口。**活文档**随代码更新；**历史**只读，不再改；**计划**是未实施的方案。

## 活文档

| 文档 | 内容 |
|---|---|
| [架构总览](architecture/模块架构.md) | 模块边界、协议中心、生命周期、扩展点——当前架构的唯一权威描述 |
| [多加载器与多版本](architecture/多加载器与多版本.md) | 三层怎么切、平台差异、门禁、加一个平台或版本的步骤 |
| [未完成事项](architecture/未完成事项.md) | 已定但未做的工作与收口条件 |
| [API 兼容策略](architecture/API兼容策略.md) | api 面、1.x 兼容规则、模块版本范围、1.0.0 检查清单 |
| [能力目录设计](architecture/能力目录设计.md) | 能力表：kind × binding、描述符、执行与消费、与织言的分工 |
| [平台能力清单](architecture/平台能力清单.md) | 舟楫与游戏本体的交互面：客户端/服务端 × 读/写，已有/缺/不做 |
| [弃用清单](api-deprecations.md) | 处于弃用期的条目与移除计划 |
| [协议中心](protocol/TianshuProtocolCenter.md) | 信封、broker、topic、生命周期 |
| [协议适配指南](protocol/TianshuProtocolAdapterGuide.md) | 模块如何接协议中心 |
| [CoreManager 说明书](core/CoreManager_说明书.md) | 宿主生命周期与能力的参考手册 |
| [外部接入指南](../EXTERNAL_INTEGRATION_GUIDE.md) | 第三方模组如何依赖天枢与应用接入 API |

## 功能模块文档

| 模块 | 入口 |
|---|---|
| 听澜（语音输入） | [ASR 协议](function/intake/ASR_协议中心使用文档.md)、[ASR 架构](function/intake/ASR_模块架构说明.md)、[IR 协议](function/intake/repair/IR_协议中心使用文档.md)、[IR 架构](function/intake/repair/IR_模块架构说明.md)、[游戏内测试说明](function/intake/听澜游戏内测试说明.md) |
| 回响（语音输出） | [TTS 协议](function/echo/TTS_协议中心使用文档.md)、[TTS 架构](function/echo/TTS_模块架构说明.md) |
| 织言（云端对话） | [LLM 接口设计](function/weaving/LLM接口设计.md)、[libs 接口设计](function/weaving/libs接口设计.md) |
| 天枢（对话助手） | [文档索引](function/auxilium/AX_文档索引.md)（运行架构 / 事件系统 / 上下文编排 / 记忆策略 / Agent 运行时 / 协议） |
| 映迹（世界状态与 HUD） | [文档索引](function/presence/Presence_文档索引.md)（产品活动协议 / 模块设计） |

## 计划

| [工具层收敛计划](architecture/工具层收敛计划.md) |
| [工具层游戏内验收](architecture/工具层游戏内验收.md) | 真实客户端/服务器逐项验收清单 | 审查结论、A3/H3 决策、P0–P5 施工顺序与验收 |

| 文档 | 内容 |
|---|---|
| [工具层设计（RFC）](architecture/工具层设计.md) | 舟楫向其他模组与天枢同时提供能力：类目、默认策略、调用者、侧路由与首批工具（契约层已实现，平台通道与首批工具待做） |

## 历史（只读）

| 文档 | 为什么留 |
|---|---|
| [模块拆分实施记录](history/模块拆分实施记录.md) | 施工顺序、取舍理由、踩过的坑 |
| [织言清洁实施记录](history/织言清洁实施记录.md) | 织言云端化重构：删了什么、为什么保留 tier/RAG 数据面、验证结果 |
| [舟楫初期设计笔记](history/舟楫初期设计笔记.md) | 拆分前的整体设想，说明"为什么长成这样" |
| [舟楫审阅报告（2026-10）](history/舟楫审阅报告-2026-10.md) | 一次完整审计的快照：修完的问题、当时的决策与实机验收记录 |
| [接口对齐审查（2026-11）](history/接口对齐审查-2026-11.md) | 以 javap 为证据的接口核对：Fabric 事实缺口逐条定级、"不做 mixin"的决策依据 |
| [多版本实测记录](history/多版本实测记录.md) | 逐版本的 API 断点、构建插件坑与验证过程（活文档的实测附录） |
| [libs 1.0.7 更新日志](history/libs-1.0.7-更新日志.md) | 本地推理库的接口变更记录 |
| [adapter 早期文档](history/adapter-early/) | 拆分前的 adapter 架构、设置 GUI 框架/接入指南、天枢与织言的设置页设计稿——已被模块架构与外部接入指南取代 |

## 维护规则

1. 新增文档先判断属于"活 / 历史 / 计划"，再决定放在哪个目录；历史文档不再更新。
2. 活文档改动后，检查本索引与各模块索引里的链接是否需要同步。
3. 功能模块的文档放在 `function/<模块>/` 下，模块自己维护入口索引。
4. 平台/适配层文档放 `doc/architecture/` 或 `doc/protocol/`，不再在 Gradle 模块目录里新建 `doc/`。
5. 一次性的审计、实测、进度报告写进 `history/`，不占活文档的位置；活文档只留当前规则与结论。