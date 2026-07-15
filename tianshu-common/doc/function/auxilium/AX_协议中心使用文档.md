# AX 协议中心使用文档

本文说明辅星（AX）如何通过协议中心参加天枢对话链路，以及其他模块应如何与这条链路协作。AX 是 IA 管理的一个 dialogue participant，不是可以绕过 IA 直接调用的公共聊天或 LLM 服务。

## 1. 接入前提

调用方应作为天枢托管模块完成装配，并在装配阶段通过 `TianshuModuleAssemblyContext.moduleRuntime()` 提供的 `ModuleRuntimeAccess` 创建自己的协议 adapter。不要从 `TianshuCoreManager` 获取完整协议运行时，也不要直接持有 `AXModule`、`AXTurnOrchestrator`、memory 或 prompt 实现。

## 2. AX 的公开身份

AX 使用：

```text
moduleId = module.ax
participantId = tianshu.AX
routeCapability = AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY
```

`AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY` 当前值为 `AX.DIALOGUE_INPUT`，契约为：

| 项目 | 值 |
| --- | --- |
| PayloadType | `DIALOGUE_DELIVERY` |
| Payload | `DialogueDeliveryPayload` |
| PacketType | `COMMAND` |
| Broker | `BOUNDED_QUEUE` |
| 完成策略 | `MANUAL_COMPLETE` |

该 capability 是 IA 选中 AX 为当前 owner 后的定向 delivery 入口，不是外部模块的通用 prompt API。外部模块不得直接向它发送正文，否则会绕过 participant claim、session owner、turn、expireAt 和 LLM 授权边界。

## 3. 唤醒词与 participant 注册

AX 没有内置默认唤醒词。唤醒词为空时，AX 不会向 IA 注册 participant，也不会作为默认 owner 参加仲裁。玩家必须通过 NeoForge 设置页面配置非空词后，AX 才会注册。

注册和注销分别使用：

```text
ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER
ProtocolCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER
```

AX 发送 `DialogueParticipantRegisterPayload` / `DialogueParticipantUnregisterPayload`，并把自己的 route capability、wake word claim 和 voice trigger group 一并交给 IA。其他模块不得冒用 `module.ax` 或 `tianshu.AX` 注册、注销或提交 LLM 请求。

## 4. 外部文本如何到达 AX

外部文本源如果希望进入标准对话链路，应按下面的顺序工作：

```text
外部模块
  -> ProtocolCapabilities.IR_PARSE / IrParsePayload
  -> IR 修复并提交 DIALOGUE_ARBITRATE
  -> IA 根据 participant 和上下文选择 owner
  -> 如果 AX 被选中，IA 向 AX.DIALOGUE_INPUT 投递 DialogueDeliveryPayload
  -> AX 处理本轮并通过 IA session control 完成或释放
```

不要直接调用 `AX.DIALOGUE_INPUT`。如果你的模组希望自己成为 owner，应按 `../ia/IA_外部模组仲裁接入说明.md` 注册自己的 participant，而不是把内容塞给 AX。

## 5. DialogueDeliveryPayload 边界

AX 使用 delivery 中的稳定字段，包括 `sessionId`、`requestId`、`playerId`、`turnId`、`repairedText`、`normalizedText`、命中 wake word/物品/实体、interaction hints、context snapshot、`timestampMillis` 和 `expireAtMillis`。

AX 在处理前会检查自身 readiness、session/turn 和 delivery 时效。调用方不能：

- 自造 owner 身份或过期时间。
- 把 Minecraft 活对象放入 payload。
- 重放旧 delivery 驱动新一轮回复。
- 假定 AX 一定是 owner。

## 6. AX 使用的下游协议

下面这些能力由 AX 调用，不是 AX 对外提供的服务：

| 能力 | 用途 |
| --- | --- |
| `ProtocolCapabilities.LLM_REQUEST` | 当前 owner turn 的 CHAT 请求和后台维护 TASK。 |
| `ProtocolCapabilities.LLM_PRIMITIVE_QUERY` | token count、embedding 和 runtime primitive。 |
| `ProtocolCapabilities.LLM_CACHE_MANAGE` | 请求内 RAG 检索和 cache 管理。 |
| `ProtocolCapabilities.PRESENCE_QUERY_CONTEXT` | 获取允许的动态事实快照。 |
| `ProtocolCapabilities.TTS_SPEAK` | 播放 AX 可见回复。 |
| `ProtocolCapabilities.TTS_CONTROL` | 玩家开始说话时按策略停止当前播报。 |
| `ProtocolCapabilities.DIALOGUE_SESSION_CONTROL` | 延长、释放或确认中断当前 IA session。 |

AX 对 LLM 的 request 会先构建信封、登记 `LLMPromptStreamChunkPayload` / `LLMPromptResultPayload` response handler，再提交请求；停止、超时或模块 stop 时注销 response handler 并通过协议取消。外部模块需要这些能力时应分别遵循 LLM、TTS 和 IA 文档，不能复用 AX 的内部 request ID 或缓存。

## 7. AX 订阅的事件

AX 当前订阅：

| Topic | Payload | 用途 |
| --- | --- | --- |
| `ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY` | `AsrSpeechActivityPayload` | 玩家开始说话时，按配置取消当前 CHAT LLM 并停止当前 TTS。 |
| `PresenceWorldEventPayload.TOPIC` | `PresenceWorldEventPayload` | 把允许的世界事件附加到当前 scope 记忆。 |
| `PresenceChatMessagePayload.TOPIC` | `PresenceChatMessagePayload` | 把聊天消息写入当前 scope 的近期对话。 |

这些 topic 是事件，不是 AX 请求入口。只有对应事实的所有者才应发布 Presence 事件；其他模块不得伪造玩家身份、维度或聊天发送者。

AX 还会向 `ProtocolTopics.MODULE_STATUS` 发布 `module.ax` 的状态。订阅者必须按 `moduleId` 过滤，并优先使用 `messageKey` 本地化，不解析 fallback 文本。

## 8. 玩家说话时的中断

当 `interruptOnPlayerSpeech()` 启用且 ASR 发布 `speaking=true` 时，AX 会：

1. 取消本模块仍在进行的 CHAT LLM 请求。
2. 向 `ProtocolCapabilities.TTS_CONTROL` 发送 `STOP_CURRENT`。
3. 保持 IA session/owner 语义由标准 session control 和后续 turn 处理。

speech activity 使用最新态语义，不能被外部模块当作可靠的逐帧音频队列。

## 9. 生命周期与线程

- AX 的 capability/topic handler 只做校验、状态交接和受控编排；LLM、RAG、存储、Presence 查询和维护任务不得占用 Minecraft 主线程。
- AX stop 会停止维护、保存近期 checkpoint、取消或清理 pending LLM/primitive/RAG/Presence 请求，并注销 participant。
- participant 未注册、AX 未 prepare、delivery 过期或 IA owner 已变化时，调用方不能假设 AX 会输出回复。
- 外部模块不得依赖 AX 内部 timeout、prompt 文件、memory JSON 路径、execution lane 队列容量或具体 LLM client 类。

## 10. 请求 AX 的正确方式

AX 当前没有面向任意外部模块的公共 `REQUEST` capability。需要不同功能时应选择真正的协议所有者：

| 需求 | 正确入口 |
| --- | --- |
| 把文本送入开放对话 | `IR_PARSE`，随后由 IA 仲裁。 |
| 自己成为对话 owner | IA participant 注册。 |
| 直接调用模型 | LLM 公共 capability，并遵守 IA CHAT 授权。 |
| 播放或合成语音 | TTS 公共 capability。 |
| 观察 AX 状态 | `MODULE.STATUS`，过滤 `module.ax`。 |

不要新增 `AX.CHAT_REQUEST`、`AX.LLM_RESULT` 或公开 AX memory topic 来绕过现有 owner、响应关联和隐私边界。

## 11. 最小接入检查表

- [ ] 不直接发送 `AX.DIALOGUE_INPUT`。
- [ ] 文本先进入 IR，再由 IA 选择 owner。
- [ ] 空唤醒词时不假定 AX 已注册。
- [ ] 不冒用 `module.ax / tianshu.AX` 身份。
- [ ] LLM、TTS、IA 请求分别遵循对应模块文档。
- [ ] 不发布伪造 Presence 或 ASR 活动事件。
- [ ] 不在 handler 或 Minecraft 主线程执行阻塞任务。
- [ ] 不读取 AX 内部 memory、prompt、timeout 或 pending request 实现。
## 诊断记录

AX 设置面板中的“诊断记录”开关控制 `module.ax` 的对话调试记录。开启后允许记录交付文本、会话标识和阶段信息；关闭时不会写入集中诊断文件。AX 不自行落盘，也不创建诊断线程。
