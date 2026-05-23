# 辅星模块架构与拆分执行计划

## 1. 模块身份

英文名：`AX`

中文名：`辅星`

代号：`辅星`

推荐模块 ID：

```text
module.ax
```

推荐包名：

```text
com.rheinmetal.tianshu.function.auxilium
```

辅星模块是天枢系统内置的默认对话参与方。它不再是 LLM 模块的内部子系统，而是一个独立业务模块。它通过仲裁机关获得对话处理权，通过 LLM 模块获得基础推理能力，通过 TTS 模块获得语音输出能力。

核心定位：

```text
辅星 = 仲裁机关中的一个默认对话参与方 + LLM 能力消费者
```

## 2. 拆分目标

当前助手线位于：

```text
com.rheinmetal.tianshu.function.llm.AX
```

该位置会导致 LLM 模块同时承担两类职责：

- 基础推理基础设施；
- 辅星业务逻辑。

拆分后目标是：

```text
module.llm
  只提供基础 LLM 能力

module.ax
  提供辅星业务能力

module.ia
  负责对话所有权仲裁
```

拆分完成后，LLM 模块不再理解助手的人设、记忆、上下文、动态事实和 prompt 编排。助手模块自行组织请求，然后调用 LLM 基础能力。

## 3. 总体架构

```text
ASR
  ↓
IR
  ↓
IA / 仲裁机关
  ↓ 选定 owner
辅星 AX / 辅星
  ↓ 需要推理时调用
LLM 基础模块
  ↓
Java-Llama-Server
  ↓
辅星处理输出
  ↓ 可选调用
TTS
```

模块关系：

```text
module.ia
  决定本轮对话由谁处理

module.ax
  在获得 owner 身份后处理辅星对话

module.llm
  提供通用推理、流式输出、task lane、RAG 字段透传

module.tts
  提供授权语音输出能力
```

## 4. 职责边界

### 4.1 辅星负责什么

辅星负责：

- 注册自己为仲裁机关的对话参与方；
- 接收仲裁机关定向投递的对话正文；
- 维护辅星的人设、语气、行为边界；
- 维护辅星专属的短期对话记忆；
- 维护辅星专属的会话摘要；
- 维护辅星专属的长期用户记忆候选；
- 维护辅星专属的长期记忆 RAG 文件；
- 采集或接收已经快照化的运行时事实；
- 将运行时事实整理为 dynamic RAG；
- 组装 LLM messages、generation options 和 RAG context；
- 调用 LLM 模块执行推理；
- 接收 LLM 流式输出；
- 处理助手输出、记忆候选和 RAG 命中反馈；
- 在授权场景下调用 TTS 播报结果；
- 主动释放、续租或结束仲裁会话。

### 4.2 辅星不负责什么

辅星不负责：

- 管理 LLM server 进程；
- 直接拼接 Java-Llama-Server 的 HTTP 请求；
- 直接访问 LLM server 队列；
- 仲裁其他模块的对话所有权；
- 替 NPC、机器、女仆或其他模组编写业务 prompt；
- 处理非自身 owner 的对话正文；
- 广播玩家输入正文；
- 广播 LLM prompt；
- 广播 LLM response；
- 广播内部记忆内容；
- 广播 RAG 检索命中；
- 直接读取 Minecraft 活对象；
- 绕过协议中心调用其他模块能力；
- 绕过仲裁机关抢占会话。

### 4.3 LLM 模块负责什么

LLM 模块拆分后只负责基础能力：

- 启动和停止 Java-Llama-Server；
- 健康检查；
- OpenAI-compatible HTTP/SSE 适配；
- LLM 请求对象标准化；
- chat lane / task lane 调用；
- 流式结果解析；
- 取消请求；
- 基础准入、队列和限流；
- RAG 请求字段透传；
- 将结果定向返回调用方。

### 4.4 仲裁机关负责什么

仲裁机关负责：

- 管理对话参与方；
- 接收 IR 之后的仲裁请求；
- 判断本轮 owner；
- 创建和维护 dialogue session；
- 向 owner 定向投递正文；
- 处理抢占、释放、续租和超时；
- 发布不含正文的状态事件；
- 拒绝非 owner 访问正文链路。

## 5. 信息最小暴露原则

辅星必须遵守信息最小暴露原则。

### 5.1 不对外暴露的内容

以下内容不得通过公共 topic、普通事件、日志或诊断默认暴露：

- 原始 ASR 文本；
- IR 修复后的完整文本；
- 玩家本轮输入正文；
- prompt 完整内容；
- system prompt；
- short-term raw turns；
- conversation summary；
- long-term memory；
- pending memory candidates；
- dynamic RAG 原始条目；
- RAG hit 明细；
- LLM 完整 response；
- TTS 待播文本；
- 玩家详细上下文快照；
- 物品 NBT；
- 实体完整状态；
- 世界状态完整快照。

### 5.2 允许对外暴露的内容

默认只允许暴露：

- AX 模块状态；
- 是否可用；
- 是否当前 owner；
- 当前 session id；
- 当前 turn id；
- 处理阶段；
- 成功、失败、取消、超时等状态码；
- 面向 UI 的短状态文案；
- 脱敏后的诊断摘要。

状态文案不得包含玩家输入正文、prompt、记忆或 RAG 命中。

### 5.3 内部流转规则

辅星内部可以传递必要数据，但只允许在需要它的组件之间传递：

```text
Dialogue input
  只进入 AXInputNormalizer / AXConversationService

Memory snapshot
  只进入 AXContextCollector / AXContextOrchestrator

Runtime facts
  只进入 RuntimeFactPool / DynamicRagCandidateBuilder

Prompt plan
  只进入 AXContextOrchestrator / LLM request construction

LLM output
  只进入 OutputProcessor / authorized stream sink / optional TTS
```

任何准备离开 AX 模块边界的数据，都必须先经过授权判断和脱敏处理。

## 6. 对外能力设计

辅星作为独立模块后，建议注册以下 capability。

```text
AX_PARTICIPANT_REGISTER
AX_DIALOGUE_DELIVERY
AX_SESSION_CONTROL
AX_STATUS
```

如果协议中心已有统一的 dialogue capability，助手模块不需要暴露过多专用 capability。推荐方式是：

```text
AX 向 IA 注册 participant
IA 通过 participant.routeCapability 定向投递正文
AX 通过 IA 的 session control 控制会话
```

也就是说，助手模块的对外能力应尽量收敛，不额外建立独立的公开聊天入口。

### 6.1 不推荐继续使用 LLM_CHAT 表达助手入口

`LLM_CHAT` 容易混淆基础推理和辅星业务。

拆分后建议：

```text
LLM_*       表示基础推理能力
AX_* 表示辅星业务能力
DIALOGUE_*  表示仲裁机关对话会话能力
```

## 7. IA 接入方式

辅星应作为默认 participant 注册到仲裁机关。

概念描述：

```text
participantId = tianshu.AX
moduleId = module.ax
displayName = 辅星
priority = 默认助手优先级
routeCapability = AX_DIALOGUE_DELIVERY
interruptPolicy = 可被明确高优先级参与方抢占
leasePolicy = 普通对话租约
```

仲裁流程：

```text
IR 输出 DialogueArbitrationRequest
  ↓
IA 收集参与方 claim
  ↓
辅星返回 claim 或由 IA 使用静态默认策略
  ↓
IA 选中辅星为 owner
  ↓
IA 定向投递正文到 AX_DIALOGUE_DELIVERY
  ↓
辅星处理输入并调用 LLM
  ↓
辅星输出完成后释放或续租 session
```

辅星只能处理 IA 定向投递给自己的正文。非 owner 状态下不得消费对话正文。

## 8. LLM 调用方式

辅星不得直接访问 `LlmEngine`。

允许依赖：

```text
LlmInvocationService
LlmTaskGatewayService
LlmInvocationRequest
LlmInvocationMessage
LlmGenerationOptions
LlmRagContext
LlmStreamSink
```

推荐调用链：

```text
AXConversationService
  ↓ 构造 LlmInvocationRequest
LlmInvocationService.submitStreaming
  ↓
LLM 基础模块
  ↓
Java-Llama-Server
```

助手模块负责构造：

- messages；
- generation options；
- dynamic RAG；
- memory RAG profile；
- static RAG scope；
- request key。

LLM 模块只负责执行。

## 9. 存储与 RAG 目录

辅星应拥有自己的存储布局，不再放在 LLM 模块私有语义下。

推荐逻辑根：

```text
<llmBase>/AX/
```

推荐记忆根：

```text
<llmRagRoot>/<world>/tianshu/agents/AX/memory_rag/
```

推荐静态知识根：

```text
<llmRagRoot>/<world>/tianshu/static_rag/
```

长期记忆文件：

```text
memories.jsonl
```

约束：

- AX 只维护自己的 `memories.jsonl`；
- Java-Llama-Server 维护 `.javallama-memory-index/`；
- AX 不修改 `.javallama-memory-index/`；
- AX 不写其他模组 agent 的 memory；
- 其他模块不写 AX 的 memory；
- 动态事实不写入长期记忆。

## 10. 内部子系统

辅星模块建议采用以下内部结构。

```text
AXModule
  ├─ AXProtocolAdapter
  ├─ AXParticipantRegistrar
  ├─ AXDialogueGateway
  ├─ AXConversationService
  ├─ AXInputNormalizer
  ├─ AXContextCollector
  ├─ AXContextOrchestrator
  ├─ AXPromptPlanner
  ├─ AXPromptRenderer
  ├─ AXMemorySystem
  ├─ AXRuntimeFactCollector
  ├─ AXRuntimeMaintenanceCoordinator
  ├─ AXOutputProcessor
  ├─ AXSessionController
  ├─ AXAccessController
  └─ AXDiagnosticsView
```

### 10.1 AXModule

模块生命周期入口。负责：

- 注册 capability；
- 获取 LLM 服务；
- 获取 IA 服务或注册 participant；
- 初始化存储、记忆、上下文和输出处理器；
- 在 stop/destroy 时取消未完成请求并释放会话。

### 10.2 AXProtocolAdapter

负责 AX 模块与协议中心交互。

只注册必要 capability，不广播正文。

### 10.3 AXParticipantRegistrar

负责向 IA 注册辅星 participant。

注册信息只包含能力描述、显示名、优先级和 route capability，不包含 prompt、记忆或内部策略细节。

### 10.4 AXDialogueGateway

负责接收 IA 定向投递的 dialogue input。

职责：

- 校验 session owner；
- 校验请求是否过期；
- 生成 AX request；
- 调用 AXConversationService；
- 将输出回传给授权链路；
- 处理取消、失败和释放。

### 10.5 AXAccessController

负责助手内部授权判断。

至少校验：

- 当前输入是否来自 IA；
- 当前 session owner 是否为 AX；
- 当前模块是否允许调用 LLM；
- 当前输出是否允许送入 TTS；
- 当前诊断是否允许包含脱敏摘要。

### 10.6 AXDiagnosticsView

只提供脱敏快照。

允许字段：

- 最近状态；
- 当前 session id；
- 当前阶段；
- 记忆条目数量；
- raw turn 数量；
- pending compression task 数量；
- runtime fact 数量；
- 最近错误码。

禁止字段：

- 玩家正文；
- prompt；
- response；
- 记忆正文；
- RAG 正文；
- 世界完整上下文。

## 11. 现有代码迁移范围

当前应从 LLM 包迁出的类：

```text
com.rheinmetal.tianshu.function.llm.AX.AXConversationService
com.rheinmetal.tianshu.function.llm.AX.AXInvocationPlan
com.rheinmetal.tianshu.function.llm.AX.AXRequest
com.rheinmetal.tianshu.function.llm.AX.context.*
com.rheinmetal.tianshu.function.llm.AX.fact.*
com.rheinmetal.tianshu.function.llm.AX.input.*
com.rheinmetal.tianshu.function.llm.AX.memory.*
com.rheinmetal.tianshu.function.llm.AX.output.*
com.rheinmetal.tianshu.function.llm.AX.prompt.*
com.rheinmetal.tianshu.function.llm.AX.rag.*
com.rheinmetal.tianshu.function.llm.AX.runtime.*
com.rheinmetal.tianshu.function.llm.AX.scope.*
com.rheinmetal.tianshu.function.llm.AX.storage.*
```

迁移后目标路径：

```text
com.rheinmetal.tianshu.function.auxilium
```

LLM 模块应保留：

```text
com.rheinmetal.tianshu.function.llm.engine.*
com.rheinmetal.tianshu.function.llm.inference.*
com.rheinmetal.tianshu.function.llm.gateway.*
com.rheinmetal.tianshu.function.llm.server.*
LlmModule
LlmInvocationService
LlmProtocolAdapter
LlmRequestService 可逐步降级或移除助手语义
LlmRuntimeCapabilities
LlmEngineProvider
```

## 12. 迁移阶段计划

本计划不采用最小可执行原则，而采用稳健架构优先原则。每一阶段必须保持边界清楚、可回滚、可验证。

### 阶段一：文档和命名冻结

目标：确定 AX 独立模块身份。

任务：

1. 确定英文名 `AX`；
2. 确定中文名 `辅星`；
3. 确定代号 `辅星`；
4. 确定模块 ID `module.ax`；
5. 确定包名 `com.rheinmetal.tianshu.function.auxilium`；
6. 确定 AX 不再作为 LLM 内部线存在；
7. 更新 LLM 相关文档，将助手线标记为待迁出或历史设计。

验收：

- 新文档存在；
- 命名一致；
- 边界与 IA 文档不冲突。

### 阶段二：建立 AXModule 骨架

目标：先建立健壮模块入口，不急于搬全部业务。

任务：

1. 新增 `AXModule`；
2. 新增 `AXProtocolAdapter`；
3. 新增 `AXParticipantRegistrar`；
4. 新增 `AXDialogueGateway`；
5. 新增 `AXAccessController`；
6. 新增 `AXDiagnosticsView`；
7. 在模块生命周期中接入服务注册和释放逻辑；
8. 不在该阶段暴露任何正文 topic。

验收：

- AX 模块可独立注册；
- 不依赖 LLM 内部助手初始化；
- 没有公共广播正文；
- stop/destroy 幂等。

### 阶段三：从 LLM 模块移除助手初始化

目标：让 LLM 模块纯化为基础推理模块。

任务：

1. 从 `LlmModule` 中移除 `AXConversationService` 初始化；
2. 从 `LlmModule` 中移除 AX memory 初始化；
3. 从 `LlmModule` 中移除 runtime fact 初始化；
4. `LlmModule` 只注册基础 LLM 服务；
5. 将原 `LLM_CHAT` 的助手语义迁移到 AX 模块或 IA 投递链路；
6. 保留 LLM task / invocation 能力。

验收：

- `LlmModule` 不引用 AX 包；
- LLM 服务仍可执行基础推理；
- AX 模块通过 LLM 服务调用推理；
- LLM 模块不再持有助手记忆对象。

### 阶段四：迁移 AX 包

目标：把助手业务代码移动到独立包。

任务：

1. 将 `llm.AX` 整包迁移到 `function.auxilium`；
2. 修正 imports；
3. 将 storage layout 改为 AX 模块语义；
4. 将 prompt resource repository 移入 AX 模块；
5. 将 memory system 改为 AX 独占；
6. 确认 LLM inference 类型仍从 `function.llm.inference` 引用。

验收：

- 不存在新的 AX 代码留在 `function.llm.AX`；
- AX 代码只依赖 LLM 的公开推理模型；
- AX 内部类不被外部模块直接引用。

### 阶段五：接入 IA 正式链路

目标：让辅星成为 IA participant。

任务：

1. AX 启动时注册 participant；
2. IA 选中 AX 后定向投递正文；
3. AXDialogueGateway 校验 owner 和 session；
4. AX 调用 LLM；
5. AX 根据处理结果续租或释放 session；
6. AX 可选调用 TTS；
7. 非 owner 状态下拒绝正文输入。

验收：

- 对话正文只从 IA 进入 AX；
- session owner 校验生效；
- 非 owner 无法调用助手正文处理入口；
- 状态事件不包含正文。

### 阶段六：适配多世界 RAG 基座

目标：与 Java-Llama-Server 新 RAG profile 设计对齐。

任务：

1. LLM 基础模块支持 `--rag-root-path`；
2. LLM `LlmRagContext` 支持 world/profile/staticScope/staticMods；
3. AX 使用固定 profile：

```text
world = 当前世界 ID
profile = tianshu/AX
static_scope = world 或 mod
```

4. AX 维护自己的 `memories.jsonl`；
5. AX 不直接维护 server index。

验收：

- AX 长期记忆进入自己的 profile；
- 其他模块不会混写 AX memory；
- LLM server 能通过 profile 定位 AX memory RAG。

### 阶段七：安全与诊断加固

目标：防止内部信息泄漏。

任务：

1. 检查所有日志，禁止输出正文、prompt、记忆和 RAG 内容；
2. 检查所有 topic，禁止广播正文；
3. 诊断视图只输出脱敏统计；
4. 错误响应只输出错误码和短消息；
5. 对 memory 写入、RAG 写入和 TTS 输出做授权校验；
6. 增加 session 超时和取消清理。

验收：

- 默认运行不泄露内部正文；
- 调试信息不含敏感内容；
- 异常情况下不会把 prompt 或 response 写入公共日志。

## 13. 推荐最终调用链

### 13.1 普通语音助手对话

```text
ASR final
  ↓
IR repair / enrich
  ↓
IA arbitration
  ↓
AX 被选为 owner
  ↓
AXDialogueGateway
  ↓
AXConversationService
  ↓
LlmInvocationService
  ↓
Java-Llama-Server
  ↓
AXOutputProcessor
  ↓
TTS / UI / session release
```

### 13.2 非助手模块接管对话

```text
ASR final
  ↓
IR
  ↓
IA arbitration
  ↓
其他 participant 被选为 owner
  ↓
其他模块自行处理
  ↓ 可选调用
LLM / TTS
```

AX 不应收到该轮正文。

### 13.3 助手后台记忆压缩

```text
AXRuntimeMaintenanceCoordinator
  ↓
AXCompressionTaskDispatcher
  ↓
LlmTaskGatewayService 或 LlmInvocationService task lane
  ↓
LLM task lane
  ↓
AXMemorySystem 接收结果
```

该流程不经过 IA，因为它不是新的玩家对话输入，而是 AX 内部维护任务。

## 14. 代码健壮性要求

### 14.1 生命周期幂等

以下操作必须幂等：

- AX module start；
- AX module stop；
- participant register；
- participant unregister；
- active request cancel；
- session release；
- compression task retry；
- memory consolidation。

### 14.2 失败收敛

所有失败必须收敛为明确状态：

```text
REJECTED
UNAUTHORIZED
EXPIRED
CANCELLED
LLM_UNAVAILABLE
LLM_FAILED
OUTPUT_BLOCKED
SESSION_RELEASED
```

不得把异常堆栈、prompt 或内部消息作为公共 payload 返回。

### 14.3 数据原子性

记忆文件写入必须使用临时文件和替换策略，避免半写入。

```text
write memories.jsonl.tmp
flush
atomic replace memories.jsonl
```

如果平台不支持原子替换，应退化为安全覆盖并保留错误日志，但日志不得包含记忆正文。

### 14.4 边界测试

后续实现必须覆盖：

- 非 owner 调用助手正文入口被拒绝；
- IA 投递过期请求被拒绝；
- LLM 不可用时 AX 正确失败并释放 session；
- 流式输出中途取消；
- TTS 不可用时不影响 session 释放；
- 记忆写入失败不影响当前回答完成；
- RAG profile 缺失时有清晰降级路径；
- stop 时 active generation 被取消。

## 15. 文档迁移状态

助手线文档已经从 LLM 目录迁出，当前 AX 相关设计集中在：

```text
doc/function/auxilium/AX_LLM使用设计.md
doc/function/auxilium/AX_记忆策略设计.md
```

LLM 文档只保留基础推理、server 适配、task lane、RAG 请求字段和跨模块接入说明。

## 16. 设计原则总结

1. 辅星是独立模块，不是 LLM 子系统。
2. 辅星是 IA 的默认对话参与方。
3. LLM 是基础推理能力，不是对话 owner。
4. IA 决定谁处理，AX 决定如何回答，LLM 负责推理执行。
5. 对话正文只通过 IA 授权后定向进入 AX。
6. prompt、记忆、RAG、LLM response 默认不对外暴露。
7. AX 内部信息只在需要它的组件之间流转。
8. 诊断只输出脱敏统计和状态，不输出正文。
9. 后台记忆维护属于 AX 内部任务，不经过 IA 对话仲裁。
10. 架构优先于临时跑通，拆分必须保证边界清晰、生命周期幂等、失败可收敛。
