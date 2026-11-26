# 文档索引

> 唯一入口。**活文档**随代码更新；**历史**只读，不再改。
> 规则见文末 §维护规则；架构层从 19 篇瘦身到 7 篇（2026-11-16），旧文全部归档到 `history/`。

## 一、架构（3 篇，按职责分工，不重复）

> **舟楫自己的文档随舟楫仓库走**：职责边界、智能体能力中心、实体、技能撰写规范现在在 `Tianshu-ferry/doc/`
> （入口 `Tianshu-ferry/doc/README.md`）——它们描述基座的决策、契约与内容规范，改动跟基座代码一起走。
> 本仓库保留跨仓库的平台与适配层文档。**不设 history**：过时结论直接改掉，不归档。

| 文档 | 唯一职责 |
|---|---|
| [模块架构](architecture/模块架构.md) | **结构**：产物形态、模块职责、依赖模型、载荷归属、装配、边界门禁 |
| [多加载器与多版本](architecture/多加载器与多版本.md) | 三层怎么切、平台差异、加一个平台或版本的步骤（含版本矩阵结论） |
| [未完成事项](architecture/未完成事项.md) | 施工进度与收口条件（含实体层收口、作弊动作收口） |

## 二、平台参考

> 协议中心与模块宿主的文档随舟楫仓库走：`Tianshu-ferry/doc/协议中心.md`、`Tianshu-ferry/doc/模块宿主.md`。

| 文档 | 内容 |
|---|---|
| [弃用清单](api-deprecations.md) | 处于弃用期的条目与移除计划 |
| [外部接入指南](../EXTERNAL_INTEGRATION_GUIDE.md) | 第三方模组如何依赖天枢与应用接入 API |

## 三、功能模块文档

| 模块 | 入口 |
|---|---|
| 听澜（语音输入） | [ASR 协议](function/intake/ASR_协议中心使用文档.md)、[ASR 架构](function/intake/ASR_模块架构说明.md)、[IR 协议](function/intake/repair/IR_协议中心使用文档.md)、[IR 架构](function/intake/repair/IR_模块架构说明.md)、[游戏内测试说明](function/intake/听澜游戏内测试说明.md) |
| 回响（语音输出） | [TTS 协议](function/echo/TTS_协议中心使用文档.md)、[TTS 架构](function/echo/TTS_模块架构说明.md) |
| 织言（云端对话） | [LLM 接口设计](function/weaving/LLM接口设计.md)、[libs 接口设计](function/weaving/libs接口设计.md)、[黑盒测试](function/weaving/黑盒测试.md) |
| 天枢（对话助手） | [文档索引](function/dubhe/Dubhe_文档索引.md)（运行架构 / 事件系统 / 上下文编排 / 记忆策略 / Agent 运行时 / 协议） |
| 映迹（世界状态与 HUD） | [文档索引](function/presence/Presence_文档索引.md)（产品活动协议 / 模块设计） |

## 四、历史（只读）

> **舟楫相关的历史已清空**：能用的结论全部并进了上面 3 篇与舟楫仓库的 6 篇，剩下的原文删除——
> 归档会让同一件事有两套说法。留在这里的只有**非舟楫**的部分，随各自模块走。

| 文档 | 是什么 |
|---|---|
| [版本功能矩阵](history/版本功能矩阵.md) | 各版本线实现了哪些功能面（结论已并入[多加载器与多版本](architecture/多加载器与多版本.md)） |
| [多版本实测记录](history/多版本实测记录.md) | 逐版本的 API 断点、构建插件坑与实测过程 |
| [织言清洁实施记录](history/织言清洁实施记录.md) | 织言侧的实施流水（协议侧结论已并入 `Tianshu-ferry/doc/协议中心.md`） |
| [libs-1.0.7-更新日志](history/libs-1.0.7-更新日志.md) | 底层库自身的变更记录 |
| `adapter-early/` | 早期适配层设计稿：`AXSettingsGuiDesign`、`ZhiyanLlmGuiDesign`（功能模块内部，随模块走） |

## 维护规则

1. **一种信息只有一个落点**：决策 → 职责边界；能力、工具与验收 → 智能体能力中心；进度 → 未完成事项；过程 → `history/`。
   其余文档只能链接，不复述。前两个落点在舟楫仓库（`Tianshu-ferry/doc/`），跨仓库只能写路径、不做相对链接。
2. 新增文档先判断属于"活 / 历史"，历史不再更新。
3. 活文档改动后，同步本索引与各模块索引里的链接。
4. 功能模块文档放 `function/<模块>/`，模块自己维护入口索引。
5. 平台/适配层文档放 `doc/architecture/` 或 `doc/protocol/`，不在 Gradle 模块目录里新建 `doc/`；
   **舟楫自身**的文档放 `Tianshu-ferry/doc/`，跟基座代码一起改，那里不设 history。
6. 一次性审计、实测、进度报告写进 `history/`，活文档只留当前规则与结论。