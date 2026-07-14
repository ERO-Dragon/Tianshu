# 天枢协议中心架构契约

协议中心是天枢各功能模块之间的通信和生命周期基础设施。它不承载业务脑，不理解业务 Payload 的内部含义，也不直接执行 Minecraft、LLM、TTS、ASR 或 UI 动作；它只负责把标准信封安全地路由、排队、取消、观测和清理。

## 1. 核心原则

1. **跨模块通信只走协议中心。** 模块之间不能直接 import 对方实现类，也不能绕过协议中心调用其他模块内部服务。
2. **公开寻址模型只有能力和主题。** 能力用于请求或命令服务拥有者，主题用于发布事件。协议响应不是第三种公开寻址模型，而是协议中心根据请求信封 ID 管理的内部回包通道。
3. **协议中心不拆业务 Payload。** Payload 是类型化黑盒。协议中心只校验 `PayloadType`、Payload Java 类型、`PacketType`、优先级和注册声明是否匹配。
4. **模块声明能力，协议中心托管生命周期。** 模块通过 `CapabilityDescriptor`、`TopicSubscriptionDescriptor` 等声明自己能处理什么，协议中心负责排队、分发、完成、失败、取消、过期和死信。
5. **业务编排留在业务模块。** ASR、IR、IA、AX、LLM、TTS 等流程由模块或编排模块通过信封派生完成，协议中心不硬编码业务流程。
6. **功能模块只获得受控运行端口。** 装配器通过 `ModuleRuntimeAccess` 注入 `ModuleProtocolAccess`、`ModuleExecutionAccess` 和只读 registration view；`ProtocolRuntime`、`ProtocolExecutorManager`、registry、broker、dead-letter 和 lifecycle store 不向功能模块暴露。

## 2. 统一信封

所有跨模块交互都封装为 `TianshuEnvelope`。

```java
class EnvelopeHeader {
    String envelopeId;
    String traceId;
    String parentId;
    String sourceId;

    TargetMode targetMode;
    String target;
    DeliveryPolicy deliveryPolicy;

    PacketType packetType;
    PayloadType payloadType;
    AckPolicy ackPolicy;

    Priority priority;
    ThreadPolicy threadPolicy;

    long createdAt;
    long deadline;
    long expireAt;

    CancellationScope cancellationScope;
    FailurePolicy failurePolicy;
}
```

| 字段 | 含义 |
|---|---|
| `envelopeId` | 全局唯一信封 ID，用于响应关联、取消、日志和防重复消费。 |
| `traceId` | 链路追踪 ID。同一业务链路中的请求、派生信封、响应和流式片段共享同一个追踪。 |
| `parentId` | 父信封 ID。响应包的 `parentId` 必须指向被响应的请求信封。 |
| `sourceId` | 发信模块或输入源 ID。 |
| `targetMode` | 公开投递模式：`CAPABILITY` 或 `TOPIC`。 |
| `target` | 能力 ID、主题 ID，或协议响应内部目标。普通业务代码不应依赖响应内部目标。 |
| `deliveryPolicy` | 投递策略，例如排队、只保留最新、合并等。 |
| `packetType` | 信件语义，例如 `COMMAND`、`REQUEST`、`RESPONSE`、`CANCEL`、`STATUS`。 |
| `payloadType` | Payload 类型枚举。 |
| `ackPolicy` | 是否期待成功或失败确认。 |
| `priority` | 投递优先级。 |
| `threadPolicy` | Handler 执行线程约束。 |
| `deadline` | 期望完成时间。 |
| `expireAt` | 绝对过期时间，超过后进入过期或死信流程。 |
| `cancellationScope` | 取消传播范围。 |
| `failurePolicy` | 失败后的传播、忽略、重试或降级策略。 |

Payload 必须实现 `ITianshuPayload`，推荐使用不可变 `record`。禁止在 Payload 中携带 `Entity`、`ItemStack`、`Level`、`Player`、`Screen`、`PoseStack` 等活对象；跨线程、跨模块数据必须先快照化。

## 3. 投递模型

### 3.1 能力 Capability

能力表示“谁能处理某类请求或命令”。调用方不关心具体模块，只向能力 ID 投递信封。

典型能力：

| 能力 | Payload | 用途 |
|---|---|---|
| `IR_PARSE` | `IR_PARSE` | 文本或语音意图解析。 |
| `LLM.REQUEST` | `LLM_PROMPT_REQUEST` | LLM 主请求入口，支持流式响应。 |
| `LLM.CACHE_MANAGE` | `LLM_CACHE_MANAGE` | LLM 缓存管理入口。 |
| `TTS_SPEAK` | `TTS_TEXT` | 统一本地语音播报入口。Payload 用 `TtsPlaybackPlacement` 声明排队、丢弃、插入或取消式抢占策略，并携带音色参数。 |
| `TTS_SYNTHESIZE` | `TTS_TEXT` | 只执行语音合成，通过协议响应返回 `TTS_AUDIO`，由调用方自行决定播放方式；可通过 `TTS_CONTROL` 按 requestId 取消。 |
| `TTS_CONTROL` | `CUSTOM` | TTS 控制命令，承载停止、重载以及后续音色/克隆控制。 |
| `DIALOGUE.ARBITRATE` | `DIALOGUE_ARBITRATION_REQUEST` | IA 仲裁入口。 |
| `DIALOGUE.PARTICIPANT_REGISTER` | `DIALOGUE_PARTICIPANT_REGISTER` | 对话参与者注册。 |
| `DIALOGUE.PARTICIPANT_UNREGISTER` | `DIALOGUE_PARTICIPANT_UNREGISTER` | 对话参与者注销。 |
| `DIALOGUE.SESSION_CONTROL` | `DIALOGUE_SESSION_CONTROL` | 会话释放和控制。 |
| `DIALOGUE.LLM_USAGE_AUTHORIZE` | `DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST` | LLM 使用授权。 |
| `AX.DIALOGUE_INPUT` | `DIALOGUE_DELIVERY` | IA 仲裁后投递给 AX 的对话输入。外部参与方可自定义同类能力名，但必须接收 `DialogueDeliveryPayload`。 |

能力注册必须声明：

- 能力 ID；
- 支持的 `PayloadType`；
- Payload Java 类；
- 可接受的 `PacketType`；
- 最低优先级；
- Broker 类型；
- 完成策略。

### 3.2 主题 Topic

主题表示“某件事发生了，订阅者都可以收到”。主题必须先注册，未注册主题会进入死信。

典型主题：

| 主题 | Payload | 用途 |
|---|---|---|
| `INPUT.ASR_SPEECH_ACTIVITY` | `ASR_SPEECH_ACTIVITY` | ASR 在高通/降噪等音频处理后检测到的用户说话活动状态。 |
| `INPUT.ASR_FINAL_TEXT` | `ASR_TEXT` | ASR 最终识别文本。 |
| `IR.RESULT` | `IR_RESULT` | IR 解析结果。 |
| `SYSTEM.RUNTIME_INTERRUPT` | `CUSTOM` | 运行时中断。 |
| `LLM.STATUS` | `LLM_STATUS` | LLM libs 推理事件状态。 |
| `TTS.PLAYBACK` | `TTS_PLAYBACK_STATUS` | TTS 播放状态。 |
| `DIALOGUE.SESSION_EVENTS` | `DIALOGUE_SESSION_EVENT` | IA 会话事件。 |
| `DIALOGUE.OWNER_PREVIEW` | `DIALOGUE_OWNER_PREVIEW` | 当前如果说话将被哪个 IA owner 承接。 |

`LLM.STATUS` 只发布 libs `inferenceEventListener` 回调产生的真实推理事件，例如 `QUEUED`、`STARTED`、`PREFILL_STARTED`、`GENERATION_STARTED`、`SUSPENDED`、`COLD_RESUME_STARTED`、`COMPLETED`、`CANCELLED`、`FAILED`。协议层请求接收、admission 排队和响应完成状态由 response payload 与 envelope lifecycle 表达，不混入该 topic。

高频主题必须节流，优先使用 `LATEST_ONLY`、`COALESCE` 或短生命周期默认值。协议中心不是帧级 UI RenderBus，模块私有高频 UI 状态应由模块内部维护快照。

### 3.3 协议响应 Response

响应包仍然通过协议中心投递，但不暴露为公开寻址模型。

规则：

1. 请求方发送 `PacketType.REQUEST` 到能力。
2. 请求方如需结果，在协议中心登记该请求 `envelopeId` 对应的响应处理器。
3. 服务方调用 `respondTo(parent, payloadType, payload)` 生成 `PacketType.RESPONSE`。
4. 协议中心根据响应包 `parentId` 找到原请求的响应处理器并投递。
5. 一个请求可以收到多个响应包。LLM 流式输出会反复返回 `LLM_PROMPT_STREAM_CHUNK`，最后返回 `LLM_PROMPT_RESULT`。
6. 最终响应到达、请求过期、模块卸载或调用方主动清理时，响应处理器必须注销。

响应不是 `AX.LLM_RESULT` 这类业务能力，也不是 `module.ax` 这类直投路线。业务能力只表达对外可调用的服务入口，回包关系由协议中心按请求链路维护。

## 4. 生命周期与取消

信封状态由协议中心维护：

```text
CREATED -> ACCEPTED -> QUEUED -> DISPATCHED -> RUNNING -> COMPLETED
```

终态包括：

- `COMPLETED`
- `CANCELLED`
- `EXPIRED`
- `FAILED`
- `REJECTED`
- `DEAD_LETTERED`

取消由 `CancellationScope` 决定影响范围：

| Scope | 行为 |
|---|---|
| `SELF_ONLY` | 只取消当前信封。 |
| `CHILDREN` | 取消当前信封及子链路。 |
| `TRACE` | 取消整条追踪链路。 |
| `RESOURCE` | 取消同一资源上的低优先级任务。 |

失败由 `FailurePolicy` 决定后续动作：

| Policy | 行为 |
|---|---|
| `PROPAGATE_CANCEL` | 按取消范围传播。 |
| `FALLBACK` | 允许模块派生降级信封。 |

模块卸载由宿主统一调用 `ProtocolRuntime.unregisterModule(moduleId)`。协议中心会同时清理该模块的 capability provider、topic subscription、未完成请求的 response handler、voice trigger 和 module descriptor；如果某个 capability、topic 或 request 仍有其他模块的登记，只移除目标模块的条目，不得影响其他 provider / subscriber / handler。该清理可用于世界退出后的模块重装配，不要求业务模块直接修改协议内部注册表。
| `IGNORE` | 只清理当前信封。 |
| `RETRY` | 在 Broker 允许时重试。 |
| `REPORT_ONLY` | 记录和上报，不传播取消。 |

`cancelEnvelope(...)` 不是公开直投，它会生成协议取消信封，并由协议中心按目标信封和取消范围处理。

## 5. Broker 与执行通道

Broker 是能力或订阅的资源控制器，负责排队、拒绝、合并、替换、打断和释放资源。

| Broker | 适合场景 |
|---|---|
| `EXCLUSIVE_INTERRUPT` | TTS、音频播放等独占且可打断资源。 |
| `PARALLEL_LIMIT` | LLM、RAG、网络 IO 等有限并发资源。 |
| `LATEST_ONLY` | Hover、准星、Tick 状态等只关心最新值的高频事件。 |
| `BOUNDED_QUEUE` | 普通短队列任务。 |
| `STATELESS_FAST_PATH` | IR、本地规则解析等快速无状态任务。 |
| `MAIN_THREAD` | 客户端 UI 或必须主线程处理的任务。 |
| `SERVER_PACKET` | 服务端真实状态变更意图。 |

阻塞任务不能在 Broker Handler 或主线程里直接执行，必须提交到协议中心统一执行通道：

| Lane | 用途 |
|---|---|
| `MAIN` | Minecraft 主线程转发。 |
| `CPU` | 普通短 CPU 任务。 |
| `IO` | 网络、文件、LLM HTTP stream。 |
| `AUDIO_IO` | 音频播放和设备交互。 |
| `TTS_FAST` | 快速 TTS 合成。 |
| `TTS_AUTOREGRESSIVE` | 自回归 TTS 合成。 |
| `ASR_STREAM` | ASR 音频流处理。 |
| `MODEL_LOAD` | 模型加载和切换。 |
| `LONG` | 长驻监控或子进程监控。 |
| `SCHEDULED` | 定时清理和延迟任务。 |

## 6. 死信与风暴防护

以下情况进入死信：

- 目标能力或主题不存在；
- 响应找不到对应请求处理器；
- PayloadType 或 Payload Java 类不匹配；
- PacketType 不被注册方接受；
- 优先级低于注册方最低要求；
- 信封过期；
- Broker 拒绝且无法降级；
- Handler 抛出未捕获异常；
- 风暴防护触发。

死信默认记录信封 ID、traceId、sourceId、target、targetMode、packetType、payloadType、错误码和简短原因。完整 Payload 只能在显式 Debug 模式下脱敏输出。

风暴防护应关注：

- 同一 SourceId 单位时间投递量；
- 同一 Topic 回环发布；
- 同一 Trace 派生深度；
- 同一 PayloadType 连续重复；
- 流式分片频率。

## 7. 观测

协议中心应暴露轻量观测快照：

- Broker 队列和运行状态；
- 当前模块列表；
- 能力列表；
- 主题列表；
- 主题订阅列表；
- 响应处理器数量或请求 ID 摘要；
- 语音触发注册；
- 最近死信；
- 信封状态流转；
- StormGuard 拒绝统计。

观测接口服务 Debug 和开发者工具，不参与业务判定。

## 8. 模块接入约束

1. 模块只能依赖公共协议、Payload、Snapshot、Capability、Topic 常量。
2. 跨模块请求使用 `commandCapability(...)` 或 `requestCapability(...)`。
3. 跨模块事件使用 `publishTopic(...)`。
4. 请求回包使用 `respondTo(...)` 和请求响应处理器。
5. 禁止新增公开直投路线来绕过能力和主题。
6. 耗时任务必须设置合理 `deadline` / `expireAt`，并在需要时发送进度、心跳或最终响应。
7. 所有跨模块 Payload 必须不可变且不含 Minecraft 活对象。

这份契约的目标是让模块边界清楚：公开入口用能力，广播事件用主题，回包关系由协议中心按请求链路托管。
