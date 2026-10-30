# 仲裁机关开发技术说明

### 快速摘要

- 参与方

  - AX、NPC、其他模组角色、物品或交互功能都可以注册为对话参与方。
  - 每个参与方声明自己希望在什么条件下接手输入，例如唤醒词、手持物品、装备、准星目标或交互状态。

- 预中标人（不说话时也在维护）

  - IA 持续消费映迹的交互上下文摘要（准星目标、主手物品、装备、潜行、交互键、屏幕类型），随时算出“现在开口会归谁”。
  - 预中标人只由上下文条件决定，没有租期、没有倒计时；指针一移就变。

- 每轮仲裁

  - IA 接收 IR 的结构化结果，与开口时冻结的摘要一起判断哪些参与方满足条件。
  - 有硬 claim 时按“上下文条件高于醒词”、参与方优先级和稳定顺序选择 owner；指着某个会 claim 的实体说话，一定归它。
  - 没有硬 claim 时，上一轮中标人的租期未过期就继续归它，租期从这一轮重新计时；否则回到默认参与方。

- 会话与投递

  - IA 为每轮输入建立独立会话，只把正文和必要情境投递给最终选中的 owner。
  - owner 默认获得 10 秒处理时间，可以按需要续期，但整个会话最晚在创建后 180 秒结束。
  - 新一轮仲裁会结束上一轮仍在活动的会话，完成、拒绝、失败、中断和超时都会形成明确终态。

- 对外状态

  - IA 写入映迹的伙伴广播 `PRESENCE.PARTNER`（预中标人或中标人，中标时带租期截止时间），以及发布会话开始、结束和 owner 变化等状态。
  - 这些公共状态只表达仲裁结果，不携带玩家正文；正文只进入最终 owner 的定向投递。

## 1. 模块定位

仲裁机关模块（Interaction Arbiter）是天枢语音对话链路中的开放交互仲裁机构，负责在 IR 完成文本修复和文本特征提取之后，结合 IA 自己捕获的轻量上下文，判断一次自然语言对话应由哪个参与方接管。

仲裁机关不是协议中心，也不是 LLM 模块。它位于 IR 之后、LLM/TTS 之前，是对话轮次所有权、参与方选择、释放和当前轮处理期限的业务仲裁层。

它的核心目标是：

- 允许天枢助手、NPC、女仆、机械装置、其他模组实体或物品交互模块竞争一次对话的处理权。
- 让外部模块通过适配天枢协议参与对话，而不是直接监听 ASR 或绕过 IR/LLM。
- 统一管理预中标人、每轮对话 owner 与租期、生命周期、释放策略和当前轮处理期限。
- 只广播会话状态，不广播 ASR 文本、IR 文本、LLM prompt 或 LLM response。
- 与协议中心保持职责分离，复用协议中心的路由、能力、线程调度和资源仲裁能力。

总体位置如下：

```text
ASR
  ↓ 受控输入
IR
  ↓ 修复文本 / 文本特征
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
- 每轮仲裁、处理期限延长、释放
- 会话状态事件发布
- 对话内容的定向投递

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
- 文本侧物品识别
- wake word 匹配
- 文本侧仲裁特征提取
- 生成可供仲裁机关使用的对话候选输入

仲裁机关不替代 IR。标准语音链路中，仲裁机关订阅 IR 发布的结构化分析结果；诊断、测试和受信模块仍可通过显式仲裁 capability 提交请求。

推荐链路：

```text
ASR final text
  ↓
IR parse / repair / enrich
  ↓
IntakeTopics.IR_RESULT / IrResultPayload
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
- 订阅 IR 发布的结构化分析结果，并将其作为标准语音仲裁输入。
- 为诊断、测试和受信模块保留显式仲裁 capability。
- 维护可参与对话的 participant 注册表。
- 先基于 participant 声明的 claim 条件收集硬 claim；有硬 claim 时按 claim 来源档（上下文高于醒词）、participant priority 和稳定 participant ID 选择 owner。没有硬 claim 时，上一轮中标人的租期未过期就继续归它，否则回到默认 participant。不做动态评分，也没有 fallback score。
- 创建 dialogue session。
- 维护 session owner、turn id、processing deadline、状态和释放原因。
- 处理 owner 处理期限延长、主动释放、超时释放。
- 每轮重新仲裁；上一轮中标人只通过未过期的租期承接没有硬 claim 的追问，租期到点即失效，不跨轮叠加。
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
"DIALOGUE.ARBITRATE"
"DIALOGUE.PARTICIPANT_REGISTER"
"DIALOGUE.SESSION_CONTROL"
```

语义：

| capability | 语义 |
|---|---|
| `DIALOGUE_ARBITRATE` | 受信模块显式请求仲裁机关对一次对话输入进行 owner 仲裁；标准 IR 链路不使用该 capability。 |
| `DIALOGUE_PARTICIPANT_REGISTER` | 模块注册自己为可参与对话的候选方。 |
| `DIALOGUE_SESSION_CONTROL` | 当前 owner 对会话进行处理期限延长、释放、打断确认等控制。 |

标准语音链路由 IA 订阅 `IntakeTopics.IR_RESULT` 接收 `IrResultPayload`。IR 发布后即完成职责；IA 负责创建/更新 session、选择 owner、定向 delivery，并发布不含正文的 session 事件。

`DIALOGUE_ARBITRATE` 同时接受 `COMMAND` 和 `REQUEST`，但只作为受信服务端口：

- `COMMAND` 用于无需同步结论的显式仲裁调用。
- `REQUEST` 用于确实需要同步获知仲裁结论的诊断、测试或受信模块查询场景。调用方必须为原请求 `envelopeId` 注册 `DIALOGUE_ARBITRATION_RESULT` 响应处理器，并在最终响应、过期、取消或模块停止时清理。

仲裁机关的对外协议不应追求“先能跑”的临时收敛，而应从一开始建立稳定边界：仲裁、参与方注册、会话控制、状态事件、归属校验都属于同一套体系。即使某些能力在代码实现中分阶段完成，协议模型也应按成熟形态设计，避免后续反复破坏 payload、capability 和 session 语义。

### 5.2 payload type

建议新增：

```text
PayloadType.of("DIALOGUE_ARBITRATION_REQUEST")
PayloadType.of("DIALOGUE_ARBITRATION_RESULT")
PayloadType.of("DIALOGUE_PARTICIPANT_REGISTER")
PayloadType.of("DIALOGUE_SESSION_CONTROL")
PayloadType.of("DIALOGUE_SESSION_EVENT")
```

### 5.3 topic

仲裁机关可以有自己的 topic，但只用于状态观测。

建议新增：

```text
IaTopics.DIALOGUE_SESSION_EVENTS
```

`DIALOGUE_SESSION_EVENTS` 只发布会话状态事件，不发布对话正文。伙伴标记不走 IA 的 topic：那条广播是映迹自己的 `PRESENCE.PARTNER`，IA 只是它的写入方（见 §5.4），载荷也由映迹定义。

允许发布的事件包括：

- `conversation.claimed`
- `conversation.released`
- `conversation.interrupted`
- `conversation.owner_changed`
- `conversation.session_started`
- `conversation.session_finished`
- `conversation.rejected`
- `conversation.expired`

写 `PRESENCE.PARTNER` 时只在显示状态变化时写：预中标人变化、中标（含续租）、租期到期回默认。没有固定刷新周期，没有事件就没有流量。

载荷 `PresencePartnerPayload` 的字段：`playerId` / `moduleId` / `participantId` / `icon` / `defaultOwner` / `holdUntilMillis` / `holdDurationMillis` / `updatedAtMillis`。`holdUntilMillis > 0` 表示中标租期（`holdDurationMillis` 是那一档的时长），`0` 表示预中标或默认承接者；topic 由映迹自注册（`LATEST_ONLY`），听澜是写入方。

IA 订阅映迹的 `PRESENCE.INTERACTION_CONTEXT`（交互上下文摘要）：摘要一变就重算预中标人，变了才写伙伴广播。仲裁用的就是同一份摘要——开口那一刻把它按 sessionId 冻结，同一帧也随 delivery 交给承接者，所以画面上的预中标人与实际拿到这一轮的人不会错开，仲裁也不再有上下文等待。

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
- owner 变化原因
- 是否成功接管
- 面向诊断的非敏感显示名

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
- 会话存储只保存 session 状态、owner、turn、processing deadline、释放原因等必要字段，不长期保存完整对话正文。
- 状态广播只发布 session 状态，不附带正文、prompt、response、完整上下文。
- owner 定向投递只包含处理当前 turn 所需的输入和上下文。
- 非 owner 只能获得会话状态，不能获得本轮正文。
- IA 结构化诊断默认关闭；Debug 构建中由设置页右下角的全局 Debug 开关统一控制，关闭时不记录玩家正文。
- 用户开启全局 Debug 后，`repairedText` / `normalizedText` 可以作为 `RAW_CONTENT` 进入宿主集中管理的异步诊断落盘链路。普通日志和公共 session event 始终不携带正文；诊断文件为独立的 `logs/tianshu-diagnostics.log`。

换句话说，仲裁机关本身就是归属边界。只要消息准备离开仲裁机关内部、发给参与方、UI、日志、诊断或公共 topic，就必须先经过仲裁机关的归属判断。

## 7. 输入契约

标准 IR topic 输入：

```java
IrResultPayload(
    repairedText,
    normalizedText,
    rawText,
    objectMatches,
    moduleMatches,
    turnId,
    sessionId,
    timestampMillis
)
```

`objectMatches` 是 `List<IrObjectMatch>`（`spokenWord` / `registryId` / `registryKind` / `languageKey`），`moduleMatches` 是 `List<IrModuleMatch>`（`moduleId` / `spokenWord` / `registeredWord`）。IA 的 `WAKE_WORD` claim 比较 `moduleMatches.spokenWord`；`MENTIONED_ENTITY` claim 只取 `objectMatches` 里 `entity()` 为真的 `registryId`，不按 id 形状猜类别。IR 的匹配排序不替代 IA 的 claim 来源档或 participant priority。

受信 `DIALOGUE_ARBITRATE` capability 输入：

```java
DialogueArbitrationRequestPayload(
    requestId,
    sourceModuleId,
    playerId,
    turnId,
    sourceSessionId,
    repairedText,
    normalizedText,
    objectMatches,
    moduleMatches,
    timestampMillis,
    expireAtMillis
)
```

字段语义：

| 字段 | 含义 |
|---|---|
| `requestId` | 本次仲裁请求 ID。 |
| `sourceModuleId` | 显式请求来源模块。 |
| `playerId` | 玩家标识。 |
| `turnId` | 上游 turn 编号。 |
| `sourceSessionId` | 上游 ASR/输入会话 ID，用于关联“开始说话时冻结的上下文摘要”。 |
| `repairedText` | IR 修复后的自然语言文本，例如同音错词修正；不携带物品资源 ID，仅允许进入仲裁机关和候选 owner 判断，不做公共广播。 |
| `normalizedText` | 归一化文本。 |
| `objectMatches` | 命中具名对象的证据，包含玩家说出的词与解析到的 registry id。 |
| `moduleMatches` | 命中模块触发词的证据，包含说出的词与该模块注册的词。 |
| `timestampMillis` | 请求创建时间。 |
| `expireAtMillis` | 请求过期时间。 |

IR topic 和显式仲裁请求都只承载文本侧结果：修复文本、归一化文本、具名对象命中与模块触发词命中。手持物、身上装备、准星实体、按键状态等轻量交互上下文由映迹通过 `PRESENCE.INTERACTION_CONTEXT` 推送摘要，IA 订阅后组合成内部 `DialogueArbitrationInput` 供硬 claim 判断和 owner delivery 使用。`DialogueArbitrationInput.displayNames` 只来自完整快照（维度、手持、实体等世界侧名字），与请求里的对象命中不合并。

IA 会订阅 `INPUT.ASR_SPEECH_ACTIVITY / ASR_SPEECH_ACTIVITY`。当 ASR 发布 `speaking=true` 时，IA 把最近一次收到的交互上下文摘要按 sessionId 冻结；当后续 `IrResultPayload` 带有相同 sessionId 时，IA 优先消费这份冻结摘要。`speaking=false` 不会立刻删除它，因为最终文本通常在用户说完后才进入 IR；摘要会短时间保留，直到被仲裁消费或过期清理。这样 claim 判断使用的是“用户开始说话时”的手持、装备、准星和按键状态，而不是 ASR 识别完成后的状态。

实际代码中，payload 不应直接携带 Minecraft 活对象。Presence 只能把世界状态转换成 common 可理解的 ID、摘要和引用描述；摘要缺失时按空 `DialogueContextFrame` 处理，不能阻断仲裁。

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
    reason,
    processingDeadlineMillis
)
```

字段语义：

| 字段 | 含义 |
|---|---|
| `requestId` | 对应的仲裁请求 ID。 |
| `sessionId` | 仲裁机关为当前 IR 最终输入创建的会话 ID。每一轮使用新 session。 |
| `accepted` | 是否成功找到 owner。 |
| `ownerModuleId` | 接管模块 ID。 |
| `ownerParticipantId` | 接管参与方 ID。 |
| `routeCapability` | 定向投递给 owner 的 capability。能力名由参与方自定义，但必须接收标准 `DIALOGUE_DELIVERY / DialogueDeliveryPayload / COMMAND`。 |
| `reason` | 接管、拒绝或 owner 变化原因。 |
| `processingDeadlineMillis` | 当前轮处理期限。默认初始窗口为 10 秒，owner 可续期，但绝对不能超过 session 创建时间后 180 秒。该期限只保护本轮异步处理，不影响下一轮 owner 归属。 |

## 9. 参与方模型

对话参与方是一个可被仲裁机关选择的候选处理者。

概念模型：

```java
DialogueParticipantDescriptor(
    participantId,
    moduleId,
    displayName,
    priority,
    claimProfile,
    triggerGroup,
    routeCapability,
    turnProcessingPolicy,
    icon,
    holdTier
)
```

参与方注册时，仲裁机关会校验 `routeCapability` 对应的能力是否已经注册，且是否满足统一对话投递契约。IA 只投递 `DialogueDeliveryPayload`，不识别也不适配各模组内部 payload；外部模组需要在自己的 adapter 内把 `DialogueDeliveryPayload` 转换成内部业务模型。

参与方声明自己在什么条件下应该接管，以及中标后自己的租期档位（`20s` / `10s` / `5s` / `0s`）。IA 只用 IR 文本结果和冻结的上下文摘要做命中判断，不做动态评分，也不向参与方索取数值。

参与方模型由两层组成：

```text
静态描述：模块启动时注册，说明身份、优先级、投递入口和处理期限
claim profile：模块启动时注册，说明 wake word、手持物、装备、准星实体、交互状态等硬命中条件
voice trigger group：模块启动或配置变化时注册，说明该 moduleId 的 wakeWords / extraWords 共享热词组
```

### 9.1 静态描述

静态描述适合表达：

- 模块 ID
- 参与方 ID
- 显示名称
- 兼容字段中的 wake word、实体类型、物品 ID
- `triggerGroup` 中的 wakeWords / extraWords。wakeWords 可与 claim profile 的 `WAKE_WORD` 条件保持一致；extraWords 只用于 ASR 热词和 IR 辅助修复，不进入 IA 仲裁。
- 基础优先级
- route capability
- 当前轮处理期限策略
- 伙伴标记贴图 `icon`：`.RULES` 参与者必须提供；**有图标就画**，所以第三方若声明 `DEFAULT_OWNER` 并可能被选成默认承接者，也必须提供（辅星不提供，由主图标表达）
- `factoryDefaultOwner`：声明"没配置过时默认是我"；只有 `DEFAULT_OWNER` 参与者可以声明，辅星是当前的出厂默认
- 中标租期档位 `holdTier`：`20s` / `10s` / `5s` / `0s`，`0s` 表示不持有

### 9.2 Claim Profile

claim profile 适合表达：

- 玩家说出了某个 wake word。
- 玩家是否正看着该实体
- 玩家是否拿着指定物品
- 玩家身上是否装备了指定物品
- 玩家是否命中了准星目标
- 玩家附近最近的白名单实体是否在指定半径内
- 玩家是否按住交互键或处于潜行状态
- 平台上下文是否带有某个简化 fact

概念模型：

```java
DialogueClaimRule(
    ruleId,
    operator,
    conditions
)
```

其中：

| 字段 | 含义 |
|---|---|
| `operator` | `ANY` 或 `ALL`，由外部模组决定多个条件的组合方式。 |
| `conditions` | `WAKE_WORD`、`HELD_ITEM`、`EQUIPPED_ITEM`、`MENTIONED_ENTITY`、`CROSSHAIR_ENTITY`、`INTERACTION_KEY`、`SNEAKING`。 |

claim 的来源档由**实际命中的条件**决定，不由规则声明：`WAKE_WORD`、`MENTIONED_ENTITY` 属于醒词档（说的是什么）；准星、主手、装备、交互键、潜行属于上下文档（现在的事实）。命中的条件里只要有一个上下文条件，这条 claim 就按上下文档排序；上下文档高于醒词档——被指着的人有预约，醒词没有。

claim profile 是 participant 对“什么情况下希望承接输入”的主观声明，`ANY` 相当于条件 OR，`ALL` 相当于条件 AND。IR 和 Presence 提供命中词、物品、实体、交互状态等客观输入，IA 只负责用这些客观输入计算 participant 声明的谓词，不替模块推断业务意图。

IA 会把 participant 中的 `WAKE_WORD` claim 条件与 `triggerGroup.wakeWords` 汇总为共享 wakeWords，并把 `triggerGroup.extraWords` 作为共享 extraWords 同步到语音资源层。同步结果按 `moduleId` 覆盖旧组，因此当前约定一个模块只维护一组 wake/extra 热词。`extraWords` 可以提升 ASR/IR 对专有名词的识别和修复，但不会让该模块在 IA 中产生 claim。

原本还有四条条件：`NEAREST_ENTITY_WITHIN`（附近白名单实体）、`INTERACTION_TAG`（交互 tag）、`CROSSHAIR_HIT`（准星命中任意实体）、`CONTEXT_FACT`（平台 fact）。前两条映迹没有产出者；`CROSSHAIR_HIT` 与 `CROSSHAIR_ENTITY` 空参重复；`CONTEXT_FACT` 没有生产模块使用。四条都已从契约删除——不再保留“声明了但永远不命中”的条件；将来要加，连同产出者和文档一起加。

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
- `processingDeadlineMillis`
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

- 接收本轮 `DialogueDeliveryPayload` 的权限。
- 调用 LLM 生成本轮回答的权限。
- 调用 TTS 输出本轮结果的权限。
- 主动释放 session 的权限。
- 延长当前轮处理期限的权限。

非 owner 不应获得本 session 的对话正文。

### 10.2 释放策略

session 可以因为以下原因释放：

- owner 主动完成。
- 玩家取消输入。
- 输入超时。
- owner 处理失败。
- 新一轮仲裁选择了其他 owner。
- 玩家切换目标。
- 世界或玩家生命周期结束。

## 11. 预中标人与租期策略

玩家在游戏中会频繁切换目标和意图，所以 IA 区分两个概念：

| 概念 | 由什么决定 | 有没有租期 | 什么时候变 |
|---|---|---|---|
| 预中标人 | 交互上下文摘要里的上下文条件 | 没有 | 摘要一变就重算 |
| 中标人 | 每轮仲裁选出的 owner | 有，按参与方注册档位 | 每轮仲裁、租期到期 |

两个直接效果：

- **预中标人不需要玩家说话**：指针移到酒狐、主手换成对讲机、屏幕打开，预中标人就会变，并立刻写入伙伴广播。
- **中标人负责多轮连续性**：某一轮中标后，在租期之内再说话时，只要没有新的硬 claim，就仍然归它，租期从这一轮重新计时；租期过了才回到默认。

核心维度：

| 维度 | 说明 |
|---|---|
| `claim 来源档` | 实际命中的条件属于上下文档还是醒词档；上下文档更高。 |
| `priority` | 参与方基础优先级，在同一来源档内比较。 |
| `holdTier` | 参与方注册时声明的中标租期：`20s` / `10s` / `5s` / `0s`；`0s` 表示不持有。 |
| 默认承接者 | 玩家在听澜设置里选的那一位（候选 = 注册时声明 `DEFAULT_OWNER` 的参与者，含"无"）；从未配置过时用**出厂默认**（辅星声明了这个身份），没装辅星则为无。没有任何 claim、也没有有效租期时归它。 |

仲裁规则：

1. 每轮输入都创建新的 claimed session。
2. 有硬 claim 时，只在硬 claim 之间按来源档、`priority` 和稳定 participant ID 选择 owner；选中者的租期从这一刻开始算。
3. 没有硬 claim 时，上一轮中标人的租期未过期就继续归它，租期从这一轮重新计时；否则归默认承接者。
4. 每轮仲裁都会抢占并结束上一轮 active session，只有 owner 确实变化时才额外发布 `conversation.owner_changed`。
5. 当前轮 processing deadline 默认是 10 秒，owner 可按实际异步处理需要续期，但绝对截止时间固定为 session 创建时间后 180 秒，反复续期不能绕过该上限。
6. 租期与 processing deadline 是两件事：租期决定“下一轮说话归谁”，processing deadline 只保护本轮异步处理。

预中标人和租期都不向玩家暴露数值；对外只有映迹的伙伴广播（`PRESENCE.PARTNER`：预中标人或中标人，中标时带租期截止时间）。

**默认承接者是一个设置项**：听澜设置页（【听澜】ASR语音识别）里的"默认承接者"一组，选项是"无"加上当前注册的 `DEFAULT_OWNER` 参与者。两个选项时按钮直接互相切换，三个以上打开选择面板；没有候选时整组不出现。选择写进 `module.ia` 的配置（`defaultOwnerDisabled` + `defaultOwnerParticipantId` 两个键），思衡在每次仲裁时实时读取，所以改完立刻生效并刷新伙伴标记。从未配置过时用出厂默认；选中的模块当前不在注册表时视为"无"，界面不提示、也不回退到别人——选择是玩家的，不替他换人。

## 12. 线程与调度策略

仲裁机关不创建自己的线程池。

所有跨模块请求、claim 判断、仲裁和状态事件都应通过协议中心的执行模型完成。

建议策略：

| 操作 | 推荐执行 lane | 推荐 broker 语义 |
|---|---|---|
| participant 注册 | `CPU` | 短任务、串行保护注册表。 |
| 单次仲裁 | `CPU` | 有界队列，避免语音输入堆积。 |
| owner 定向投递 | `CPU` / `IO` | 取决于 owner capability。 |
| LLM 调用 | `IO` | 由 LLM 模块自身管理。 |
| TTS 调用 | `AUDIO_IO` / `IO` | 由 TTS 模块自身管理。 |
| UI 状态展示 | `MAIN` | 只投递状态，不做重逻辑。 |
| 租期到期任务 | `SCHEDULED` | 到点时写一次伙伴广播并回默认；只在当前没有预中标人显示时才需要排。 |
| Presence query timeout | `SCHEDULED` | 由协议中心持有可取消的超时任务；响应、stop 或 destroy 时取消。 |

仲裁机关只表达任务意图和边界，不直接控制底层线程。IA 内部需要延迟或定时执行时，也应使用 `ModuleExecutionAccess.schedule(...)` 这类协议中心受控执行入口，不能自建私有线程池或绕开模块宿主生命周期。

### 12.1 生命周期与世界会话边界

IA 的租期到期任务和 Presence 查询超时都由 IA 表达生命周期语义，但实际线程由协议中心 `SCHEDULED` lane 持有。IA 为每次 runtime 激活分配递增 generation；`stop`、`destroy` 或重复 `prepare` 会先关闭旧 generation、取消 pending 到期任务与 Presence 请求，旧响应和已经排队的回调只能结束协议上下文，不能继续创建 session 或发送 delivery。这里不包含模型推理、网络等待或 Minecraft 主线程工作。

到期任务抛出异常时，该调度代际停止并由 Protocol task handle 保留 failure cause，不反复制造同一次失败；后续显式 prepare 可以重新启动新的调度代际。

IA 的 runtime 资源遵循以下边界：

- `prepare` 绑定当前 voice resource registry、发布当前 participant 的 wake/extra words、激活新的 runtime generation、标记仲裁能力 ready，并订阅交互上下文摘要。ready 表示 IA 服务可以接收并给出仲裁结论，不要求当前已经存在 owner；participant 为空时直接以 `NO_PARTICIPANT` 拒绝，不读取上下文、不创建 active session，也不触发 delivery、LLM 或 TTS。IA 可以保留终态 `REJECTED` 审计记录。
- 重复 `prepare` 不重复订阅摘要；若 runtime context 发生替换，先解绑旧 voice registry 和旧 capability 状态。
- `stop` 先关闭当前 runtime generation，再取消 pending 到期任务，解绑 voice trigger 并撤销 `ARBITRATION` ready 状态。
- `destroy` 可重复执行，并清空 participant、session、租期、冻结上下文、交互摘要槽位和已写入的伙伴广播去重状态。
- Protocol 已关闭时，`stop` / `destroy` 仍只做幂等资源释放，不尝试创建替代线程或兼容分支。

Core 在世界退出时按 `stop -> destroy -> unregister` 清理模块，再次进入世界时重新运行 assembler 并创建新的 IA 实例。因此 participant、owner、session、租期和 preview 都不跨世界继承；各功能模块或外部模组必须在新世界会话重新注册 participant。

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

但最终接入天枢时，应统一转化为 participant 注册和 claim profile。IA 负责用每轮输入和冻结的上下文摘要判断这些 profile 是否硬命中。

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

外部模组不应直接监听 ASR，不应直接绕过仲裁机关接管会话。

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
- 外部模块请求 LLM 走 LLM 模块的公共能力。
- 对话型 LLM 请求不再由 IA 授权：LLM 模块不向 IA 查询使用权限，IA 也不校验 LLM 请求。
- 对话正文的边界由 IA 的定向投递保证：只有当前 owner 收到本轮正文，非 owner 只收到状态事件。
- IA 不代理、不转发、不排队 LLM 请求，不生成 prompt，也不管理 LLM 内部队列。
- 外部模块请求 TTS 走 TTS 模块的公共能力；TTS 不反向向 IA 查询授权。
- 模块内部不得把完整内部消息直接作为公共 payload、日志或诊断输出。
- 内部消息只在需要它的组件之间流转，不为了调试或方便而全局扩散。

## 16. 成熟架构要求

仲裁机关应按完整对话仲裁系统设计，而不是按临时网关或最小闭环设计。

成熟形态至少包含以下子系统：

| 子系统 | 职责 |
|---|---|
| Participant Registry | 管理参与方注册、注销、能力描述、可见性和生命周期绑定。 |
| Claim Engine | 根据 participant 的 claim profile 和当前输入快照收集硬 claim。 |
| Arbitration Policy | 先按硬 claim 的来源档、participant priority 和稳定 ID 处理硬 claim；无硬 claim 时看上一轮中标人的租期，未过期就续用，过期则归默认承接者；不做动态评分。 |
| Session Store | 维护 session 状态、owner、turn、processing deadline、释放原因和审计信息。 |
| Access Control | 校验模块是否有权读取正文、控制 session 或接收事件。 |
| Message Gateway | 对入站和出站正文消息做归属检查和定向投递，拒绝旁路访问。 |
| Event Publisher | 发布不含正文的会话状态事件。 |
| Lifecycle Sweeper | 只回收 processing deadline 已过期的 active session；participant/module 注销由 Participant Lifecycle Coordinator 处理，世界切换由 Core 的 stop/destroy 生命周期处理。 |
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
映迹发布 PRESENCE.INTERACTION_CONTEXT（交互上下文摘要）
  ↓
IA 重算预中标人，变了才写伙伴广播
  ↓
IR 发布 IrResultPayload
  ↓
仲裁机关检查请求有效性和过期时间
  ↓
读取 participant 注册表快照
  ↓
根据 claim profile 与开口时冻结的摘要收集本轮硬 claim
  ↓
有硬 claim：按来源档 / priority / 稳定 ID 选择；没有：上一个中标人的租期，再没有：默认承接者
  ↓
创建新的 claimed session，中标人的租期开始计时
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
3. **位于 IR 之后，继承 IR 的文本修复和 wake word 结果；交互上下文由映迹推送摘要，IA 据此维护预中标人。**
4. **通过协议中心运行，不另起调度体系。**
5. **common 主体不依赖 Minecraft 活对象。**
6. **外部模组自定义交互方式，但统一适配为 participant / claim。**
7. **LLM 和 TTS 是 owner 可调用的能力，不是仲裁机关内部硬编码步骤。**
8. **默认拒绝旁路访问，所有正文链路必须定向投递。**
9. **内部消息不做无关扩散，只在需要它的组件之间流转。**
10. **对外状态事件只表达状态变化，不携带正文和完整上下文。**
