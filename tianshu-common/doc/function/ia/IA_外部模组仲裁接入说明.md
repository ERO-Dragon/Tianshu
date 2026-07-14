# IA 外部模组仲裁接入说明

## 1. 接入目标

本文面向希望参与天枢开放对话仲裁的外部模组，包括 NPC、女仆、实体、方块实体、机械装置、物品交互模块、世界事件模块以及其他业务 owner。

外部模组接入 IA 的目标不是直接监听玩家语音，也不是绕过 IA 接管 LLM，而是把自身声明为一个可被 IA 选择的对话参与方，由 IA 统一决定本轮对话归属。

标准链路如下：

```text
IR 修复和结构化玩家输入
  ↓
IA 执行仲裁
  ↓
IA 选择 owner participant
  ↓
IA 定向投递 DialogueDeliveryPayload
  ↓
owner 模组处理本轮对话
  ↓
owner 可选调用 LLM / TTS / 游戏动作
  ↓
owner 释放 session 或延长当前轮处理期限
```

外部模组不得绕过 IA 直接监听 ASR 文本，不得通过公共状态事件获取正文，也不得自行实现对话型 LLM 使用的最终安全边界。

## 2. 职责边界

### 2.1 IA 负责

IA 负责：

- 管理 dialogue participant 注册表。
- 根据 IR 输入、IA 捕获的轻量上下文、硬 claim、priority、attention 衰减和默认 owner 选择本轮 owner。当前默认 owner 由 AX 注册。
- 创建和维护 dialogue session。
- 记录 session owner、turn、processing deadline、状态和释放原因。
- 将对话正文定向投递给当前 owner。
- 发布不含正文的 session 状态事件。
- 校验对话型 LLM 使用权限。

### 2.2 外部模组负责

外部模组负责：

- 声明自己的 participant 身份和能力范围。
- 注册接收 IA delivery 的 capability。
- 在收到 delivery 后执行自身业务逻辑。
- 如需调用 LLM，通过协议中心提交 `LLMPromptRequestPayload`，并把 IA delivery 中的正文和上下文组织进 prompt / RAG chunk。
- 在完成、取消、失败、需要延长当前轮处理期限或确认中断时，通过 session control 通知 IA。

### 2.3 LLM 负责

LLM 模块负责：

- 通过协议中心统一接收 `ProtocolCapabilities.LLM_REQUEST`。
- 处理 `PayloadType.LLM_PROMPT_REQUEST` / `LLMPromptRequestPayload`。
- 按 `lane=CHAT` 或 `lane=TASK` 执行推理调度。
- 通过协议响应返回 `LLMPromptResultPayload` / `LLMPromptStreamChunkPayload`，由协议中心按原请求 `envelopeId` 路由给请求方登记的响应处理器。

外部模组不需要也不应该绕过协议中心直接访问 LLM 底层实现。对话正文的 owner 归属由 IA 在 delivery 前完成；LLM 只处理 owner 模块提交的推理请求，不参与 owner 判定。

## 3. 注册 participant

每个希望参与开放对话的模组，都需要向 IA 注册一个或多个 `DialogueParticipantDescriptor`。

核心字段：

```java
DialogueParticipantDescriptor(
    String participantId,
    String moduleId,
    String displayName,
    int priority,
    List<String> supportedIntents,
    List<String> supportedEntityTypes,
    List<String> supportedItemIds,
    DialogueClaimProfile claimProfile,
    DialogueVoiceTriggerGroup voiceTriggerGroup,
    String routeCapability,
    DialogueTurnProcessingPolicy turnProcessingPolicy
)
```

字段语义：

| 字段 | 说明 |
|---|---|
| `participantId` | 模组内部稳定参与方 ID，例如 `maid.default`、`npc.villager_chat`。 |
| `moduleId` | 模组 ID，必须和后续 LLM 请求中的 requester module 一致。 |
| `displayName` | 面向 UI 或诊断的显示名。 |
| `priority` | 基础优先级，越高越容易在相近匹配下胜出。 |
| `supportedIntents` | 兼容字段，当前按 wake word 处理，例如 `酒狐`、`maid`、`create`。新接入建议使用 `DialogueClaimProfile.rules(...)` 显式声明规则。 |
| `supportedEntityTypes` | 支持的实体类型 ID。 |
| `supportedItemIds` | 支持的物品 ID。 |
| `claimProfile` | 参与 IA 仲裁的硬命中规则。只有 `WAKE_WORD` 条件会作为仲裁 wake word；`extraWords` 不参与 IA claim。 |
| `voiceTriggerGroup` | 该模块的共享语音触发词组，包含 `wakeWords` 与 `extraWords`。`wakeWords` 会进入 ASR/IR 并可参与 IA wake claim；`extraWords` 只进入 ASR 热词与 IR 修复/匹配，不参与 IA 仲裁。一个模块当前只应维护一组。 |
| `routeCapability` | IA 选中该 participant 后投递正文的 capability。能力名由模块自定义，但能力契约必须接收标准 `DialogueDeliveryPayload`。 |
| `turnProcessingPolicy` | 当前轮处理期限策略，只约束本轮异步处理的最大时间，不决定下一轮归属。 |

如果模块参与 IA 仲裁，推荐通过 `DialogueParticipantDescriptor.voiceTriggerGroup` 声明本模块的 wake/extra 热词，不要再用同一个 `moduleId` 额外注册一份 `VoiceTriggerRegistration`；共享热词注册表按 `moduleId` 覆盖旧值，重复入口会互相踩掉。普通非 IA 语音触发仍可直接使用 `VoiceTriggerRegistration`。

### 3.1 通过协议 capability 注册

外部模块和其他功能模块统一通过协议中心提交 participant 注册：

```text
ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER
PayloadType.DIALOGUE_PARTICIPANT_REGISTER
DialogueParticipantRegisterPayload
PacketType.COMMAND
```

卸载或停用时提交：

```text
ProtocolCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER
PayloadType.DIALOGUE_PARTICIPANT_UNREGISTER
DialogueParticipantUnregisterPayload
PacketType.COMMAND
```

不要从 module service registry 获取 IA 实现服务，也不要直接持有 IA registry。协议 capability 才是跨模块稳定契约，它负责 payload 校验、生命周期、失败观测和无 IA 组合下的 provider 检查。

如果一个模块注册了多个 participant，模块卸载时应逐个通过 unregister capability 注销，避免 IA 中留下失效 owner。

### 3.2 仲裁入口语义

标准语音/聊天主链路由 IR 向 `ProtocolCapabilities.DIALOGUE_ARBITRATE` 发送 `COMMAND`。IR 只负责提交已经修复和结构化的候选输入，不等待仲裁结果；IA 会在内部完成 owner 判定、session 创建/更新、正文 delivery 和 session 状态事件发布。

`DIALOGUE_ARBITRATE` 也支持 `REQUEST`，但它不是主链路默认用法。只有诊断、测试或受信模块确实需要同步获知本次仲裁结论时，才应使用 `REQUEST`，并为原请求 `envelopeId` 注册 `PayloadType.DIALOGUE_ARBITRATION_RESULT` 响应处理器。外部对话参与方通常只需要注册 participant 和 delivery capability，不需要主动消费仲裁结果。

## 4. 注册 delivery capability

模组必须注册一个用于接收正文投递的 capability，并把该 capability ID 写入 `DialogueParticipantDescriptor.routeCapability`。

该 capability 应接收：

```text
PayloadType.DIALOGUE_DELIVERY
DialogueDeliveryPayload
PacketType.COMMAND
```

IA 不适配各模组的私有 payload。参与者可以自定义 capability 名，例如 `MAID.DIALOGUE_INPUT`、`CREATE.DIALOGUE_INPUT`，但注册 participant 时 IA 会校验 `routeCapability` 是否已经注册，且是否满足上述公共投递契约。

IA 只有在该 participant 被选为 owner 后，才会把正文定向投递到这个 capability。

`DialogueDeliveryPayload` 包含：

```java
DialogueDeliveryPayload(
    String sessionId,
    String requestId,
    String playerId,
    String turnId,
    String repairedText,
    String normalizedText,
    List<String> matchedWakeWords,
    List<String> matchedItemIds,
    List<DialogueEntityRef> matchedEntityRefs,
    DialogueInteractionHints interactionHints,
    DialogueContextSnapshot contextSnapshot,
    long timestampMillis,
    long expireAtMillis
)
```

owner 可以读取 `repairedText` / `normalizedText` 并结合上下文决定如何处理。`repairedText` 是 IR 修复后的自然语言正文，例如 `下届合金能做什么` 修复为 `下界合金能做什么`；物品资源 ID 只通过 `matchedItemIds` 提供，不应混入正文。

非 owner 不会收到该 payload。

其中 `repairedText`、`normalizedText`、`matchedWakeWords`、`matchedItemIds` 来自 IR 的文本侧结果。`matchedWakeWords` 在 IA 仲裁语义中只表示 wake word，不参与模糊评分。`matchedEntityRefs`、`interactionHints`、`contextSnapshot` 来自 IA 通过 `PRESENCE.QUERY_CONTEXT` 获取的仲裁快照；`matchedEntityRefs` 使用 `DialogueEntityRef`，会同时包含实体 UUID/ref id、实体类型 ID、显示名、距离和是否为准星目标。语音输入场景下，IA 会优先使用 ASR `speaking=true` 时冻结的快照，而不是 ASR final 后的状态。外部模组不要假设 IR 会在仲裁请求 payload 里提供手持物、身上装备、准星实体、维度或按键状态。

## 5. 处理 delivery

模组收到 `DialogueDeliveryPayload` 后，应执行以下步骤：

```text
1. 检查 payload 类型、sessionId、requestId、expireAtMillis。
2. 根据自身业务构造响应计划。
3. 可选执行游戏动作、固定文本回复、LLM 请求或 TTS 请求。
4. 处理完成后释放 session，或在长任务中延长当前轮处理期限。
```

最小必要校验：

- payload type 必须是 `DIALOGUE_DELIVERY`。
- `sessionId` 不得为空。
- `requestId` 不得为空。
- `expireAtMillis` 未过期。
- delivery capability 必须是该 participant 注册给 IA 的 capability。

这些校验只用于保护模组自己的入口，不代替 IA 与 LLM 的最终安全校验。

## 6. 调用 LLM

如果 owner 需要调用 LLM，应通过协议中心提交 `LLMPromptRequestPayload`。

```text
ProtocolCapabilities.LLM_REQUEST
PayloadType.LLM_PROMPT_REQUEST
LLMPromptRequestPayload
```

对话型请求使用 `lane=CHAT`。请求内容由 `chunks` 组成，常见结构是一个 `message` chunk，包含 system / user / assistant 消息；需要 RAG 时再追加 `rag` chunk。

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    delivery.requestId(),
    1024,
    0.7f,
    true,
    false,
    "CHAT",
    0,
    false,
    List.of(LLMPromptRequestPayload.ChunkPayload.message(List.of(
        LLMPromptRequestPayload.MessageItemPayload.system("你是当前 IA owner participant"),
        LLMPromptRequestPayload.MessageItemPayload.user(delivery.repairedText())
    )))
).withDialogueAuthorization(
    delivery.sessionId(),
    ownerModuleId,
    ownerParticipantId,
    delivery.turnId()
);
```

提交方式：

```text
EnvelopeBuilder.requestCapability(
    ownerModuleId,
    ProtocolCapabilities.LLM_REQUEST,
    PayloadType.LLM_PROMPT_REQUEST,
    payload
)
```

LLM 输出不会发布到公共 LLM 文本 topic，而是作为原请求的协议响应返回：

```text
LLMPromptStreamChunkPayload
LLMPromptResultPayload
```

注意：

- `ProtocolCapabilities.LLM_REQUEST` 是当前 LLM 的公共能力入口。
- `LLM_PROMPT_REQUEST` 是当前 LLM 的公共请求 payload 类型。
- `CHAT` / `TASK` 是 `LLMPromptRequestPayload.lane`，不是 IA owner 判定。
- `lane=CHAT` 是对话型 LLM 请求，必须携带 `dialogueSessionId / requesterModuleId / requesterParticipantId / dialogueTurnId`。推荐使用 `withDialogueAuthorization(delivery.sessionId(), ownerModuleId, ownerParticipantId, delivery.turnId())` 从 IA delivery 补齐。
- `requesterModuleId` 必须和提交 `LLM_REQUEST` 的 envelope `sourceId` 一致；owner 模块只能代表自己提交对话型 LLM 请求，不能冒用其他 owner 身份。
- LLM 模块在执行 `lane=CHAT` 前会向 IA 的 `DIALOGUE_LLM_USAGE_AUTHORIZE` 查询授权；只有当前 session owner、turn 匹配且 session 仍 active 时才会放行。
- IA 不把 owner 或处理期限作为 LLM payload 字段传入；owner 归属由 IA 在 delivery 前完成。
- 外部模组不要恢复或依赖旧的任务式 LLM 接入口。

## 7. 非对话型 LLM 任务

如果模组调用 LLM 是后台任务，例如摘要、索引、压缩、离线分析，不基于当前 dialogue session，应仍然使用同一个 LLM capability，只把 `LLMPromptRequestPayload.lane` 设置为 `TASK`。

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "memory-compress",
    2048,
    0.3f,
    false,
    false,
    "TASK",
    10,
    true,
    chunks
);
```

`TASK` 请求不代表 IA 对话 owner，也不能用于接管当前玩家对话。凡是来自 IA dialogue delivery 并用于回答本轮玩家对话的请求，都应使用 `lane=CHAT`，由当前 owner 模块提交，并携带本轮 delivery 的 dialogue 授权上下文。

## 8. 控制 session

owner 可以通过 IA session control 控制当前 session。

使用：

```text
ProtocolCapabilities.DIALOGUE_SESSION_CONTROL
PayloadType.DIALOGUE_SESSION_CONTROL
```

典型动作包括：

```text
EXTEND_PROCESSING
RELEASE
INTERRUPT_ACK
```

请求中必须携带：

```text
sessionId
requesterModuleId
requesterParticipantId
action
reason
requestedProcessingMillis
```

IA 会校验 requester 是否为当前 session owner。非 owner 控制请求会被拒绝。

建议：

- 短回复完成后主动 `RELEASE`。
- 长时间 LLM / TTS / 动作流程中，在 processing deadline 接近过期前 `EXTEND_PROCESSING`。
- 收到中断语义时及时 `INTERRUPT_ACK` 或释放。
- 模块停用、实体消失、玩家离线或世界切换时释放或注销 participant。

## 9. 监听状态事件

其他模块可以订阅：

```text
ProtocolTopics.DIALOGUE_SESSION_EVENTS
PayloadType.DIALOGUE_SESSION_EVENT
```

事件只包含 session 状态，不包含玩家正文、prompt、LLM response 或完整上下文。

状态事件适合用于：

- UI 显示当前对话 owner。
- 调试面板观察 session 生命周期。
- 非 owner 模块感知当前是否有人接管对话。
- 音频、字幕、视觉反馈模块做非正文状态提示。

状态事件不能作为获取对话正文的通道。

如果 UI 只需要展示“玩家此刻说话会被谁承接”，可以订阅：

```text
ProtocolTopics.DIALOGUE_OWNER_PREVIEW
PayloadType.DIALOGUE_OWNER_PREVIEW
```

该 topic 只发布 `DialogueOwnerPreviewPayload(playerId, moduleId, participantId, displayName, updatedAtMillis)`，不包含正文、prompt、上下文细节或仲裁原因。IA 只在 preview owner 发生变化时发布一次，并通过协议中心 `SCHEDULED` lane 定期刷新 attention 衰减后的最新归属，因此订阅方可以把它视为“当前 owner 预览”的最新态。

## 10. 参与仲裁的推荐流程

### 10.1 模块启动

```text
1. 注册自己的 delivery capability。
2. 构造 DialogueParticipantDescriptor。
3. 通过 DIALOGUE_PARTICIPANT_REGISTER capability 注册 participant。
```

### 10.2 收到 IA delivery

```text
1. 校验 DialogueDeliveryPayload。
2. 读取 repairedText / normalizedText。
3. 构造业务响应。
4. 如需 LLM，提交 lane=CHAT 的 LLMPromptRequestPayload。
5. 等待 LLM result 或 stream chunk。
6. 输出结果或执行动作。
7. 主动释放 session。
```

### 10.3 模块停止

```text
1. 取消本模块未完成任务。
2. 释放或取消当前持有的 session。
3. 注销 participant。
4. 清理本地 pending 请求。
```

### 10.4 退出世界后再次进入

IA 的 participant、owner、session、attention 和 owner preview 都属于当前世界会话内存状态，不跨世界保留。Core 在退出世界时销毁当前 IA 实例，再次进入世界时装配新的实例。

因此外部模组必须把 participant 注册视为世界会话启动步骤：

```text
退出世界
  ↓
取消本模块 pending 请求并释放/注销当前会话资源
  ↓
再次进入世界，等待天枢模块会话启动
  ↓
重新注册 delivery capability 对应的 participant
```

不要缓存旧 `sessionId`、旧 owner preview 或“已经注册”的本地标记并跨世界复用。重新注册仍使用原有 `DIALOGUE_PARTICIPANT_REGISTER` 契约，不存在额外的恢复接口或兼容入口。

## 11. 不允许的接入方式

外部模组不得：

- 直接监听 ASR 文本来接管开放对话。
- 绕过 IR 直接向 IA 塞未经修复和结构化的正文。
- 通过公共 session event 获取正文。
- 自行广播玩家输入正文、LLM prompt 或 LLM response。
- 在非 owner 状态下处理当前 session 正文。
- 绕过 IA delivery，把非 owner 模块的内容伪装成当前对话响应。
- 将当前玩家对话交给 `lane=TASK` 后台任务承接。
- 自建全局线程池绕过协议中心调度。
- 长时间持有 session 不延长处理期限也不释放。
- 在模块卸载后保留 participant 注册。

## 12. 最小接入检查表

接入前确认：

- [ ] 有稳定 `moduleId`。
- [ ] 有稳定 `participantId`。
- [ ] 注册了接收 `DIALOGUE_DELIVERY` 的 capability。
- [ ] `DialogueParticipantDescriptor.routeCapability` 指向该 capability。
- [ ] `routeCapability` 对应能力已经注册，且接收 `DialogueDeliveryPayload` 和 `COMMAND`。
- [ ] 能在模块停止时注销 participant。
- [ ] 能处理 `DialogueDeliveryPayload.expireAtMillis`。
- [ ] 对话型 LLM 请求使用 `LLMPromptRequestPayload`。
- [ ] 对话型 LLM 请求使用 `lane=CHAT`。
- [ ] 后台 LLM 请求才使用 `lane=TASK`。
- [ ] 不把 IA owner 判定塞进 LLM payload。
- [ ] 不直接访问 JavaLlamaServer HTTP，不直接订阅或广播底层 LLM 文本。
- [ ] 理解 `LLM_REQUEST` 的最终输出通过 result/chunk 协议响应返回。
- [ ] 完成后会释放 session，长任务会延长当前轮处理期限。
- [ ] 不把正文写入公共 topic、日志或诊断快照。

## 13. 示例身份对齐

假设某女仆模组注册：

```text
moduleId = module.maid
participantId = maid.default
routeCapability = MAID.DIALOGUE_INPUT
```

当 IA 选中该 participant 后，session owner 为：

```text
ownerModuleId = module.maid
ownerParticipantId = maid.default
```

女仆模组向 LLM 发对话型请求时使用：

```text
ProtocolCapabilities.LLM_REQUEST
PayloadType.LLM_PROMPT_REQUEST
LLMPromptRequestPayload.lane = CHAT
```

请求的 prompt / RAG 内容由女仆模组根据 `DialogueDeliveryPayload` 构造。IA 不把 owner 或处理期限作为 LLM payload 字段传入；owner 事实保存在 IA session 中，并体现在只有当前 owner 才会收到正文 delivery。

女仆模组处理完成后，应通过 IA session control 释放，长任务才需要延长当前轮处理期限：

```text
sessionId = delivery.sessionId
turnId = delivery.turnId
```

只有当前 owner 能接收本轮 `DialogueDeliveryPayload`。非 owner 不应持有正文，也不应构造本轮对话的 LLM 请求。

## 14. 接入原则总结

1. 外部模组通过 participant 参与仲裁，不直接抢夺语音链路。
2. IA 决定 owner，owner 才能接收正文。
3. LLM 是 owner 可调用的能力，不是 owner 判定者。
4. 对话型 LLM 请求使用 `LLM_REQUEST + LLMPromptRequestPayload + lane=CHAT`。
5. 后台 LLM 请求使用同一个能力入口，只把 `lane` 设置为 `TASK`。
6. 外部模组不要向 LLM payload 携带自声明 owner 或处理期限。
7. 非 owner 只能看状态事件，不能看正文。
8. LLM 输出必须绑定原始请求返回，不做底层 LLM 公共广播。
9. 所有跨模块公开通信走协议中心 capability 或 topic，请求结果走协议响应处理器。
10. 模组必须处理注销、释放、处理期限延长和超时。
