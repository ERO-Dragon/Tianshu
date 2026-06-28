# AX 天枢助手模块架构与分期设计

## 1. 文档定位

本文描述 AX（辅星）模块在天枢中的模块身份、职责边界、内部子系统、协议关系和分期落地策略。

本文不展开 prompt 细节，也不定义记忆字段协议。Prompt 编排、动态环境检索、静态知识 RAG 和玩家记忆注入的组合方式，见 [AX_Prompt编排与上下文检索设计.md](./AX_Prompt编排与上下文检索设计.md)。Raw Turn、STM、E、向量组、存储和检索策略，见 [AX_记忆策略设计.md](./AX_记忆策略设计.md)。

本文不是最终类图或实现清单。具体类名、文件名、阈值、prompt 文案、RAG uid、索引格式和 token 预算应在实现阶段结合现有代码逐步冻结。

## 2. 模块身份

AX 是天枢内置的默认对话参与方，不是 LLM 模块的子系统。

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

AX 只能在获得 IA 授权后处理玩家可见对话。后台记忆压缩、事实抽取、向量重建、静态知识索引等 AX 内部维护任务不经过 IA 对话仲裁，但仍必须通过协议中心访问 LLM。

## 3. Agent 定位与分期边界

AX 在模块拓扑上与 LLM、IA、TTS 平级，但内部复杂度高于普通功能模块。它不是单纯的 prompt 拼装器，而是一个面向长期 agent 化演进的对话运行时。

分期边界：

- 一期：完整对话工具。必须打通完整记忆系统、动态环境检索、静态知识 RAG、prompt 编排和输出闭环，但不进入工具执行层。
- 二期：轻量 agent。开始引入意图理解、只读工具框架、工具权限和结果归一化。
- 三期：结构化工具选择与 planner。面向 9B 以上或云端模型探索更强的 function calling 和短链路计划。
- 四期以后：更高级的 agent 行动能力和世界动作安全设计。

一期虽然不做完整工具调用，但整体框架必须为后续 agent 化预留扩展点。这里的“预留”不是提前实现二期框架，而是避免把动态环境、静态知识、玩家记忆、输出动作全部硬编码进一个巨大的 PromptPlanner。一期只保留必要的职责边界，例如上下文提供、知识命中适配、prompt contributor 和预算策略；二期再按需要插入 IntentRouter、ToolRegistry 和 ToolExecutor。

模型能力决定启用深度：

| 模型档位 | AX 能力策略 |
|---|---|
| 2B / 极小模型 | 一期以普通对话、动态环境注入、静态知识 RAG、玩家记忆和固定上下文需求判断为主；二期后的 function calling 只作为实验功能或规则驱动兜底。 |
| 本地 7B-12B | 二期后可启用有限工具选择、结构化意图解析、短链路计划和多源上下文解释。 |
| 云端或更强模型 | 三期后可启用更完整的 planner / tool calling / 多步执行，但仍由 AX 代码控制权限、预算和结果校验。 |

无论模型多强，AX 都不把工具权限、记忆生命周期或执行安全交给 LLM 自行决定。LLM 只能提出结构化意图、候选动作或文本结果，最终调度由 AX 代码执行。

## 4. 总体调用链

玩家可见对话：

```text
ASR / 输入源
  -> IR 解析与修正
  -> IA 仲裁
  -> AX 获得 owner
  -> AX 构造上下文与 prompt
  -> LLM_REQUEST lane=CHAT
  -> AX 输出处理
  -> UI / TTS / session release
```

后台维护任务：

```text
AX maintenance
  -> Raw Turn / STM / 静态资料 / 派生索引快照
  -> LLM_REQUEST lane=TASK 或 LLM_PRIMITIVE_QUERY
  -> 解析压缩、事实抽取、向量化或索引结果
  -> 写入 AX 存储或通过 LLM_CACHE_MANAGE 更新静态 RAG cache
```

AX 后台维护状态通过协议中心发布 `MODULE.STATUS` topic，供 Presence、诊断面板或外部集成观察。该 topic 只承载脱敏状态，例如“记忆整理中 / 完成 / 失败”，不承载 prompt、玩家输入、记忆正文、RAG hit 或动态环境快照。本轮 prompt 所需动态环境仍必须走能力请求/回传，不从 topic 读取。

底层原语任务：

```text
AX
  -> LLM_PRIMITIVE_QUERY TOKEN_COUNT / EMBED / STATUS
  -> 更新预算估算、向量组、embedding namespace 或运行态判断
```

所有跨模块调用都必须经过协议中心。

## 5. 职责边界

AX 负责：

- 注册为 IA participant。
- 校验 IA 投递的 session / turn / owner。
- 维护辅星人格、语气和行为边界。
- 通过协议中心能力请求获取动态环境快照。
- 管理 AX 私有记忆系统。
- 接入静态知识库 RAG。
- 规划 prompt 分区和 token 预算。
- 通过协议中心调用 LLM。
- 处理 LLM 输出、取消、失败和 session release。
- 在授权场景下调用 TTS。
- 为二期以后的意图理解、工具调用和 planner 保留扩展接口。

二期以后，AX 还负责：

- 识别玩家意图、上下文依赖和可执行请求。
- 选择可用只读工具。
- 校验工具权限、参数、超时和结果。
- 决定工具结果是否进入最终回答。

AX 不负责：

- 管理 LLM server 进程。
- 直接调用 `LLMService` 或 `JavaLlamaServer`。
- 直接写 LLM RAG cache 二进制文件。
- 仲裁其他模块的对话所有权。
- 处理非 owner 的玩家正文。
- 绕过工具权限直接操作游戏状态。
- 让 LLM 自行执行未经 AX 校验的 function call。
- 广播 prompt、玩家输入、玩家记忆、RAG hit 明细或完整 LLM response。

LLM 模块只负责基础能力：

- `LLM_REQUEST`：CHAT / TASK 推理。
- `LLM_PRIMITIVE_QUERY`：token count、embedding、status。
- `LLM_CACHE_MANAGE`：静态知识库 RAG cache 管理。
- usage、stream chunk、rag hits 等协议返回。

## 6. 内部子系统

推荐长期内部结构：

```text
AXModule
  ├─ AXProtocolAdapter
  ├─ AXParticipantRegistrar
  ├─ AXDialogueGateway
  ├─ AXConversationService
  ├─ AXInputNormalizer
  ├─ AXRuntimeContextCollector
  ├─ AXStaticKnowledgePlanner
  ├─ AXMemorySystem
  ├─ AXPromptPlanner
  ├─ AXPromptRenderer
  ├─ AXMaintenanceCoordinator
  ├─ AXOutputProcessor
  ├─ AXAccessController
  ├─ AXDiagnosticsView
  ├─ AXIntentRouter        二期以后实装
  ├─ AXToolRegistry        二期以后实装
  ├─ AXToolExecutor        二期以后实装
  └─ AXAgentPlanner        三期以后实装
```

一期核心子系统：

- `AXDialogueGateway`：接收 IA 定向输入，校验 owner/session/turn。
- `AXConversationService`：组织一次玩家可见对话的主流程。
- `AXInputNormalizer`：规范化当前输入，不改变语义。
- `AXRuntimeContextCollector` / `AXRuntimeContextClient`：通过能力请求获取动态环境快照，输出短 TTL 事实。
- `AXStaticKnowledgePlanner`：选择静态知识库 scope / query context，并把底层检索结果适配为 AX 自己的知识命中对象后交给 prompt 编排层。
- `AXMemorySystem`：维护 Raw Turn、STM、E、向量组、检索和 STM 注入片段。
- `AXPromptPlanner`：决定本轮上下文预算和分区内容。
- `AXPromptRenderer`：渲染最终 message chunk；动态环境、静态知识命中、玩家记忆和近期对话都由 AX 先整理后进入 message。
- `AXMaintenanceCoordinator`：调度压缩、事实抽取、向量重建、索引 checkpoint。
- `AXOutputProcessor`：处理流式输出、最终结果、TTS 和 session release。
- `AXAccessController`：统一处理 IA 授权、协议调用权限和后续工具权限边界。
- `AXDiagnosticsView`：只输出脱敏状态。

一期应保留的扩展接口：

```text
AXContextProvider
  通过协议能力提供动态环境、近期对话、模型状态等上下文片段

AXKnowledgeProvider
  提供静态知识库 uid / scope / query context

AXMemoryProvider
  提供玩家记忆检索和 STM 注入片段

AXPromptContributor
  把某类上下文渲染成 prompt 分区

AXExecutionPolicy
  为二期工具调用预留权限、预算、超时和可见性判断入口
```

这些接口只表达职责边界，具体命名和方法签名留到实现阶段确定。重点是避免把所有逻辑写死在单个 prompt 构造函数中。

## 7. LLM 协议使用

AX 对 LLM 的访问必须经过协议中心。

允许：

- `ProtocolCapabilities.LLM_REQUEST`
- `ProtocolCapabilities.LLM_PRIMITIVE_QUERY`
- `ProtocolCapabilities.LLM_CACHE_MANAGE`

典型用途：

| 能力 | AX 用途 |
|---|---|
| `LLM_REQUEST lane=CHAT` | 玩家可见回答 |
| `LLM_REQUEST lane=TASK` | STM 压缩、事实抽取等后台任务 |
| `LLM_PRIMITIVE_QUERY / TOKEN_COUNT` | 窗口和注入预算估算 |
| `LLM_PRIMITIVE_QUERY / EMBED` | E 向量化和向量组重建 |
| `LLM_PRIMITIVE_QUERY / STATUS` | 模型、上下文和 embedding 空间快照 |
| `LLM_CACHE_MANAGE` | 静态知识库 RAG cache 管理 |

禁止：

- 直接调用 LLM 服务实现类。
- 直接访问 Java-LlamaServer。
- 直接写 LLM cache 文件。
- 让 UI/TTS 直接订阅底层 LLM stream。

玩家可见对话必须携带 IA 授权上下文；后台 TASK 不伪造对话授权。

## 8. 存储布局

AX 私有记忆存储以 `AXStorageLayout` 为准，推荐逻辑根：

```text
config/Tianshu/module/ax/cache/
  shared/
  worlds/<worldId>/
    raw_turns/
    stm_blocks/
    events/
      attached_world_events.jsonl
    vectors/
    indexes/
    stats/
```

静态知识库 RAG cache 归 LLM 模块管理，推荐逻辑位置见 LLM 文档：

```text
config/Tianshu/module/llm/ragCache/<world>/
config/Tianshu/module/llm/ragCache/global/
```

边界：

- AX 私有记忆不写入 LLM RAG cache。
- 静态知识库可以通过 `LLM_CACHE_MANAGE` 写入 LLM RAG cache。
- 动态环境不持久化为长期记忆。
- 派生索引和 stats 可重建，不应污染权威记忆数据。

持久化兼容红线：

- 已发布的权威数据文件不能靠删除字段、改字段语义或重排旧记录来升级。
- Raw Turn、STM、E、向量组元数据等权威记录必须带 `schemaVersion` 或等价版本标识。
- 附属世界事件是 STM 的旁证记录，属于 AX 私有记忆权威数据的一部分，但不等同于 E；策略允许的事件可以在绑定 STM 后直接写入该 STM 的 E 集，且该 E 必须引用所属 STM。
- 主记录优先追加；需要 compaction 时写出新快照，并保留能从旧版本读取或迁移的代码路径。
- 派生索引、offset、聚类快照和 stats 可以重建，因此可以替换；但替换必须通过临时文件和原子替换完成。
- 业务代码不得散落物理路径，所有路径通过 `AXStorageLayout` 或迁移器获得。
- 新版本只增不破坏旧数据；确需废弃字段时，读路径继续兼容旧字段，写路径写新字段。

提示词资源属于 AX 配置资源，不属于 MC 翻译资源。推荐 common 内置 JSON catalog，并在首次运行时释放到 `ax/cache/shared/prompts/` 供玩家覆盖；Java 代码只按 key 读取和渲染变量，避免把压缩、抽事实、prompt 编排文案硬编码在流程代码里。顶层 prompt 顺序只通过 `ax_system`、`game_context`、`player_memory`、`provided_context`、`recent_dialogue`、`current_input` 这类语义区块配置。NeoForge 的语言文件只负责玩家可见 UI / Presence 文案。

玩家可见 CHAT 的最终请求由 AX 组装为单一 message chunk。静态知识可以复用 LLM RAG cache 做检索和缓存，但进入最终 prompt 前必须先被 AX 适配为知识命中结果，再渲染到 `<game_context>`；AX prompt 编排层不直接持有 LLM rag chunk 作为自己的上下文结构。

## 9. 输出与会话控制

AX 输出处理必须遵守 IA 会话边界：

- 只有当前 owner 会话可产生玩家可见回复。
- 取消、失败、超时时必须释放或更新 session 状态。
- TTS 调用必须在授权链路内发生。
- 流式输出可以转发给授权 UI/TTS sink，但不得广播到底层公共 topic。
- 后台 TASK 结果不直接变成玩家可见文本。

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
- TTS 待播完整文本。
- 二期以后工具调用的原始参数和底层返回对象。

允许输出：

- AX 当前阶段。
- 是否 owner。
- session / turn 的脱敏 ID。
- 记忆条目数量。
- pending task 数量。
- 最近错误码。
- 脱敏后的短状态文案。

普通日志不得包含正文；需要调试正文时必须走显式调试开关和授权流程。

## 11. 分期落地

实现按阶段推进。每一期都应能独立运行，不要求后续 agent 能力完成后才能提供基础对话。

### 11.1 一期：完整对话工具、记忆系统与 RAG

目标：让 AX 成为 IA 授权下可用的完整对话工具。该阶段必须打通完整记忆系统、动态环境检索、静态知识 RAG 和 prompt 编排闭环，但不进入 agent 工具执行层。

范围：

- IA 授权入口和 `AXDialogueGateway`。
- 上下文 Provider / Prompt Contributor 的基本框架。
- 动态环境能力请求、短 TTL 检索和 `<game_context>` 注入。
- 静态知识库 `LLM_CACHE_MANAGE` 接入，以及静态知识命中内容到 `<game_context>` 的编排。
- 玩家输入直接检索静态知识库。
- 玩家输入先检索动态环境，再用命中的环境信息检索静态知识库。
- Raw Turn、STM、E 的完整记忆链路。
- E 检索到 STM 映射注入。
- STM 链式发散和模型预算自适应。
- `LLM_PRIMITIVE_QUERY / TOKEN_COUNT` 用于预算估算。
- `LLM_PRIMITIVE_QUERY / EMBED` 用于 E 向量化。
- 向量组按 namespace 批量重建。
- 聚类、有效映射索引、stats 和 compaction 等记忆/RAG 派生索引能力。
- 基础安全、脱敏日志、失败收敛。
- 为二期保留 `AXExecutionPolicy` 和工具扩展边界，但不开放工具执行。

一期不要求：

- 完整 function calling。
- `ToolRegistry` / `ToolExecutor` 运行时闭环。
- 多步 planner。
- 改变游戏状态的工具。
- 模型自主工具选择。

### 11.2 二期：Agent 化、轻量意图与只读工具框架

目标：在一期完整对话工具之上，让 AX 从“会组织上下文的助手”升级为轻量 agent，但仍以稳定性和可控性为主。

范围：

- `IntentRouter` 的规则兜底和简单意图分类。
- `ToolRegistry` / `ToolExecutor` 的只读工具框架。
- 环境查询、知识查询、记忆查询等只读工具。
- 工具权限、超时、失败和结果归一化。

二期仍不默认启用复杂模型自主工具选择。2B 模型主要走规则和固定流程，7B-12B 可尝试有限结构化意图。

### 11.3 三期：结构化工具选择与 Planner

目标：面向 9B 以上或云端模型，引入更强 agent 能力。

范围：

- 结构化工具选择实验。
- `AXAgentPlanner` 的短链路计划。
- 多源上下文需求判断。
- 多轮计划状态的保存和恢复。
- 工具结果进入 prompt 前的统一校验和归一化。

三期原则：

- LLM 可以提出候选工具调用，但不能直接执行。
- AX 必须做权限校验、参数校验和结果校验。
- 改变游戏状态的工具仍默认不开放。

### 11.4 四期：高级记忆索引与执行能力扩展

目标：在一期记忆/RAG稳定、二三期 agent 能力稳定后，继续增强检索质量和 agent 行动能力。

范围：

- 更高级的聚类维护、热度统计和检索调优。
- 更细的动态环境匹配策略。
- 更复杂的任务计划和提醒。
- 需要授权的输出动作扩展。
- 世界动作类工具的单独安全设计。

世界动作类工具必须另行设计权限、确认、回滚和审计，不在本文阶段内默认开放。

## 12. 最终原则

- AX 是业务模块，LLM 是能力模块。
- IA 决定谁能处理当前对话，AX 只处理授权输入。
- 一期是完整对话工具，不是最小 demo。
- 二期以后才进入工具调用和 agent 执行层。
- 一期架构必须为二期 agent 化保留扩展接口。
- 玩家记忆由 AX 自己管理，不进入 LLM RAG cache。
- 静态知识复用 LLM RAG cache，不污染 AX 记忆。
- 动态环境是短 TTL 上下文，不是长期记忆。
- Prompt 编排属于 AX，推理执行属于 LLM。
- 工具调用由 AX 授权、调度和校验，LLM 不能自行执行。
- 所有跨模块调用走协议中心。
- 后台维护不阻塞玩家可见对话。
- 诊断默认脱敏，日志不暴露正文。
