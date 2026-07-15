# 辅星-自然交互基线设计

## 1. 文档定位

本文定义辅星一期的总纲。它不只描述 prompt，也不只描述记忆，而是把 AX 在天枢里的模块身份、调用链、内部子系统、协议关系、存储边界和分期方向放到同一张图里。

本文面向的是“一期可上线的完整对话工具”，不是最小 demo。它必须能独立完成授权对话、上下文组织、记忆注入、输出收口和后台维护；但它仍不进入技能执行层，不接管世界动作，不让 LLM 自主决定工具或行动。

相关细节分别见：

- [AX_Prompt编排与上下文检索设计.md](./AX_Prompt编排与上下文检索设计.md)
- [AX_记忆策略设计.md](./AX_记忆策略设计.md)
- [AX_辅星技能协作设计.md](./AX_辅星技能协作设计.md)

## 2. 模块身份

AX 是天枢内置的默认对话参与方，不是 LLM 的子系统。

```text
module.ax
  负责辅星人格、对话组织、上下文编排、私有记忆、动态环境筛选、静态知识接入和输出处理

module.ia
  负责对话所有权仲裁和会话授权

module.llm
  负责基础推理、token 计数、embedding、RAG cache 和协议返回

module.tts
  负责授权后的语音输出
```

AX 只能在获得 IA 授权后处理玩家可见对话。后台记忆压缩、事实抽取、向量重建和索引维护不经过 IA 对话仲裁，但仍必须通过协议中心访问 LLM。

## 3. 一期结构

一期由 `core` 和 `module` 两部分组成，二者是同级目录。

- `core` 负责整体排布、装配顺序和最终输出收口。
- `module` 负责五块内容的具体实现。

`module` 下按五块并列组织：

- `AXSystem`：人设、行为规则、预设提示词和 system 区块渲染。
- `AXGameContext`：内部按静态与动态路径组装；进入 CHAT 时渲染为模型可理解的“当前处境相关信息”和“当前问题相关游戏知识”，其中当前处境相关信息包含命中的动态事实及其关联出的静态 RAG 内容。
- `AXMemory`：STM、E、检索、压缩、原子化和索引；消费 `AXRecentDialogue` 剥离出的只读 Raw Turn 快照。
- `AXRecentDialogue`：实时上下文窗口与近期完整对话时间线，持有 Raw Turn / Raw Turn Window。
- `AXCurrentInput`：当前输入规范化与 user 区块渲染。

`core` 与 `module` 的关系是：`module` 供给内容，`core` 负责编排和收口。`module` 内部也不是平铺类堆，而是按“模块 -> 子模块 -> 孙模块”组织。

## 4. 结构树

```text
AX
  core
    prompt orchestration
      AXPromptOrchestrator
      AXPromptAssemblyBuilder
      AXPromptAssembly
    turn control
      AXDialogueGateway
      AXAccessController
      AXOutputProcessor
  module
    AXSystem
      profile
        AXPromptProfile
        AXPromptResourceRepository
        AXPromptLanguageProvider
      rendering
        AXSystemPromptContributor
        AXPromptTexts
    AXGameContext
      dynamic facts
        AXDynamicFactClient
        AXDynamicKnowledgeFormatter
        AXContextCollector
        AXDynamicFact
      static knowledge
        AXGameContextKnowledgePlanner
        AXKnowledgeHit
    AXMemory
      short term memory
        AXStmBlock
        AXStmBlockStore
      atomic event
        AXMemoryEvent
        AXEventVector
        AXMemoryRetrievalIndex
      retrieval
        AXMemoryRetriever
        AXMemoryRetrievalPolicy
        AXMemoryRetrievalTrace
      maintenance
        AXMemoryMaintenanceService
        AXMemoryTaskPromptRepository
        AXMemoryDerivedMaintenanceService
    AXRecentDialogue
      timeline
        AXRawTurn
        AXRawTurnWindow
        AXRecentDialoguePromptContributor
    AXCurrentInput
      normalization
        AXDialogueInputMapper
        AXInputNormalizer
      rendering
        AXCurrentInputPromptContributor
```

这棵树表达的是职责层次，不是强制类图。实现可以随着代码成熟继续拆分，但不能再把一期写成一串互不分层的平铺项。

## 5. 调用链

### 5.1 玩家可见对话

```text
ASR / 输入源
  -> IR 解析与修正
  -> IA 仲裁
  -> AX 获得 owner
  -> AX 规范化输入
  -> AX 组织上下文与 prompt
  -> LLM_REQUEST lane=CHAT
  -> AX 输出处理
  -> UI / TTS / session release
```

### 5.2 后台维护任务

```text
AX maintenance
  -> Raw Turn / STM / 派生索引快照
  -> LLM_REQUEST lane=TASK 或 LLM_PRIMITIVE_QUERY
  -> 解析压缩、事实抽取、向量化或索引结果
  -> 写入 AX 存储或重建派生索引
```

AX 后台状态可以通过 `MODULE.STATUS` topic 发布给观测面板，但该 topic 只承载脱敏状态，不承载 prompt、玩家输入、记忆正文或 RAG hit。当前轮 prompt 需要的动态环境仍必须走能力请求/回传。

## 6. 职责边界

AX 负责：

- 注册为 IA participant。
- 校验 IA 投递的 session / turn / owner。
- 维护辅星人格、语气和行为边界。
- 通过协议中心获取动态环境客观快照，并在 AX 内部转换为动态知识候选。
- 管理 AX 私有记忆系统。
- 接入静态知识库 RAG。
- 规划 prompt 分区和 token 预算。
- 通过协议中心调用 LLM。
- 处理 LLM 输出、取消、失败和 session release。
- 在授权场景下调用 TTS。

AX 不负责：

- 管理 LLM server 进程。
- 直接调用 `LLMService` 或 `JavaLlamaServer`。
- 直接写 LLM RAG cache 二进制文件。
- 仲裁其他模块的对话所有权。
- 让 LLM 自行执行未经 AX 校验的 function call。

LLM 模块只提供能力：

- `LLM_REQUEST`：CHAT / TASK 推理。
- `LLM_PRIMITIVE_QUERY`：token count、embedding、status。
- `LLM_CACHE_MANAGE`：shared 静态知识库 RAG 管理，以及 AX 私有记忆检索投影的 uid/entryId 检索。

Presence / 映迹只提供客观上下文事实和结构化字段，不负责 AX prompt 语义加工。AX 通过 `AXDynamicKnowledgeFormatter` 消费 Presence `nativeValues`，生成用于临时动态 RAG 和 `<game_context>` 的模型友好动态知识文本；例如背包物品数量会在 AX 内部表达为“少量 / 半组 / 一组 / 多组”，而不是要求 Presence 输出 AX 专属文本。

## 7. 记忆与 Prompt

AX 的私有记忆权威数据不进入 LLM shared RAG 库。`AXRecentDialogue` 持有实时上下文窗口：窗口内保留近期完整 Raw Turn，容量超限时从最老完整轮次开始剥离只读快照。剥离出的纯聊天内容交给 `AXMemory` 后台压缩为 STM；世界事件只进入运行时附属事件视图，只有策略允许的事件才转成 E 持久化，随后才进入原子化、私有 RAG 投影检索、E -> STM 折叠和 STM 注入链路。静态知识库可以复用 LLM 的 shared RAG cache，但进入最终 prompt 前必须先被 AX 适配成知识命中结果，再渲染到 `<game_context>`。

Prompt 编排与记忆策略的硬约束见对应专题文档。这里只强调一点：一期不是“静态、动态、记忆”的无差别拼接，而是 `core` 统一收口、`module` 各自供料、最后按预算装配成一个 message chunk。

## 8. 存储布局

AX 私有记忆存储以 `AXStorageLayout` 为准，逻辑上建议如下：

```text
config/Tianshu/module/ax/cache/
  shared/
  worlds/<worldId>/
    raw_turns/
    stm_blocks/
    events/
    vectors/
    indexes/
    stats/
    ...
```

边界：

- AX 私有记忆的权威 Raw Turn、STM、E 不写入 LLM RAG cache；可重建的检索投影可以写入 AX 私有 uid。
- 静态知识库通过 `LLM_CACHE_MANAGE` 写入 LLM RAG cache。
- 动态环境不持久化为长期记忆。
- 世界事件不作为事件日志持久化；只保留运行时短窗口，必要时由策略转成 E。
- 派生索引和 stats 可重建，不应污染权威记忆数据。

持久化红线：

- Raw Turn、STM、E、向量组元数据等权威记录必须带版本标识。
- 附属世界事件是运行时旁证，不等同于 E，不作为 `world_events` 文件落盘。
- 需要 compaction 时写出新快照，再原子替换。
- 业务代码不得散落物理路径。

## 9. 输出与会话控制

AX 输出处理必须遵守 IA 会话边界：

- 只有当前 owner 会话可产生玩家可见回复。
- 取消、失败、超时时必须释放或更新 session 状态。
- TTS 调用必须在授权链路内发生。
- 流式输出可以转发给授权 UI / TTS sink，但不得广播到底层公共 topic。

LLM 失败时，AX 应返回简短、脱敏的失败状态，不暴露 prompt、RAG hit、记忆正文或异常堆栈。

## 10. 安全与诊断

默认不得输出：

- 玩家完整输入。
- prompt 完整内容。
- system prompt。
- 动态环境完整快照。
- 玩家记忆正文。
- RAG hit 明细。
- LLM 完整 response。

允许输出：

- AX 当前阶段。
- 是否 owner。
- session / turn 的脱敏 ID。
- 记忆条目数量。
- pending task 数量。
- 最近错误码。
- 脱敏后的短状态文案。

## 11. 分期落地

### 11.1 一期：完整对话工具、记忆系统与 RAG

目标是把 AX 做成 IA 授权下可用的完整对话工具。

范围：

- IA 授权入口和 `AXDialogueGateway`。
- 动态事实能力请求、动态事实路径 RAG 与 `<game_context>` 动态内容组装。
- 静态知识库接入与命中结果编排。
- Raw Turn、STM、E 的完整记忆链路。
- E 检索到 STM 映射注入。
- STM 链式发散和模型预算自适应。
- `LLM_PRIMITIVE_QUERY / TOKEN_COUNT` 用于 message-only prompt 的精确 token 计数和预算校验。
- `LLM_PRIMITIVE_QUERY / EMBED` 用于 E 向量化。
- 向量组重建、聚类、有效映射索引、stats 和 compaction。

一期不要求：

- 完整 function calling。
- `ToolRegistry` / `ToolExecutor` 闭环。
- 多步 planner。
- 改变游戏状态的工具。

### 11.2 二期：Agent 化

二期就是 AX 的 agent 化阶段，不再继续拆三期、四期。二期在一期稳定对话能力之上，引入意图理解、工具协作、受控执行和结果回流：

- `IntentRouter` 的规则兜底和模型辅助意图判断。
- `ToolRegistry` / `ToolExecutor` 的受控工具框架。
- 只读工具优先，世界动作类工具必须另行经过权限、确认和审计设计。
- 工具权限、超时和失败处理。
- 工具结果进入 prompt 前的统一归一化、脱敏和预算裁剪。

原则仍然不变：LLM 只能提出候选意图、候选工具或文本结果，AX 负责权限校验、参数校验、执行调度和结果校验。

## 12. 最终原则

- AX 是业务模块，LLM 是能力模块。
- IA 决定谁能处理当前对话，AX 只处理授权输入。
- 一期是完整对话工具，不是最小 demo。
- 二期进入 agent 化和工具协作，不能再拆成三期、四期来延后定义。
- 玩家记忆由 AX 自己管理；LLM RAG 只承接 AX 私有检索投影，不拥有玩家记忆语义。
- 静态知识复用 LLM RAG cache，不污染 AX 记忆。
- 动态环境是短 TTL 上下文，不是长期记忆。
- Prompt 编排属于 AX，推理执行属于 LLM。
- 工具调用由 AX 授权、调度和校验，LLM 不能自行执行。
- 所有跨模块调用走协议中心。
- 后台维护不阻塞玩家可见对话。
- 诊断默认脱敏，日志不暴露正文。

## 13. 一期实施缺口记录

以下内容已经在设计上纳入一期，但代码里还没有完全做到位，先保留在文档末尾，不在这里硬补：

- 记忆检索和 prompt 预算已经接入 LLM `STATUS.contextTokenBudget`、`AXMemoryWindowPolicy.fromBudget`、`AXContextBudget.fromPolicy` 这些入口；`contextTokenBudget` 按单次请求输入 + 输出总上限处理，AX 默认只取其中 60% 作为 CHAT/TASK 输入目标并保留输出、thinking/CoT 与安全余量。CHAT baseline 已覆盖 `ax_system`、`game_context` 知识池、`player_memory` 检索/近期摘要、`recent_dialogue` raw 窗口和 `current_input`；`ax_system` 通过 LLM `TOKEN_COUNT` 精确计数后选择 short / standard / full 完整 system prompt 档位，三档都作为原子文本，不按字符截断。后续需要继续结合本轮密度评估和命中分数动态裁剪，不得再引入文档未定义的额外 prompt 分区。
- 二期 agent 化的工具协作还只是边界规划，尚未形成独立的工具注册与执行子系统。

以下条目已在本次整改中完成：

- ~~`AXMemory` 的 Hot / Warm / Cold 分层召回还没有形成独立的预算策略入口，现有实现更接近单一路由 + 加权精排。~~ **已完成**：`AXMemoryRetrievalPolicy` 新增 `hotScoreThreshold` / `warmScoreThreshold` / `coldScoreThreshold` 三层分数阈值与 `hotBudgetRatio` / `warmBudgetRatio` 预算比例，`AXMemoryRetriever.selectBlocks` 改为按三层分别选择，每层有独立的 block/token 预算上限，不足时剩余预算回补给下一层。
- ~~`AXPromptAssemblyBuilder` 目前还是通用 message 组装器，尚未增加“只接受折叠后的 STM 块”的硬校验层。~~ **已完成**：`AXMemoryBlockView.block()` 类型即 `AXStmBlock`，编译期排除原始 turn 进入 prompt 的可能，无需额外校验层。
- ~~`AXMemoryMaintenanceService` 已经任务化，但“只读快照输入”的边界还主要依赖调用约定，缺少更显式的快照输入类型。~~ **已完成**：`AXRawTurnBatch` 作为显式只读快照输入类型，`AXMemoryMaintenanceService` 通过 `recentDialogueSystem.selectCompressionBatch(scope)` 消费，不再持有 window 引用。
- ~~`AXGameContext` 与 `AXMemory` 虽然已经按并列模块思路收口，但当前上下文装配链路里仍保留了少量显式传递，后续还可以继续减耦。~~ **已完成**：两模块并列，无显式传递，融合只在 `AXContextCollector` 末端装配。
- ~~`AXRecentDialogue` / `AXMemory` 的代码边界仍需继续整改：Raw Turn Window 应由 `AXRecentDialogue` 持有，`AXMemory` 只消费剥离出的只读快照；当前代码里仍残留部分记忆侧持有或调度 Raw Turn 的旧路径。~~ **已完成**：`AXRawTurnWindow` 由 `AXRecentDialogueSystem` 持有，memory 侧只消费 `AXRawTurnBatch` 只读快照。
- ~~`AXMemory` 内部的子模块化拆分还在进行中，当前已经开始把 prompt 编排拆成 `core/module`，但记忆内部的 `retrieval / maintenance / event / stm` 还没有全部完全落到同等层级。~~ **已完成**：`module/memory/` 下 `event/`、`maintenance/`、`retrieval/`（含 `index/`）、`shortterm/` 四子目录已到位。

## 14. 配置与存储端口

AX 不再接收跨模块配置总接口。模块只获得 `AXStorageConfiguration.storageRoot()`、`AXAssistantSettings`、`AXOutputSettings` 和内部稳定 policy；记忆窗口策略不再借用 LLM 配置命名空间。NeoForge 仍通过单一 `config/tianshu-client.toml` 提供玩家设置，AX 权威记忆继续写入独立的世界分层存储，不与 TOML 配置文件混合。
