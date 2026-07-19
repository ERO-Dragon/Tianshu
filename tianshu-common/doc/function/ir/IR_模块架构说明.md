# IR 模块架构说明

### 快速摘要

- 文本整理

  - IR 接收 ASR 发布的最终文本，保留玩家完整表达，同时清理填充词和句子边界噪声。
  - 根据当前名称索引修复物品名、实体名和容易识别错误的专有词，形成便于后续理解的修复文本。

- 特征提取

  - 识别文本中命中的唤醒词，并保留对应模块、命中词和可信度。
  - 提取文本中明确提到的物品与实体，并保留稳定的物品 ID 和实体类型 ID。
  - extra word 当前只作为扩展字段和辅助识别信息保留，不直接产生仲裁条件。

- 结果发布

  - 每条有效输入最终汇总为修复文本、规范化文本、语音词命中、物品命中和实体命中。
  - IR 通过 `IR_RESULT` topic 发布这份结构化结果，IA 再结合参与方声明和游戏情境决定本轮由谁接手。

## 1. 模块定位

IR 是语音链路中的文本修复与文本特征提取层。当前唯一业务输入是 ASR 发布的 final text：

```text
ProtocolTopics.INPUT_ASR_FINAL_TEXT
PayloadType.ASR_TEXT
AsrTextPayload
```

IR 完成处理后发布结构化分析事件：

```text
ProtocolTopics.IR_RESULT
PayloadType.IR_RESULT
IrResultPayload
```

IA 订阅该 topic，并结合 participant claim、Presence 上下文、attention 和 participant priority 决定 owner。IR 不选择 owner，不调用 IA 仲裁 capability，也不向 AX、LLM、TTS 或其他业务模块投递正文。

## 2. 当前执行链路

```text
ASR final text
  ↓
IrInputMapper
  ↓
IrInputPreprocessor
  ├─ voiceText：保留完整句子
  └─ filteredText：去除填充词和边界噪声
  ↓
IrNamedObjectEnhancer
  ├─ 修复物品、实体名称
  ├─ matchedItemIds
  └─ matchedEntityTypeIds
  ↓
IrWakeWordEnhancer
  ↓
IrVoiceTriggerMatcher
  ├─ moduleId
  ├─ matchedWakeWords
  ├─ matchedExtraWords
  └─ confidence
  ↓
IrResultPayload
  ↓ topic
IA
```

空文本不会发布 `IR_RESULT`，只产生受模块诊断开关控制的 `EMPTY_INPUT` 诊断。

## 3. 模块组成

- `IrModule`：处理流程编排和异步 Presence 查询。
- `IrProtocolAdapter`：订阅 ASR final text、发布 IR result、查询 Presence。
- `IrInputMapper` / `IrInputPreprocessor`：输入映射和双文本视图构建。
- `IrNamedObjectEnhancer`：自然语言名称修复和结构化物品、实体 ID 提取。
- `IrWakeWordEnhancer`：只对已注册 wake word 做保守修复。
- `IrVoiceTriggerIndexer` / `IrVoiceTriggerMatcher`：编译并匹配 wake/extra 词组。
- `IRCommandService` / `CommandParser` / `CommandCandidateRanker` / `CommandTextRepairer`：命名对象索引、候选评分和文本修复。

IR 不再包含独立 routing policy，也不包含 IA request mapper。发布分析事实就是 IR 的处理终点。

## 4. 文本预处理

`IrInputPreprocessor` 生成：

- `voiceText`：用于命名对象修复、wake word 修复和最终自然语言正文。
- `filteredText`：用于结构化检索，并作为 `IrResultPayload.normalizedText`。

`replaceHomophones()` 当前只做基础清理，没有实现通用同音词纠正。文档和 UI 不应把同音词纠正描述为现有能力。

## 5. 命名对象增强

增强结果包含：

- `repairedText`：修复后的自然语言正文。
- `matchedItemNames` / `matchedEntityNames`：内部匹配和诊断信息。
- `matchedItemIds` / `matchedEntityTypeIds`：发布给 IA 的结构化事实。

资源 ID 不会替换进玩家正文。索引检索、拼音评分和文本替换均为 common 纯计算逻辑，不依赖 Minecraft 或 NeoForge 活对象。

客户端索引由 `TianshuClientRuntime` 在客户端启动阶段异步初始化：

- 单独使用 `Tianshu-IR-Index` worker；
- 缓存位于 `config/Tianshu/cache`；
- 世界退出和重新进入不重建索引；
- resource reload 通过同一 worker 串行重建；
- 客户端关闭时释放 worker；
- 读写锁保证解析只读取完整快照。

索引构建和解析不得放入 Minecraft 主线程或 tick 热路径。首次解析发现索引未就绪时只触发后台初始化并返回未就绪结果，不同步等待构建完成；后续输入在快照可用后自动获得命名对象增强。

## 6. Wake Word 与 Extra Word

IR 从共享 `VoiceTriggerRegistry` 读取各模块注册的词组。

`IrWakeWordEnhancer` 只修复 wake word，不修复 extra word。`IrVoiceTriggerMatcher` 将每组命中保留为 `VoiceTriggerMatch`：

```text
moduleId
matchedWakeWords
matchedExtraWords
confidence
```

语义边界：

- wake word 是 IA 可以使用的强仲裁证据之一；
- extra word 当前只是保留的扩展字段和 ASR 热词来源；
- extra word 当前不形成 IA claim，也不改变 owner；
- IR 不根据 participant priority 排序或选择目标；priority 只由 IA 在有效 claim 之间使用。

## 7. IR_RESULT 契约

`IrResultPayload` 字段：

| 字段 | 语义 |
| --- | --- |
| `repairedText` | 完成命名对象和 wake word 修复后的自然语言正文。 |
| `normalizedText` | 预处理后的过滤文本视图。 |
| `voiceMatches` | 按模块分组的 wake/extra 命中和 confidence。 |
| `matchedItemIds` | 文本中命中的物品资源 ID。 |
| `matchedEntityTypeIds` | 文本中命中的实体类型 ID。 |
| `turnId` / `sessionId` | 与 ASR 输入段关联。 |
| `timestampMillis` | IR 完成分析并发布的时间。 |

该事件是 IA 的标准业务输入，不是调试摘要，也不是 owner 授权结果。外部观察者可以订阅，但不能根据 `moduleId` 绕过 IA 直投正文。

## 8. Presence 与异步边界

IR 可请求：

```text
ProtocolCapabilities.PRESENCE_QUERY_CONTEXT
INTERACTION_CONTEXT
PLAYER_INVENTORY
```

Presence 查询超时为 300ms。超时、失败或没有 provider 时，IR 使用空上下文继续分析。查询等待和后续解析运行在协议执行路径，不占用 Minecraft 主线程。

模块 stop/destroy 时会先停止接收输入，注销并丢弃尚未完成的 Presence 查询，不会在退出世界期间继续发布过期 `IR_RESULT`。同一实例重新 prepare 时恢复接收；重新装配实例时只处理新的 ASR 输入。

IR 使用 Presence 只增强命名对象识别；IA 会按自身 participant claim 重新规划并获取仲裁所需上下文。

## 9. 边界总结

- ASR 负责产生 final text。
- IR 负责文本修复和结构化事实提取。
- IA 负责 claim、attention、priority、owner 和 session。
- Protocol Center 负责 topic、capability、线程调度和投递。
- 目标功能模块只接收 IA 授权后的 `DialogueDeliveryPayload`。

新增 IR 能力时应优先扩展 `IrResultPayload` 中的事实，不应在 IR 内增加 owner 判断、定向业务投递或独立线程池。
