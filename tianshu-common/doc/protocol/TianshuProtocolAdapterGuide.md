# 天枢协议适配器使用说明

适配器是每个模块接入协议中心的边界层。它负责把模块的业务意图写成标准 `TianshuEnvelope`，也负责把模块能处理的能力、主题订阅和请求响应处理器登记给协议中心。

适配器不决定业务流程，不直接调用其他模块实现类，也不绕过协议中心建立私有通道。

## 1. 基本概念

- **协议中心**：统一收信、路由、排队、投递、取消、死信和观测的基础设施。
- **模块**：ASR、IR、IA、AX、LLM、TTS、GUI 等功能边界。
- **适配器**：模块自己的协议入口，封装发信、收信登记、响应处理和后台任务提交。
- **信封**：`TianshuEnvelope`，所有跨模块通信都必须使用它。
- **Payload**：业务内容，协议中心只做类型校验，不拆业务字段。

公开通信方式只有两类：

| 方式 | 用途 |
|---|---|
| `CAPABILITY` | 找“能做某事”的模块，适合命令和请求。 |
| `TOPIC` | 发布事件，所有订阅者都可以收到。 |

请求的返回值使用协议响应处理器，不是公开私有路线，也不应新增 `AX.LLM_RESULT` 这类伪能力来承载私有回包。

## 2. 创建模块适配器

```java
public final class MyModuleProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.my_module";
    public static final String SOURCE_ID = "module.my_module";

    public MyModuleProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }
}
```

`MODULE_ID` 是模块登记名，`SOURCE_ID` 是模块发信时写入信封的来源。通常两者相同。

## 3. 发送能力命令

命令表示“请会这个能力的模块做一件事”，通常不要求业务结果。

```java
public TianshuEnvelope speak(TtsSpeakPayload payload) {
    return commandCapability(
        ProtocolCapabilities.TTS_SPEAK,
        PayloadType.TTS_TEXT,
        payload
    );
}
```

`TTS_SPEAK` 是本地播放入口。Payload 使用 `TtsPlaybackPlacement` 表达本次播报如何插入本地播放流：

- `DROP_IF_BUSY`：忙碌时丢弃本次请求。
- `QUEUE_AFTER_SESSION`：普通排队，排到当前本地播放会话之后。
- `INSERT_AFTER_SESSION`：插到当前会话之后。
- `INSERT_AFTER_SENTENCE`：插到当前句子之后。
- `CANCEL_SENTENCE_AND_PLAY`：取消当前播放句子并播放新请求。
- `CANCEL_SESSION_AND_PLAY`：取消当前会话剩余句子并播放新请求。

适合：

- TTS 播报；
- 停止或控制某个资源；
- IA 会话控制；
- `IR_PARSE` 文本输入；
- GUI 打开/关闭这类动作意图。

## 4. 发送能力请求

请求表示“请会这个能力的模块处理，并可能给我回包”。

```java
public TianshuEnvelope requestLlm(LLMPromptRequestPayload payload) {
    return requestCapability(
        ProtocolCapabilities.LLM_REQUEST,
        PayloadType.LLM_PROMPT_REQUEST,
        payload
    );
}
```

常见请求：

- `LLM.REQUEST`
- `LLM.CACHE_MANAGE`
- `TTS_SYNTHESIZE`
- `DIALOGUE.LLM_USAGE_AUTHORIZE`

`TTS_SYNTHESIZE` 用于“只合成音频，不由 TTS 本地播放”的场景。调用方发送 `TtsSynthesisRequestPayload` 后，通过响应处理器接收一个或多个 `TtsAudioPayload / TTS_AUDIO` 响应包，之后自行决定 2D、3D、实体或方块声源播放方式。需要取消时复用 `TTS_CONTROL`：`STOP + targetRequestId` 取消指定播放或纯合成请求，空 target 表示全部停止。

如果调用方需要结果，应先构建请求信封，为该请求 `envelopeId` 登记响应处理器，再提交请求。对于 IA 仲裁这类主链路投递，普通调用方不需要结果时应使用 `COMMAND`；只有明确需要 `DIALOGUE_ARBITRATION_RESULT` 的诊断、测试或同步查询场景才使用 `REQUEST`。

## 5. 发布主题事件

主题用于广播事件。主题必须在 `ProtocolBootstrap` 或对应启动流程中注册，未注册主题会进入死信。

```java
public TianshuEnvelope publishAsrFinalText(AsrTextPayload payload) {
    return publishTopic(
        ProtocolTopics.INPUT_ASR_FINAL_TEXT,
        PayloadType.ASR_TEXT,
        payload
    );
}
```

适合：

- 输入事件；
- 生命周期事件；
- 设置变化；
- 播放状态；
- 低频 UI 贡献变化；
- 资源重载通知。

高频状态应使用短生命周期和最新态策略。模块私有逐帧 UI 状态不要通过协议中心广播。

## 6. 登记能力

模块要接收某类能力信封，必须登记能力。

```java
public void registerDialogueInput(EnvelopeHandler handler) {
    registerCapability(
        "AX.DIALOGUE_INPUT",
        PayloadType.DIALOGUE_DELIVERY,
        DialogueDeliveryPayload.class,
        BrokerType.BOUNDED_QUEUE,
        EnumSet.of(PacketType.COMMAND),
        Priority.LOW,
        CompletionPolicy.MANUAL_COMPLETE,
        handler,
        defaults()
    );
}
```

登记时必须填清楚：

| 参数 | 含义 |
|---|---|
| 能力 ID | 对外可调用的服务入口。 |
| `PayloadType` | 信封内容类型。 |
| Payload 类 | 实际 Java Payload 类型。 |
| Broker | 排队、并发、打断或主线程策略。 |
| `PacketType` | 接受命令、请求、响应、状态等哪些信件语义。 |
| 最低优先级 | 低于该优先级的信封会被拒绝。 |
| 完成策略 | 自动完成、手动完成或流式手动完成。 |
| Handler | 模块内部处理函数。 |

## 7. 订阅主题

模块要接收主题事件，必须订阅主题。

```java
public void subscribeTtsPlayback(EnvelopeHandler handler) {
    subscribeTopic(
        ProtocolTopics.TTS_PLAYBACK,
        PayloadType.TTS_PLAYBACK_STATUS,
        TtsPlaybackStatusPayload.class,
        BrokerType.STATELESS_FAST_PATH,
        EnumSet.of(PacketType.EVENT),
        Priority.LOW,
        handler
    );
}
```

订阅者只处理自己的业务，不应在主题 Handler 中直接调用其他模块实现。

## 8. 请求响应处理器

`respondTo(parent, payloadType, payload)` 会生成 `PacketType.RESPONSE`。协议中心根据响应包的 `parentId` 找到原请求的响应处理器。

一个请求可以收到多个响应包：

```text
request LLM.REQUEST
  <- response LLM_PROMPT_STREAM_CHUNK
  <- response LLM_PROMPT_STREAM_CHUNK
  <- response LLM_PROMPT_STREAM_CHUNK
  <- response LLM_PROMPT_RESULT
```

调用方应先构建请求并登记响应处理器，再提交请求，避免服务方极快返回时响应先于处理器注册到达：

```java
TianshuEnvelope request = buildRequestCapability(
    ProtocolCapabilities.LLM_REQUEST,
    PayloadType.LLM_PROMPT_REQUEST,
    payload
);

registerResponseHandler(
    request.envelopeId(),
    PayloadType.LLM_PROMPT_STREAM_CHUNK,
    LLMPromptStreamChunkPayload.class,
    BrokerType.BOUNDED_QUEUE,
    EnumSet.of(PacketType.RESPONSE),
    Priority.LOW,
    this::handleChunk
);

registerResponseHandler(
    request.envelopeId(),
    PayloadType.LLM_PROMPT_RESULT,
    LLMPromptResultPayload.class,
    BrokerType.BOUNDED_QUEUE,
    EnumSet.of(PacketType.RESPONSE),
    Priority.LOW,
    this::handleFinal
);

submitPrepared(request);
```

最终响应到达后，调用方应注销该请求的响应处理器。请求过期、模块停止或业务取消时也必须清理。

宿主卸载整个模块时会按 `moduleId` 统一清理该模块尚存的 response handler、capability 和 topic subscription。共享 capability、共享 topic 或同一请求下的其他模块 handler 会保留；业务 adapter 不应为了规避卸载竞态而创建备用注册路径。

服务方只需要回信：

```java
respondTo(parentEnvelope, PayloadType.LLM_PROMPT_RESULT, resultPayload);
```

服务方不需要知道请求方模块 ID，也不需要给请求方注册公开能力。

## 9. 父子信封

带 `parent` 的发送方法会继承 `traceId` 并设置 `parentId`：

```java
commandCapability(parent, capabilityId, payloadType, payload);
publishTopic(parent, topicId, payloadType, payload);
requestCapability(parent, capabilityId, payloadType, payload);
```

这用于表达同一业务链路中的派生关系，便于追踪、取消和清理。

## 10. 取消

```java
cancelEnvelope(targetEnvelope, "USER_CANCELLED", "用户取消");
```

取消信封仍通过协议中心处理。协议中心根据目标信封和 `CancellationScope` 更新生命周期、取消队列任务，并通知必要的取消回调。模块不应通过私有路线自行取消其他模块任务。

## 11. 后台任务

适配器提供协议中心统一执行器入口，模块不应自己创建无界线程池，也不应在 Handler 或主线程里直接执行阻塞任务。

```java
public ProtocolTaskHandle submitLlmIoTask(String envelopeId, Runnable task) {
    return submitTask(
        taskSpec(ExecutionLane.IO)
            .envelopeId(envelopeId)
            .concurrencyKey(MODULE_ID + ":llm")
            .maxConcurrency(1)
            .queueCapacity(4)
            .build(),
        task
    );
}
```

推荐通道：

| 场景 | 通道 |
|---|---|
| 普通短 CPU 任务 | `CPU` |
| 网络、文件、LLM HTTP stream | `IO` |
| 音频播放和设备交互 | `AUDIO_IO` |
| 快速 TTS 合成 | `TTS_FAST` |
| 自回归 TTS 合成 | `TTS_AUTOREGRESSIVE` |
| ASR 音频流处理 | `ASR_STREAM` |
| 模型加载和切换 | `MODEL_LOAD` |
| 长驻监控或子进程监控 | `LONG` |
| Minecraft 主线程 | `MAIN` |

## 12. AdapterDefaults

常用默认值：

| 默认值 | 适合场景 |
|---|---|
| `AdapterDefaults.standard()` | 普通异步任务。 |
| `AdapterDefaults.mainThreadUi()` | 客户端 UI 或主线程读取。 |
| `AdapterDefaults.highFrequencyFact()` | Hover、准星、Tick 等短生命周期最新态。 |

可按需调整：

```java
AdapterDefaults urgent = AdapterDefaults.standard()
    .withPriority(Priority.HIGH)
    .withTiming(5_000L, 10_000L);
```

常改项包括优先级、线程策略、投递策略、超时时间、取消范围、失败策略、并发和队列容量。

## 13. 常用 API

### 发信

| API | 用途 |
|---|---|
| `commandCapability(...)` | 发能力命令。 |
| `requestCapability(...)` | 发能力请求。 |
| `publishTopic(...)` | 发布主题事件。 |
| `submitToCapability(...)` | 需要自定义 `PacketType` 时发能力信封。 |
| `submitToTopic(...)` | 需要自定义 `PacketType` 时发主题信封。 |
| `respondTo(...)` | 回复上一封请求。 |
| `cancelEnvelope(...)` | 请求取消某个信封。 |

### 收信登记

| API | 用途 |
|---|---|
| `registerCapability(...)` | 声明模块能处理某个能力。 |
| `subscribeTopic(...)` | 声明模块订阅某个主题。 |
| `registerResponseHandler(...)` | 声明模块要接收某个请求的响应。 |
| `unregisterResponseHandlers(...)` | 清理某个请求的响应处理器。 |

## 14. 常见错误

1. **把回包做成公开能力。** 回包应使用响应处理器，公开能力只表达可被外部调用的服务入口。
2. **把事件做成能力。** 多个模块都可能关心的状态变化应是主题。
3. **PayloadType 和 Payload 类不匹配。** 协议中心会拒绝投递。
4. **高频状态用普通队列。** 会造成队列堆积，应使用短生命周期和最新态策略。
5. **Handler 里跑阻塞任务。** 应提交到协议中心执行通道。
6. **携带 Minecraft 活对象。** 跨模块 Payload 必须是不可变快照。
7. **绕过协议中心调用其他模块。** 这会破坏生命周期、取消、观测和资源仲裁。

适配器的边界要保持简单：公开调用走能力，广播事件走主题，请求结果走响应处理器。
