# IR 协议中心使用文档

本文说明 IR 当前稳定的协议输入和输出。IR 不提供外部文本提交 capability；标准输入只来自 ASR final text。

## 1. 稳定协议面

| 方向 | 协议 | PayloadType | Payload | PacketType |
| --- | --- | --- | --- | --- |
| ASR -> IR | `ProtocolTopics.INPUT_ASR_FINAL_TEXT` | `PayloadType.ASR_TEXT` | `AsrTextPayload` | `EVENT` |
| IR -> IA/观察者 | `ProtocolTopics.IR_RESULT` | `PayloadType.IR_RESULT` | `IrResultPayload` | `EVENT` |
| IR -> Presence | `ProtocolCapabilities.PRESENCE_QUERY_CONTEXT` | `PRESENCE_CONTEXT_QUERY` | `PresenceContextQueryPayload` | `REQUEST` |
| Presence -> IR | 原请求 response | `PRESENCE_CONTEXT_SNAPSHOT` | `PresenceContextSnapshotPayload` | `RESPONSE` |

模块装配仍通过 `TianshuModuleAssemblyContext.moduleRuntime()` 提供的 `ModuleRuntimeAccess` 创建窄 adapter。不要持有 `IrModule`、`CommandParser` 或完整 `ProtocolRuntime`。

## 2. 订阅 IR 结果

需要观察 IR 分析结果的模块可以订阅：

```java
subscribeTopic(
    ProtocolTopics.IR_RESULT,
    PayloadType.IR_RESULT,
    IrResultPayload.class,
    BrokerType.BOUNDED_QUEUE,
    EnumSet.of(PacketType.EVENT),
    Priority.LOW,
    CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
    handler,
    defaults()
);
```

`IrResultPayload` 字段：

| 字段 | 语义 |
| --- | --- |
| `repairedText` | 最终修复后的自然语言正文。 |
| `normalizedText` | 预处理后的过滤文本。 |
| `voiceMatches` | `List<VoiceTriggerMatch>`，保留 moduleId、wake、extra 和 confidence。 |
| `matchedItemIds` | 命中的物品 ID。 |
| `matchedEntityTypeIds` | 命中的实体类型 ID。 |
| `turnId` / `sessionId` | 与 ASR 输入关联。 |
| `timestampMillis` | IR 分析完成时间。 |

`VoiceTriggerMatch` 中：

- `matchedWakeWords` 是 IA 可使用的仲裁证据；
- `matchedExtraWords` 当前只作为保留字段传递，不形成 IA claim；
- `moduleId` 表示该词组的注册来源，不代表最终 owner；
- `confidence` 是 IR 匹配质量，不替代 IA 的 claim strength 或 participant priority。

## 3. IA 消费语义

IA 直接订阅 `ProtocolTopics.IR_RESULT`。标准语音链路中，IR 不调用 `ProtocolCapabilities.DIALOGUE_ARBITRATE`。

IA 会结合：

- IR 的 wake word、物品和实体文本事实；
- ASR speaking 阶段冻结的 Presence 上下文；
- 当前实时 Presence 上下文；
- participant claim rules；
- attention 状态；
- participant priority；

决定 owner 或拒绝本轮输入。没有 wake word 不代表 IR 丢弃文本，也不代表 IA 必须接受文本。

`DIALOGUE_ARBITRATE` 仍属于 IA 自己的受信服务端口，用于明确需要直接请求仲裁的模块、诊断和测试，不是 IR 的输出方式。

## 4. 外部输入边界

当前没有 `IR_PARSE` capability，也没有 `IrParsePayload`。外部模块不应构造一条与 ASR 并行的私有 IR 输入链。

如果未来增加聊天、文本框或其他输入来源，应先定义统一输入事件和生命周期语义，再接入 IR；不要通过直接调用 IR 实现类临时接入。

## 5. Presence 查询

IR 可能请求 `INTERACTION_CONTEXT` 和 `PLAYER_INVENTORY`，用于命名对象增强。调用方无需重复查询 Presence，也不应等待或阻塞 IR。

查询在 300ms 后降级为空上下文。Presence 缺失不会阻止 IR 发布文本分析结果。

## 6. 线程与隐私

- IR handler、索引等待和 Presence 查询不得占用 Minecraft 主线程。
- 订阅者不得在 `IR_RESULT` handler 中执行阻塞 IO、模型推理或游戏主线程工作。
- 不要根据 `voiceMatches.moduleId` 绕过 IA 投递正文。
- 不要自行把玩家正文写入公共日志或私有 debug 文件。
- IR 诊断由 `module.ir` 设置控制，并由宿主集中异步落盘。

## 7. 接入检查表

- [ ] 使用自己的 adapter 和 `ModuleRuntimeAccess`。
- [ ] 只在确实需要观察分析结果时订阅 `ProtocolTopics.IR_RESULT`。
- [ ] 使用 `sessionId + turnId` 关联链路。
- [ ] 不把 wake match 当成最终 owner。
- [ ] 不给 extra word 添加当前不存在的仲裁语义。
- [ ] 不阻塞协议线程或 Minecraft 主线程。
- [ ] 需要处理正文时，通过 IA participant 和 delivery 契约接入。
