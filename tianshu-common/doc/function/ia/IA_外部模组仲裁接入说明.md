# IA 外部模组仲裁接入说明

## 1. 接入目标

本文面向希望参与天枢开放对话仲裁的外部模组，包括 NPC、女仆、实体、方块实体、机械装置、物品交互模块、世界事件模块以及其他业务 owner。

外部模组接入 IA 的目标不是直接监听玩家语音，也不是直接抢占 LLM，而是把自身声明为一个可被 IA 选择的对话参与方，由 IA 统一决定本轮对话归属。

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
owner 释放或续租 session
```

外部模组不得绕过 IA 直接监听 ASR 文本，不得通过公共状态事件获取正文，也不得自行实现对话型 LLM 使用的最终安全边界。

## 2. 职责边界

### 2.1 IA 负责

IA 负责：

- 管理 dialogue participant 注册表。
- 根据 IR 输入、上下文、优先级和租约策略选择 owner。
- 创建和维护 dialogue session。
- 记录 session owner、turn、lease、状态和释放原因。
- 将对话正文定向投递给当前 owner。
- 发布不含正文的 session 状态事件。
- 校验对话型 LLM 使用权限。

### 2.2 外部模组负责

外部模组负责：

- 声明自己的 participant 身份和能力范围。
- 注册接收 IA delivery 的 capability。
- 在收到 delivery 后执行自身业务逻辑。
- 如需调用 LLM，向 LLM 模块提交对话型请求，并携带 session 授权上下文。
- 在完成、取消、失败、需要续租或确认中断时，通过 session control 通知 IA。

### 2.3 LLM 负责

LLM 模块负责：

- 统一接收 LLM task request。
- 对 `INTERACTIVE` 类型请求向 IA 查询 `DIALOGUE_LLM_USAGE_AUTHORIZE`。
- IA 允许后才执行对话型推理。
- IA 拒绝或不可用时 fail closed。

外部模组不需要也不应该各自实现 IA 授权客户端作为最终安全边界。

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
    String routeCapability,
    DialogueInterruptPolicy interruptPolicy,
    DialogueLeasePolicy leasePolicy
)
```

字段语义：

| 字段 | 说明 |
|---|---|
| `participantId` | 模组内部稳定参与方 ID，例如 `maid.default`、`npc.villager_chat`。 |
| `moduleId` | 模组 ID，必须和后续 LLM 请求中的 requester module 一致。 |
| `displayName` | 面向 UI 或诊断的显示名。 |
| `priority` | 基础优先级，越高越容易在相近匹配下胜出。 |
| `supportedIntents` | 支持的意图标签，例如 `chat`、`help`、`trade`。 |
| `supportedEntityTypes` | 支持的实体类型 ID。 |
| `supportedItemIds` | 支持的物品 ID。 |
| `routeCapability` | IA 选中该 participant 后投递正文的 capability。 |
| `interruptPolicy` | 是否允许抢占或被抢占。 |
| `leasePolicy` | owner 租约策略。 |

注册方式可以有两种。

### 3.1 推荐方式：通过 IaModuleService 注册

如果模组运行在同一 module host 中，推荐从服务表获取 `IaModuleService`：

```java
context.services().find(IaModuleService.class).ifPresent(service -> {
    service.registerParticipant(descriptor);
});
```

卸载或停用时必须注销：

```java
service.unregisterParticipant(moduleId, participantId);
```

如果一个模块注册了多个 participant，模块卸载时应注销全部 participant，避免 IA 中留下失效 owner。

### 3.2 协议方式：通过 capability 注册

也可以通过协议中心向 IA capability 提交：

```text
ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER
PayloadType.DIALOGUE_PARTICIPANT_REGISTER
```

注销时提交：

```text
ProtocolCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER
PayloadType.DIALOGUE_PARTICIPANT_UNREGISTER
```

## 4. 注册 delivery capability

模组必须注册一个用于接收正文投递的 capability，并把该 capability ID 写入 `DialogueParticipantDescriptor.routeCapability`。

该 capability 应接收：

```text
PayloadType.DIALOGUE_DELIVERY
DialogueDeliveryPayload
```

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
    List<String> matchedHotwords,
    List<String> matchedItemIds,
    List<String> matchedEntityRefs,
    DialogueInteractionHints interactionHints,
    DialogueContextSnapshot contextSnapshot,
    long timestampMillis,
    long expireAtMillis
)
```

owner 可以读取 `repairedText` / `normalizedText` 并结合上下文决定如何处理。

非 owner 不会收到该 payload。

## 5. 处理 delivery

模组收到 `DialogueDeliveryPayload` 后，应执行以下步骤：

```text
1. 检查 payload 类型、sessionId、requestId、expireAtMillis。
2. 根据自身业务构造响应计划。
3. 可选执行游戏动作、固定文本回复、LLM 请求或 TTS 请求。
4. 处理完成后释放 session，或在长任务中续租 session。
```

最小必要校验：

- payload type 必须是 `DIALOGUE_DELIVERY`。
- `sessionId` 不得为空。
- `requestId` 不得为空。
- `expireAtMillis` 未过期。
- delivery capability 必须是该 participant 注册给 IA 的 capability。

这些校验只用于保护模组自己的入口，不代替 IA 与 LLM 的最终安全校验。

## 6. 调用 LLM

如果 owner 需要调用 LLM，应向 LLM 模块提交 `LlmTaskRequestPayload`。

对话型请求必须使用：

```text
LlmTaskUsageKind.INTERACTIVE
```

并携带：

```text
moduleId = 当前 owner moduleId
agentId = 当前 owner participantId
authorization.sessionId = DialogueDeliveryPayload.sessionId
authorization.turnId = DialogueDeliveryPayload.turnId
```

当前协议 payload 中：

```java
LlmUsageAuthorizationPayload(
    String sessionId,
    String turnId
)
```

`moduleId` 和 `agentId` 位于 `LlmTaskRequestPayload` 顶层字段中。这里的 `agentId` 在 dialogue 授权语义上对应 IA 的 `participantId`。

注意：

- `INTERACTIVE` 是 common 协议层的使用场景标记，不是 JavaLlamaServer 的 `lane`，也不是 message role。
- 当前 LLM common gateway 会统一通过服务端 `task` lane 调用 JavaLlamaServer；`INTERACTIVE` 只表示进入 LLM gateway 调度前必须向 IA 查询授权。
- 是否需要 IA 授权只由 `usageKind` 决定，不由 `purpose` 字符串决定。
- 调用方和 IA 都不直接传 `world`；`world` 由 LLM common 的 `WorldScopeProvider` / `WorldIdentityProvider` 生成，并把 `moduleId/agentId` 组装为服务端 profile。默认 fallback 只保证本地实例级区分；单机存档名、服务器地址、Realm id 等精确世界身份由 MC 适配层注入，不由外部业务模组或 IA 传给 LLM。
- `LLM_TASK_REQUEST` 的协议 complete 只表示 gateway 已接收或拒绝请求，不表示推理完成；实际输出通过 `LlmTaskStreamChunkPayload` 和 `LlmTaskResultPayload` 绑定 parent envelope 异步返回。

LLM 模块收到 `INTERACTIVE` 请求后，会向 IA 查询：

```text
ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE
PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST
```

IA 实际校验：

```text
session 是否存在
session 是否 active
lease 是否未过期
turnId 是否匹配
requesterModuleId/requesterParticipantId 是否为当前 owner
```

外部模组不要在 LLM 请求中伪造 owner 或 lease。当前协议不再接受调用方自带 owner/lease 作为安全事实。

## 7. 非对话型 LLM 任务

如果模组调用 LLM 是后台任务，例如摘要、索引、压缩、离线分析，不基于当前 dialogue session，应使用：

```text
LlmTaskUsageKind.TASK
LlmUsageAuthorizationPayload.EMPTY
```

`TASK` 请求不走 IA 对话 owner 授权，但仍受 LLM gateway 的消息大小、队列、并发和资源策略限制。

不要把实际面向玩家开放对话的请求伪装成 `TASK`。凡是来自 IA dialogue delivery 并用于回答本轮玩家对话的请求，都应使用 `INTERACTIVE`。

## 8. 控制 session

owner 可以通过 IA session control 控制当前 session。

使用：

```text
ProtocolCapabilities.DIALOGUE_SESSION_CONTROL
PayloadType.DIALOGUE_SESSION_CONTROL
```

典型动作包括：

```text
RENEW
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
requestedLeaseMillis
```

IA 会校验 requester 是否为当前 session owner。非 owner 控制请求会被拒绝。

建议：

- 短回复完成后主动 `RELEASE`。
- 长时间 LLM / TTS / 动作流程中，在 lease 接近过期前 `RENEW`。
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

## 10. 参与仲裁的推荐流程

### 10.1 模块启动

```text
1. 注册自己的 delivery capability。
2. 构造 DialogueParticipantDescriptor。
3. 通过 IaModuleService 或 DIALOGUE_PARTICIPANT_REGISTER 注册 participant。
```

### 10.2 收到 IA delivery

```text
1. 校验 DialogueDeliveryPayload。
2. 读取 repairedText / normalizedText。
3. 构造业务响应。
4. 如需 LLM，提交 INTERACTIVE LlmTaskRequestPayload。
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

## 11. 不允许的接入方式

外部模组不得：

- 直接监听 ASR 文本来接管开放对话。
- 绕过 IR 直接向 IA 塞未经修复和结构化的正文。
- 通过公共 session event 获取正文。
- 自行广播玩家输入正文、LLM prompt 或 LLM response。
- 在非 owner 状态下处理当前 session 正文。
- 伪造 `moduleId` / `participantId` 请求 LLM。
- 将对话型玩家请求伪装成 `TASK` 绕过 IA 授权。
- 自建全局线程池绕过协议中心调度。
- 长时间持有 session 不续租也不释放。
- 在模块卸载后保留 participant 注册。

## 12. 最小接入检查表

接入前确认：

- [ ] 有稳定 `moduleId`。
- [ ] 有稳定 `participantId`。
- [ ] 注册了接收 `DIALOGUE_DELIVERY` 的 capability。
- [ ] `DialogueParticipantDescriptor.routeCapability` 指向该 capability。
- [ ] 能在模块停止时注销 participant。
- [ ] 能处理 `DialogueDeliveryPayload.expireAtMillis`。
- [ ] 对话型 LLM 请求使用 `INTERACTIVE`。
- [ ] 不用 `purpose` 判断是否需要 IA 授权。
- [ ] LLM 请求中的 `moduleId` / `agentId` 与 IA 注册身份一致。
- [ ] LLM 请求携带 `sessionId` / `turnId`。
- [ ] 不向 LLM 请求中传自声明 owner 或 lease。
- [ ] 不直接访问 JavaLlamaServer HTTP，不直接订阅或广播底层 LLM 文本。
- [ ] 理解 `LLM_TASK_REQUEST` complete 只表示 gateway 接收/拒绝，最终输出通过 result/chunk 异步返回。
- [ ] 完成后会释放 session，长任务会续租。
- [ ] 不把正文写入公共 topic、日志或诊断快照。

## 13. 示例身份对齐

假设某女仆模组注册：

```text
moduleId = module.maid
participantId = maid.default
routeCapability = MAID.DIALOGUE_DELIVERY
```

当 IA 选中该 participant 后，session owner 为：

```text
ownerModuleId = module.maid
ownerParticipantId = maid.default
```

女仆模组向 LLM 发对话型请求时必须使用：

```text
usageKind = INTERACTIVE
moduleId = module.maid
agentId = maid.default
authorization.sessionId = delivery.sessionId
authorization.turnId = delivery.turnId
```

LLM 向 IA 查询授权时会提交：

```text
sessionId = delivery.sessionId
requesterModuleId = module.maid
requesterParticipantId = maid.default
turnId = delivery.turnId
```

只有当 IA 当前 session owner 仍然是 `module.maid / maid.default`，且 session active、lease 未过期、turn 匹配时，LLM 才会放行。

## 14. 接入原则总结

1. 外部模组通过 participant 参与仲裁，不直接抢夺语音链路。
2. IA 决定 owner，owner 才能接收正文。
3. LLM 是 owner 可调用的能力，不是 owner 判定者。
4. 对话型 LLM 请求必须由 LLM 入口向 IA 查询授权。
5. 是否需要 IA 授权只看 `usageKind`，不看 `purpose`。
6. 外部模组只携带 session 上下文，不携带自声明 owner/lease。
7. 非 owner 只能看状态事件，不能看正文。
8. LLM 输出必须绑定原始请求返回，不做底层 LLM 公共广播。
9. 所有跨模块通信走协议中心 capability、direct route 或 topic。
10. 模组必须处理注销、释放、续租和超时。
