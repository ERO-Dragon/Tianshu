# IR 协议中心使用文档

本文说明 IR 当前稳定的协议输入和输出。IR 不提供外部文本提交 capability；标准输入只来自 ASR final text。

## 1. 稳定协议面

| 方向 | 协议 | PayloadType | Payload | PacketType |
| --- | --- | --- | --- | --- |
| ASR -> IR | `ProtocolTopics.INPUT_ASR_FINAL_TEXT` | `PayloadType.ASR_TEXT` | `AsrTextPayload` | `EVENT` |
| IR -> IA/观察者 | `ProtocolTopics.IR_RESULT` | `PayloadType.IR_RESULT` | `IrResultPayload` | `EVENT` |

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
| `rawText` | ASR 原始识别文本，未经任何修复。 |
| `objectMatches` | `List<IrObjectMatch>`，每个命中的具名对象一条证据。 |
| `moduleMatches` | `List<IrModuleMatch>`，每个命中的模块触发词一条证据。 |
| `turnId` / `sessionId` | 与 ASR 输入关联。 |
| `timestampMillis` | IR 分析完成时间。 |

`IrObjectMatch` 中：

- `spokenWord` 是玩家实际说出的词，提示词直接展示它；
- `registryId` 是解析到的注册表 id（如 `minecraft:diamond_hoe`）；
- `registryKind` 是开放字符串（`item` / `entity` / `block` / `dimension` / `biome` / `effect` 以及模组注册的其它类别），消费方必须自行兜底，不能假设只有固定几种；
- `languageKey` 是由 id 与类别派生的翻译键，类别未知时为空——猜出来的键比没有更糟；
- `entity()` 按 `registryKind` 判定是否为实体，与 id 的形状无关。

`IrModuleMatch` 中：

- `moduleId` 表示该触发词的注册来源，不代表最终 owner；
- `spokenWord` 是玩家说出的词，`registeredWord` 是该模块注册的词；
- 每个命中词一条；模块之间按匹配质量降序排列，匹配质量只用于排序、不进入 payload，也不替代 IA 的 claim 来源档或 participant priority。

## 3. IA 消费语义

IA 直接订阅 `ProtocolTopics.IR_RESULT`。标准语音链路中，IR 不调用 `ProtocolCapabilities.DIALOGUE_ARBITRATE`。

IA 会结合：

- IR 的具名对象命中（`objectMatches`）与模块触发词命中（`moduleMatches`）；
- 开口时冻结的交互上下文摘要（映迹 `PRESENCE.INTERACTION_CONTEXT` 的最近一次推送）；
- participant claim rules，以及它们实际命中的条件来源（上下文档 / 醒词档）；
- participant priority；

决定 owner 或拒绝本轮输入。没有硬 claim 时，上一个中标人的租期未过期就继续归它，否则归默认承接者。没有触发词命中不代表 IR 丢弃文本，也不代表 IA 必须接受文本。

`DIALOGUE_ARBITRATE` 仍属于 IA 自己的受信服务端口，用于明确需要直接请求仲裁的模块、诊断和测试，不是 IR 的输出方式。

## 4. 外部输入边界

当前没有 `IR_PARSE` capability，也没有 `IrParsePayload`。外部模块不应构造一条与 ASR 并行的私有 IR 输入链。

如果未来增加聊天、文本框或其他输入来源，应先定义统一输入事件和生命周期语义，再接入 IR；不要通过直接调用 IR 实现类临时接入。

## 5. Presence 查询（已退出）

IR 不再请求 `ProtocolCapabilities.PRESENCE_QUERY_CONTEXT`，也不再消费 `PresenceContextSnapshotPayload`。命名对象增强已由辅星侧自采，`IrNamedObjectEnhancer` 只使用本地索引；代价是裸指代词（"用这个"）不再结合手持物消歧，其余不变。

因此 IR 的解析不等待任何外部上下文，Presence 缺失也不会影响它发布文本分析结果。仲裁所需的交互上下文由映迹推送、IA 订阅（`PRESENCE.INTERACTION_CONTEXT`）。

## 6. 线程与隐私

- IR handler 和索引初始化触发不得占用 Minecraft 主线程；索引未就绪时解析立即降级，不等待构建完成。
- 订阅者不得在 `IR_RESULT` handler 中执行阻塞 IO、模型推理或游戏主线程工作。
- 不要根据 `moduleMatches.moduleId` 绕过 IA 投递正文。
- 不要自行把玩家正文写入公共日志或私有 debug 文件。
- IR 诊断由 `module.ir` 设置控制，并由宿主集中异步落盘。

## 7. 接入检查表

- [ ] 使用自己的 adapter 和 `ModuleRuntimeAccess`。
- [ ] 只在确实需要观察分析结果时订阅 `ProtocolTopics.IR_RESULT`。
- [ ] 使用 `sessionId + turnId` 关联链路。
- [ ] 不把触发词命中当成最终 owner。
- [ ] 实体仲裁只读 `objectMatches` 中 `entity()` 为真的条目，不按 id 形状猜类别。
- [ ] 不阻塞协议线程或 Minecraft 主线程。
- [ ] 需要处理正文时，通过 IA participant 和 delivery 契约接入。
