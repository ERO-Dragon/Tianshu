# 仲裁机关开发技术说明

## 1. 模块定位

仲裁机关模块（Interaction Arbiter）是天枢语音对话链路中的开放交互仲裁机构，负责在 IR 完成文本修复、指令识别和上下文预处理之后，判断一次自然语言对话应由哪个参与方接管。

仲裁机关不是协议中心，也不是 LLM 模块。它位于 IR 之后、LLM/TTS 之前，是对话会话所有权、参与方选择、抢占与释放的业务仲裁层。

它的核心目标是：

- 允许天枢助手、NPC、女仆、机械装置、其他模组实体或物品交互模块竞争一次对话的处理权。
- 让外部模块通过适配天枢协议参与对话，而不是直接监听 ASR 或绕过 IR/LLM。
- 统一管理对话会话的 owner、生命周期、抢占策略和释放策略。
- 只广播会话状态，不广播 ASR 文本、IR 文本、LLM prompt 或 LLM response。
- 与协议中心保持职责分离，复用协议中心的路由、能力、线程调度和资源仲裁能力。

总体位置如下：

```text
ASR
  ↓ 受控输入
IR
  ↓ 修复文本 / 意图 / 上下文
仲裁机关
  ↓ 仲裁 owner / 建立会话 / 定向投递
对话参与方
  ↓ 可选调用
LLM / TTS / 游戏动作
```

## 2. 与协议中心的关系

协议中心是底层通信与调度基础设施，负责：

- capability 注册与发现
- envelope 路由
- topic 发布订阅
- broker 策略
- execution lane 调度
- 生命周期与资源边界

仲裁机关是协议中心之上的业务模块，负责：

- 对话参与方注册
- 对话请求仲裁
- 会话 owner 管理
- 抢占、续租、释放
- 会话状态事件发布
- 对话内容的授权定向投递

两者不是合并关系。

正确关系是：

```text
协议中心 = 通信、调度、资源、生命周期底座
仲裁机关   = 对话领域的仲裁业务模块
```

仲裁机关不应创建独立线程池，也不应绕过协议中心调度。仲裁机关自身应作为普通功能模块注册 capability，并通过协议中心提交任务、返回结果和发布状态事件。

## 3. 与 ASR / IR / LLM / TTS 的关系

### 3.1 与 ASR 的关系

ASR 只负责语音识别，不负责开放对话路由。

ASR 输出不应作为公共广播暴露给外部模块。ASR 识别结果应进入受控链路，由 IR 继续处理。

外部模块不得通过监听 ASR 文本来接管对话。

### 3.2 与 IR 的关系

IR 是仲裁机关的上游。

IR 负责：

- ASR 文本归一化
- MC 词汇修复
- 物品、实体、环境上下文增强
- voice trigger 匹配
- 普通指令触发
- 生成可供仲裁机关使用的对话候选输入

仲裁机关不替代 IR。仲裁机关只处理已经经过 IR 修复和结构化之后的对话仲裁请求。

推荐链路：

```text
ASR final text
  ↓
IR parse / repair / enrich
  ↓
DialogueArbitrationRequest
  ↓
仲裁机关仲裁
```

### 3.3 与 LLM 的关系

LLM 是可被 owner 使用的推理能力，不是对话 owner 本身。

仲裁机关不负责直接生成回答，也不应持有具体 prompt 策略。当前会话 owner 可以根据自己的业务逻辑决定：

- 调用助手对话线
- 调用 LLM 外部模块线
- 调用底层 LLM task
- 不调用 LLM，直接执行动作
- 调用 TTS 播报固定文本
- 完全自行处理

因此，仲裁机关只决定“谁有权处理这轮对话”，不决定“这轮对话应该怎么回答”。

### 3.4 与 TTS 的关系

TTS 是输出能力，不是仲裁方。

只有当前会话 owner 或被 owner 授权的模块，才应该能把本轮对话内容送入 TTS。普通模块不应通过监听公共事件获得文本后自行播报。

## 4. 模块边界

### 4.1 仲裁机关负责什么

仲裁机关负责：

- 注册对话仲裁 capability。
- 接收 IR 之后的对话仲裁请求。
- 维护可参与对话的 participant 注册表。
- 基于上下文、优先级、置信度、距离、准星目标、手持物、实体状态等信息选择 owner。
- 创建 dialogue session。
- 维护 session owner、turn id、lease、状态和释放原因。
- 处理 owner 续租、主动释放、超时释放。
- 处理更高优先级参与方的抢占申请。
- 向 owner 定向投递对话输入。
- 发布会话状态事件。
- 拒绝非 owner 对当前会话内容链路的访问。

### 4.2 仲裁机关不负责什么

仲裁机关不负责：

- 麦克风采集。
- ASR 模型推理。
- IR 文本修复和命令词匹配。
- 编写任何模块的业务 prompt。
- 管理 LLM server 内部队列。
- 替其他模块执行游戏逻辑。
- 替 TTS 决定语音风格。
- 广播对话正文。
- 直接读取 Minecraft 活对象。
- 创建自己的全局线程池。

## 5. 对外协议设计

### 5.1 capability

建议新增或保留以下 capability：

```text
ProtocolCapabilities.DIALOGUE_ARBITRATE
ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER
ProtocolCapabilities.DIALOGUE_SESSION_CONTROL
ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE
```

语义：

| capability | 语义 |
|---|---|
| `DIALOGUE_ARBITRATE` | IR 或受信模块请求仲裁机关对一次对话输入进行 owner 仲裁。 |
| `DIALOGUE_PARTICIPANT_REGISTER` | 模块注册自己为可参与对话的候选方。 |
| `DIALOGUE_SESSION_CONTROL` | 当前 owner 对会话进行续租、释放、打断确认等控制。 |
| `DIALOGUE_LLM_USAGE_AUTHORIZE` | LLM 模块查询某个 requester 是否有权基于当前 dialogue session 发起对话型 LLM 使用。仲裁机关只返回允许或拒绝结论，不代理、不转发、不排队任何 LLM 请求。 |

仲裁机关的对外协议不应追求“先能跑”的临时收敛，而应从一开始建立稳定边界：仲裁、参与方注册、会话控制、状态事件、权限校验都属于同一套体系。即使某些能力在代码实现中分阶段完成，协议模型也应按成熟形态设计，避免后续反复破坏 payload、capability 和 session 语义。

### 5.2 payload type

建议新增：

```text
PayloadType.DIALOGUE_ARBITRATION_REQUEST
PayloadType.DIALOGUE_ARBITRATION_RESULT
PayloadType.DIALOGUE_PARTICIPANT_REGISTER
PayloadType.DIALOGUE_SESSION_CONTROL
PayloadType.DIALOGUE_SESSION_EVENT
PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST
PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT
```

### 5.3 topic

仲裁机关可以有自己的 topic，但只用于状态观测。

建议新增：

```text
ProtocolTopics.DIALOGUE_SESSION_EVENTS
```

该 topic 只发布会话状态事件，不发布对话正文。

允许发布的事件包括：

- `conversation.claimed`
- `conversation.released`
- `conversation.interrupted`
- `conversation.owner_changed`
- `conversation.session_started`
- `conversation.session_finished`
- `conversation.rejected`
- `conversation.expired`

## 6. 广播策略

仲裁机关需要状态广播，但不需要内容广播。

### 6.1 可以广播的内容

可以广播：

- session id
- owner module id
- participant id
- 状态类型
- 状态时间
- 释放原因
- 抢占原因
- 是否成功接管
- 面向 UI 的非敏感显示名

这些信息用于 UI、字幕栏、调试面板、动作系统或其他旁路模块了解“当前谁接管了对话”。

### 6.2 不可以广播的内容

不得广播：

- 原始 ASR 文本
- IR 修复后的完整文本
- 玩家输入正文
- LLM prompt
- LLM response
- TTS 待播文本
- 玩家详细上下文快照
- 物品详细 NBT
- 实体完整状态
- 记忆内容
- RAG 检索结果

这些内容只能在当前 owner 和被授权链路内定向流转。

### 6.3 内部消息最小暴露原则

仲裁机关负责授权，因此不需要再额外设计复杂的消息分级体系。

这里的原则很简单：模块内部可以按职责传递必要数据，但不要把无关的内部消息、完整上下文或中间过程暴露给不需要它们的组件或外部模块。

要求：

- 仲裁逻辑可以读取完成 owner 判断所需的信息。
- 参与方注册表只保存参与方能力描述，不保存玩家输入正文。
- 会话存储只保存 session 状态、owner、turn、lease、释放原因等必要字段，不长期保存完整对话正文。
- 状态广播只发布 session 状态，不附带正文、prompt、response、完整上下文。
- owner 定向投递只包含处理当前 turn 所需的输入和上下文。
- 非 owner 只能获得会话状态，不能获得本轮正文。
- 调试信息默认不包含正文；如开发模式需要查看正文，应显式开启。
- 日志只记录 request id、session id、owner、状态和原因码，不记录玩家正文。

换句话说，仲裁机关本身就是授权边界。只要消息准备离开仲裁机关内部、发给参与方、UI、日志、诊断或公共 topic，就必须先经过仲裁机关的授权判断。

## 7. 请求契约

概念模型：

```java
DialogueArbitrationRequestPayload(
    requestId,
    sourceModuleId,
    playerId,
    turnId,
    repairedText,
    normalizedText,
    matchedHotwords,
    matchedItemIds,
    matchedEntityRefs,
    interactionHints,
    contextSnapshot,
    timestampMillis,
    expireAtMillis
)
```

字段语义：

| 字段 | 含义 |
|---|---|
| `requestId` | 本次仲裁请求 ID。 |
| `sourceModuleId` | 请求来源，通常是 IR。 |
| `playerId` | 玩家标识。 |
| `turnId` | 上游 turn 编号。 |
| `repairedText` | IR 修复后的文本，仅允许进入仲裁机关和候选 owner 判断，不做公共广播。 |
| `normalizedText` | 归一化文本。 |
| `matchedHotwords` | IR 命中的热词。 |
| `matchedItemIds` | IR 增强识别出的物品。 |
| `matchedEntityRefs` | 快照化实体引用，不传活对象。 |
| `interactionHints` | 手持物、准星、按键、距离等交互提示。 |
| `contextSnapshot` | 已脱敏、快照化的上下文。 |
| `timestampMillis` | 请求创建时间。 |
| `expireAtMillis` | 请求过期时间。 |

实际代码中，payload 不应直接携带 Minecraft 活对象，只能携带 common 可理解的 ID、快照和引用描述。

## 8. 仲裁结果契约

概念模型：

```java
DialogueArbitrationResultPayload(
    requestId,
    sessionId,
    accepted,
    ownerModuleId,
    ownerParticipantId,
    routeCapability,
    routeTopic,
    reason,
    leaseExpireAtMillis
)
```

字段语义：

| 字段 | 含义 |
|---|---|
| `requestId` | 对应的仲裁请求 ID。 |
| `sessionId` | 仲裁机关创建或复用的会话 ID。 |
| `accepted` | 是否成功找到 owner。 |
| `ownerModuleId` | 接管模块 ID。 |
| `ownerParticipantId` | 接管参与方 ID。 |
| `routeCapability` | 定向投递给 owner 的 capability。 |
| `routeTopic` | 如需要流式或状态订阅，可给出受控 topic。 |
| `reason` | 接管、拒绝或抢占原因。 |
| `leaseExpireAtMillis` | 当前 owner 租约过期时间。 |

## 9. 参与方模型

对话参与方是一个可被仲裁机关选择的候选处理者。

概念模型：

```java
DialogueParticipantDescriptor(
    participantId,
    moduleId,
    displayName,
    priority,
    supportedIntents,
    supportedEntityTypes,
    supportedItemIds,
    routeCapability,
    interruptPolicy,
    leasePolicy
)
```

参与方声明自己能处理什么，但具体如何判断是否要接管，可以由参与方自行提供策略。

推荐拆成两层：

```text
静态描述：模块启动时注册，说明能力范围
动态评分：每次仲裁时基于上下文返回 claim 分数
```

### 9.1 静态描述

静态描述适合表达：

- 模块 ID
- 参与方 ID
- 显示名称
- 支持实体类型
- 支持物品 ID
- 支持交互类型
- 基础优先级
- route capability

### 9.2 动态评分

动态评分适合表达：

- 玩家是否正看着该实体
- 玩家是否拿着指定物品
- 当前实体是否允许说话
- 当前机器是否正在运行
- 当前模块是否忙碌
- 当前上下文是否像是在对自己说话

概念模型：

```java
DialogueClaim(
    participantId,
    score,
    confidence,
    priority,
    exclusive,
    reason
)
```

仲裁机关根据所有 claim 结果选择 owner。

## 10. 会话所有权

仲裁机关应维护对话 session。

session 至少包含：

- `sessionId`
- `playerId`
- `ownerModuleId`
- `ownerParticipantId`
- `state`
- `turnId`
- `createdAtMillis`
- `lastActiveAtMillis`
- `leaseExpireAtMillis`
- `releaseReason`

推荐状态：

```text
PENDING
CLAIMED
ACTIVE
INTERRUPTING
RELEASED
EXPIRED
REJECTED
```

### 10.1 owner 权限

当前 owner 拥有：

- 接收本 session 后续 turn 的权限。
- 调用 LLM 生成本 session 回答的权限。
- 调用 TTS 输出本 session 结果的权限。
- 主动释放 session 的权限。
- 请求续租的权限。

非 owner 不应获得本 session 的对话正文。

### 10.2 释放策略

session 可以因为以下原因释放：

- owner 主动完成。
- 玩家取消输入。
- 输入超时。
- owner 处理失败。
- 更高优先级参与方抢占。
- 玩家切换目标。
- 世界或玩家生命周期结束。

## 11. 抢占策略

仲裁机关支持抢占，但抢占必须是显式策略，不应成为默认副作用。

建议维度：

| 维度 | 说明 |
|---|---|
| `priority` | 参与方基础优先级。 |
| `score` | 当前上下文评分。 |
| `exclusive` | 是否声明独占。 |
| `interruptPolicy` | 是否允许打断当前 owner。 |
| `leasePolicy` | 当前 owner 是否仍在租约内。 |
| `playerAction` | 玩家是否做了明确切换动作。 |

抢占发生时，仲裁机关应：

1. 向旧 owner 发送中断通知。
2. 更新 session owner 或创建新 session。
3. 发布 `conversation.interrupted` 和 `conversation.owner_changed` 状态事件。
4. 将后续输入定向投递给新 owner。

## 12. 线程与调度策略

仲裁机关不创建自己的线程池。

所有跨模块请求、评分、仲裁和状态事件都应通过协议中心的执行模型完成。

建议策略：

| 操作 | 推荐执行 lane | 推荐 broker 语义 |
|---|---|---|
| participant 注册 | `CPU` | 短任务、串行保护注册表。 |
| 单次仲裁 | `CPU` | 有界队列，避免语音输入堆积。 |
| owner 定向投递 | `CPU` / `IO` | 取决于 owner capability。 |
| LLM 调用 | `IO` | 由 LLM 模块自身管理。 |
| TTS 调用 | `AUDIO_IO` / `IO` | 由 TTS 模块自身管理。 |
| UI 状态展示 | `MAIN` | 只投递状态，不做重逻辑。 |

仲裁机关只表达任务意图和边界，不直接控制底层线程。

## 13. common 与 Minecraft 解耦

仲裁机关主体应位于 common 层，不依赖 NeoForge 或 Minecraft 客户端类。

Minecraft 侧信息应通过 provider 和 snapshot 注入：

```text
NeoForge client
  ↓ 采集准星、手持物、实体、世界状态
ContextSnapshotProvider
  ↓ 转换为 common DTO
仲裁机关 common 主体
```

common DTO 可以表达：

- 玩家 ID
- 维度 ID
- 实体引用 ID
- 实体类型 ID
- 物品 ID
- 距离
- 是否准星命中
- 是否按住交互键
- 是否潜行
- 简化状态标签

不得把 `Player`、`Level`、`Entity`、`ItemStack` 等活对象直接放进仲裁机关核心模型。

## 14. 外部模组接入方式

外部模组不需要被天枢规定具体声明方式。

它们可以自行决定如何表达“我想接管对话”，例如：

- 玩家准星看向某个实体。
- 玩家手持某个物品。
- 玩家长按某个按键。
- 玩家进入某个机器界面。
- 玩家靠近某个方块实体。
- 玩家说出某类热词。

但最终接入天枢时，应统一转化为 participant 注册和 claim 评分。

接入原则：

```text
外部模组自定义交互方式
  ↓
适配为 DialogueParticipant
  ↓
向仲裁机关声明能力或返回 claim
  ↓
由仲裁机关决定 owner
```

外部模组不应直接监听 ASR，不应直接绕过仲裁机关抢占会话。

## 15. 安全边界

仲裁机关是对话链路的安全边界之一。

必须保证：

- ASR 不公共广播。
- IR 默认不公共广播对话正文。
- LLM prompt 不公共广播。
- LLM response 不公共广播。
- TTS 文本不公共广播。
- 只有 owner 可以接收本 session 的正文。
- 非 owner 只能接收状态事件。
- 状态事件不携带敏感正文和完整上下文。
- 外部模块请求 LLM 必须走 LLM 模块的外部模块线或授权 capability。
- 对话型 LLM 请求必须携带 dialogue session 授权上下文，由 LLM 模块在入口向仲裁机关查询 `DIALOGUE_LLM_USAGE_AUTHORIZE` 或等价服务；只有当前 session owner、session 处于可用状态且 lease 未过期时才能放行。
- 推荐采用直接 session owner 校验，而不是随机 key 或 token 授权。LLM 模块提交对话型请求时携带 `sessionId`、`requesterModuleId`、`requesterParticipantId` 和 `turnId`，仲裁机关根据 Session Store 与 Access Control 返回允许或拒绝。该校验是普通内存状态查询，性能开销低，且不会引入 token 泄露、缓存失效和撤销复杂度。
- 仲裁机关只校验对话型 LLM 使用权限，不代理 LLM 请求，不生成 prompt，不管理 LLM server 内部队列。
- 外部模块请求 TTS 必须走 TTS 模块的授权 capability。
- 模块内部不得把完整内部消息直接作为公共 payload、日志或诊断输出。
- 内部消息只在需要它的组件之间流转，不为了调试或方便而全局扩散。

## 16. 成熟架构要求

仲裁机关应按完整对话仲裁系统设计，而不是按临时网关或最小闭环设计。

成熟形态至少包含以下子系统：

| 子系统 | 职责 |
|---|---|
| Participant Registry | 管理参与方注册、注销、能力描述、可见性和生命周期绑定。 |
| Claim Engine | 收集候选参与方声明，计算 score、priority、confidence 和 exclusive 语义。 |
| Arbitration Policy | 根据租约、抢占策略、玩家动作、模块优先级和上下文决定 owner。 |
| Session Store | 维护 session 状态、owner、turn、lease、释放原因和审计信息。 |
| Access Control | 校验模块是否有权读取正文、控制 session、调用 LLM/TTS 或接收事件。 |
| Message Gateway | 对入站和出站正文消息做授权检查和定向投递，拒绝旁路访问。 |
| Event Publisher | 发布不含正文的会话状态事件。 |
| Lifecycle Sweeper | 处理超时、owner 失效、玩家离线、世界切换和模块卸载。 |
| Diagnostics Snapshot | 提供仅调试可见的脱敏快照，不暴露正文和完整上下文。 |

推荐内部结构：

```text
DialogueArbiterModule
  ├─ DialogueArbiterProtocolAdapter
  ├─ DialogueParticipantRegistry
  ├─ DialogueClaimEngine
  ├─ DialogueArbitrationPolicy
  ├─ DialogueSessionStore
  ├─ DialogueAccessController
  ├─ DialogueMessageGateway
  ├─ DialogueEventPublisher
  ├─ DialogueLifecycleSweeper
  └─ DialogueDiagnosticsView
```

这些组件可以随工程阶段逐步实现，但代码边界应从一开始按该结构切分，避免把仲裁、权限、事件发布和会话状态混在一个大类里。

成熟实现必须满足：

1. 所有正文消息只能通过 Message Gateway 出入。
2. 所有 owner 判断只能通过 Arbitration Policy 产生。
3. 所有 session 状态只能由 Session Store 维护。
4. 所有对外状态事件都不能携带正文。
5. 所有权限判断必须经过 Access Control。
6. 所有 Minecraft 侧状态必须先快照化再进入 common。
7. 所有跨模块调用必须经协议中心 capability 或受控 topic。
8. 所有超时和生命周期清理必须可重复执行且幂等。

## 17. 推荐执行顺序

```text
IR 输出 DialogueArbitrationRequest
  ↓
仲裁机关检查请求有效性和过期时间
  ↓
读取 participant 注册表快照
  ↓
向候选参与方收集 claim 或使用本地策略评分
  ↓
选择最高优先级 owner
  ↓
创建或复用 session
  ↓
发布 conversation.claimed / session_started
  ↓
将对话输入定向投递给 owner
  ↓
owner 自行调用 LLM / TTS / 游戏动作
  ↓
owner 释放或 session 超时
  ↓
发布 conversation.released / session_finished
```

## 18. 设计原则总结

仲裁机关的原则是：

1. **只仲裁对话所有权，不处理具体回答。**
2. **只广播状态，不广播正文。**
3. **位于 IR 之后，继承 IR 的文本修复和上下文增强结果。**
4. **通过协议中心运行，不另起调度体系。**
5. **common 主体不依赖 Minecraft 活对象。**
6. **外部模组自定义交互方式，但统一适配为 participant / claim。**
7. **LLM 和 TTS 是 owner 可调用的能力，不是仲裁机关内部硬编码步骤。**
8. **默认拒绝旁路访问，所有正文链路必须定向授权。**
9. **内部消息不做无关扩散，只在需要它的组件之间流转。**
10. **对外状态事件只表达状态变化，不携带正文和完整上下文。**
