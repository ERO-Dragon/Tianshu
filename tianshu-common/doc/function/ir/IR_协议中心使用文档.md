# IR 协议中心使用文档

本文面向需要向天枢输入文本，或观察 IR 文本路由结果的模块开发者。IR 是文本修复、语音触发词匹配和 IA 仲裁请求的前置层，不是命令执行器，也不直接决定最终对话 owner。

## 1. 接入前提

调用方应作为天枢托管模块完成装配，并在装配阶段通过 `TianshuModuleAssemblyContext.protocolRuntime()` 创建自己的协议 adapter。不要从 `TianshuCoreManager` 获取完整 `ProtocolRuntime`，也不要直接持有 `IrModule`、`CommandParser` 或 IR 内部 matcher。

## 2. 稳定协议面

| 方向 | 协议 | PayloadType | Payload | PacketType |
| --- | --- | --- | --- | --- |
| 外部模块 -> IR | `ProtocolCapabilities.IR_PARSE` | `PayloadType.IR_PARSE` | `IrParsePayload` | `COMMAND` |
| ASR -> IR | `ProtocolTopics.INPUT_ASR_FINAL_TEXT` | `ASR_TEXT` | `AsrTextPayload` | `EVENT` |
| IR -> 观察者 | `ProtocolTopics.IR_RESULT` | `IR_RESULT` | `IrResultPayload` | `EVENT` |
| IR -> IA | `ProtocolCapabilities.DIALOGUE_ARBITRATE` | `DIALOGUE_ARBITRATION_REQUEST` | `DialogueArbitrationRequestPayload` | `COMMAND` |
| IR -> Presence | `ProtocolCapabilities.PRESENCE_QUERY_CONTEXT` | `PRESENCE_CONTEXT_QUERY` | `PresenceContextQueryPayload` | `REQUEST` |
| Presence -> IR | 原请求 response | `PRESENCE_CONTEXT_SNAPSHOT` | `PresenceContextSnapshotPayload` | `RESPONSE` |

外部模块通常只需要使用 `IR_PARSE`。ASR 入口、Presence 查询和 IA 仲裁是 IR 自己的链路，不应由调用方重复发送。

## 3. 提交文本

推荐发送 `COMMAND`：

```java
TianshuEnvelope envelope = EnvelopeBuilder.commandToCapability(
    "module.example",
    ProtocolCapabilities.IR_PARSE,
    PayloadType.IR_PARSE,
    new IrParsePayload(
        "酒狐帮我种地",
        "酒狐帮我种地",
        1,
        42L,
        "example:chat"
    )
).build();

protocolRuntime.submit(envelope);
```

模块 adapter 中应把 `ProtocolRuntime` 封装起来，对业务代码提供类似 `submitText(IrParsePayload)` 的窄方法；上面的直接 submit 只展示信封结构。

`IrParsePayload` 字段：

| 字段 | 类型 | 当前语义 |
| --- | --- | --- |
| `text` | `String` | 要进入 IR 的文本。IR 会 trim，并对空文本发布 no-match 结果。 |
| `rawText` | `String` | 原始文本；为空时回退到 `text`。 |
| `turnId` | `int` | 调用方会话内的轮次编号。 |
| `sessionId` | `long` | 调用方会话标识，用于贯穿 IR、IA 和后续 owner 链路。 |
| `source` | `String` | 稳定来源说明，例如 `example:chat`。 |

不要把 Minecraft 实体、物品对象或可变集合塞进 payload。文本关联的物品和实体信息由 Presence 快照提供，IR 只处理不可变协议数据。

## 4. COMMAND-only 语义

`IR_PARSE` 只接受 `PacketType.COMMAND`。解析观察结果发布到 `ProtocolTopics.IR_RESULT`，实际对话路由通过 `ProtocolCapabilities.DIALOGUE_ARBITRATE` 进入 IA。

- 不要为 `IR_PARSE` 注册 `IrResultPayload` response handler。
- 不要发送 `PacketType.REQUEST`。
- 如果需要观察结果，订阅 `IR.RESULT`，并用 `sessionId + turnId` 关联。
- `IR.RESULT` 是调试和链路可视化事件，不是 owner 授权，也不包含 IA 的最终仲裁结论。

## 5. 订阅 IR 结果

```java
public void subscribeIrResult(EnvelopeHandler handler) {
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
}
```

`IrResultPayload` 字段：

| 字段 | 语义 |
| --- | --- |
| `matched` | IR 是否产生了可路由的文本匹配。 |
| `normalizedText` | no-match 时的规范化文本；成功路由时当前可能为空。 |
| `intentType` | 当前主链路为 `DIALOGUE_ARBITRATION`。 |
| `targetCapability` | 当前实现中的匹配模块摘要，不是可以直接调用的 capability ID。 |
| `confidence` | 本次匹配中的最高置信度。 |
| `repaired` | 是否进入修复/路由语义。 |
| `reason` | 例如 `EMPTY_INPUT`、`DIALOGUE_ROUTED` 或内部路由原因码。 |
| `turnId`、`sessionId` | 与源输入关联。 |

调用方不得根据 `targetCapability` 绕过 IA 直投模块；最终 owner 只能由 IA 决定。

## 6. IR 到 IA 的路由

有可路由结果时，IR 会构造 `DialogueArbitrationRequestPayload`，并向 `ProtocolCapabilities.DIALOGUE_ARBITRATE` 发送 `COMMAND`。payload 包含修复文本、规范化文本、命中 wake word、命中物品 ID、文本侧实体引用以及源 session/turn。

IR 不直接把正文投递给 AX 或其他 participant。IA 选中 owner 后，才会向 owner 注册的 delivery capability 投递标准 `DialogueDeliveryPayload`。外部参与者的注册与 session 控制见 `../ia/IA_外部模组仲裁接入说明.md`。

## 7. Presence 查询与等待

IR 可能向 `ProtocolCapabilities.PRESENCE_QUERY_CONTEXT` 请求 `INTERACTION_CONTEXT` 和 `PLAYER_INVENTORY`，用于命名对象增强。IR 会先登记 `PresenceContextSnapshotPayload` response handler，再提交请求，并在内部超时后降级为空上下文。

调用方不需要也不应同步发送第二份 Presence 请求。不要在提交 `IR_PARSE` 后 `sleep`；IR 的协议完成和后续 topic/IA 事件是异步的。

## 8. 线程、取消与隐私

- IR handler 不要求 Minecraft 主线程；解析、Presence 等待和路由不能占用 MC 主线程。
- IR 没有独立公开的长任务取消 capability。源信封尚未处理时可使用协议中心标准取消；处理已经路由到 IA 后，应按 IA session 生命周期控制。
- 不要把玩家原始正文写入公共日志或自建 debug 文件。
- 不要依赖 IR 内部阈值、拼音评分、候选排序或 parser 类名；它们不是外部协议。
- 不要直接订阅 ASR final text 来抢占开放对话；普通对话参与方应接入 IA。

## 9. 最小接入检查表

- [ ] 通过自己的 adapter 发送 `ProtocolCapabilities.IR_PARSE`。
- [ ] 使用 `COMMAND`，不发送 IR `REQUEST`，也不等待 response payload。
- [ ] 使用稳定 `sessionId + turnId` 关联观察事件。
- [ ] 不把 `IR.RESULT.targetCapability` 当作可直投 owner。
- [ ] handler 不阻塞协议线程或 Minecraft 主线程。
- [ ] 需要对话 owner 时遵循 IA participant/session 协议。
