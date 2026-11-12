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
IntakeTopics.INPUT_ASR_FINAL_TEXT
PayloadType.of("ASR_TEXT")
AsrTextPayload
```

IR 完成处理后发布结构化分析事件：

```text
IntakeTopics.IR_RESULT
PayloadType.of("IR_RESULT")
IrResultPayload
```

IA 订阅该 topic，并结合 participant claim、开口时冻结的交互上下文摘要和 participant priority 决定 owner。IR 不选择 owner，不调用 IA 仲裁 capability，也不向 AX、LLM、TTS 或其他业务模块投递正文。

## 2. 当前执行链路

```text
ASR final text
  ↓
IrInputMapper
  ↓
IrInputPreprocessor
  ├─ voiceText：保留完整句子（展示句的基底）
  └─ filteredText：去除填充词和边界噪声（只用于检索）
  ↓
IrNamedObjectEnhancer
  ├─ 在整句上修复物品、实体名称（fillers 不进入展示正文）
  └─ matches：每个命中对象一条 IrObjectMatch（成对构建）
  ↓
IrWakeWordEnhancer（作用在已修复的整句上）
  ↓
IrTriggerMatcher（token 序列匹配）
  └─ moduleMatches：每个命中触发词一条（匹配质量只用于排序）
  ↓
IrResultPayload
  ↓ topic
IA
```

空文本不会发布 `IR_RESULT`，只产生受全局 Debug 开关控制的 `EMPTY_INPUT` 诊断。

## 3. 模块组成

- `AsrTextRepairer`：在识别调用栈内执行一次文本修复（原 IR 模块的入口）。
- `AsrProtocolAdapter`：在识别后发布 `IR_RESULT`，修复结果随 ASR 调用栈发出。
- `IrInputMapper` / `IrInputPreprocessor`：输入映射和双文本视图构建。
- `IrNamedObjectEnhancer`：自然语言名称修复和结构化物品、实体 ID 提取；结果只由“整句修复文本 + 成对匹配列表”组成。
- `IrWakeWordEnhancer`：只对已注册 wake word 做保守修复。
- `IrTriggerIndexer` / `IrTriggerMatcher`：编译并匹配 wake/extra 词组（token 序列，不做拼音子串包含）。
- `IRCommandService` / `CommandParser` / `CommandCandidateRanker` / `CommandTextRepairer`：命名对象索引、候选评分和文本修复。
- `IRIndex` / `IndexBuilder` / `IRSnapshot` / `IRCacheStore`：不可变索引的构建、快照序列化与磁盘缓存。
- `IntentKeywordLoader`：随包关键词表（分隔词 / 否定词 / 填充词 / 边界词）及其资源包覆盖入口。

IR 不再包含独立 routing policy，也不包含 IA request mapper。发布分析事实就是 IR 的处理终点。

## 4. 文本预处理

`IrInputPreprocessor` 生成：

- `voiceText`：用于命名对象修复、wake word 修复和最终自然语言正文。
- `filteredText`：用于结构化检索，并作为 `IrResultPayload.normalizedText`。

`replaceHomophones()` 当前只做基础清理，没有实现通用同音词纠正。文档和 UI 不应把同音词纠正描述为现有能力。

关键词表（`ir_intent_keywords.json`）随模块以**绝对资源路径**发布，并由 `IntentKeywordLoader` 在首次使用时读取；资源包可用同名文件覆盖。解析时会丢弃空串、空白和 null 项：这些值会被 parser 当作字面分隔符，空分隔符会让拆分循环不前进。加载或覆盖失败时保留上一份可用表，不会把整张表清空。

## 5. 命名对象增强

增强结果是一对事实，而不是四组平行列表：

- `repairedText`：整句修复文本。对象词在**整句**上修复（匹配在 filtered 视图上做，两条视图不同时对整句再跑一次修复），因此填充词和边界词不会进入展示正文。
- `matches`：每个命中对象一条 `IrObjectMatch`，由增强器直接成对构建。某一个 id 没有显示名时只丢这一条，不会让后面的 id 与名字错位；两个 id 同名也仍是两条。

发布时 `spokenWord` 是对词典命中的词，`registryKind` 记录类别，`languageKey` 由类别与 id 派生、类别未知时为空。

资源 ID 不会替换进玩家正文。索引检索和文本替换均为 common 纯计算逻辑，不依赖 Minecraft 或 NeoForge 活对象。拼音统一由 pinyin4j 提供：此前随包的硬编码表存在错位（“七”读作 er、“上”读作 bu、“下”读作 yu），会把无关物品名映射成同音，且覆盖了库的正确结果，现已删除。

索引状态收口为一个不可变 `IRIndex`（词典映射、别名 token、倒排池），由 `IRCommandService` 以单个引用发布：rebuild/restore/clear 换整个索引，解析在读锁内取当前引用，不存在“静态数组被另一个实例替换”的半成品状态。`IRBaseUtils` 只保留 tokenize/joinTokens/buildGram/hash 等纯函数。

客户端索引由 `TianshuClientRuntime` 在客户端启动阶段异步初始化：

- 单独使用 `Tianshu-IR-Index` worker；
- 缓存位于 `config/Tianshu/module/asr/index`；
- 世界退出和重新进入不重建索引；
- resource reload 通过同一 worker 串行重建；
- 客户端关闭时释放 worker 并清空索引；
- 读写锁保证解析只读取完整快照。

缓存文件遵守：

- 写入先落临时文件再原子替换，崩溃不会留下半份缓存；
- 读取时每个数组长度都以文件大小为界校验，损坏文件按普通读取失败处理；
- 快照转索引时再校验结构一致性（平行数组等长、倒排区间在池内、池内 id 在词典范围内）；任何校验失败都视为坏缓存，**删除并重建**，而不是每次启动都读同一个坏文件；
- 指纹包含索引算法版本（`INDEX_SCHEMA_VERSION`）：tokenizer、拼音或 gram 规则变化后旧快照自动失效，不会静默复用旧算法结果。

索引构建和解析不得放入 Minecraft 主线程或 tick 热路径。首次解析发现索引未就绪时只触发后台初始化并返回未就绪结果，不同步等待构建完成；后续输入在快照可用后自动获得命名对象增强。

## 6. Wake Word 与 Extra Word

IR 从共享 `TriggerRegistry` 读取各模块注册的词组。

`IrWakeWordEnhancer` 只修复 wake word，不修复 extra word。`IrTriggerMatcher` 对每个命中的触发词产出一条：

```text
moduleId
spokenWord
registeredWord
kind
```

一个模块命中多个词就是多条。匹配质量（命中词数 / 注册词数）只用于给多条命中排序——命中更多的模块排在前面——不进入 payload，也不替代 IA 的 claim 来源档或 participant priority。

语义边界：

- 触发词命中是 IA 可用的仲裁证据之一；
- `kind` 区分 wake（能唤醒模块的词）与 extra（只辅助识别的词）；extra word 命中同样会出现在 `moduleMatches` 里，但 IA 的唤醒词条件只接受 `kind=wake`，因此 extra word 不会形成 claim；
- IR 不根据 participant priority 排序或选择目标；priority 只由 IA 在有效 claim 之间使用。

## 7. IR_RESULT 契约

`IrResultPayload` 字段：

| 字段 | 语义 |
| --- | --- |
| `repairedText` | 完成命名对象和 wake word 修复后的自然语言正文。 |
| `normalizedText` | 预处理后的过滤文本视图。 |
| `rawText` | ASR 原始识别文本，未经修复。 |
| `objectMatches` | 每个命中具名对象一条：`spokenWord` / `registryId` / `registryKind` / `languageKey`。 |
| `moduleMatches` | 每个命中触发词一条：`moduleId` / `spokenWord` / `registeredWord`。 |
| `turnId` / `sessionId` | 与 ASR 输入段关联。 |
| `timestampMillis` | IR 完成分析并发布的时间。 |

该事件是 IA 的标准业务输入，不是调试摘要，也不是 owner 授权结果。外部观察者可以订阅，但不能根据 `moduleId` 绕过 IA 直投正文。

## 8. Presence 与异步边界（已退出）

IR 不再向映迹查询上下文：命名对象增强改为在天枢侧自采，`AsrTextRepairer` 的注释说明了代价——裸指代词（“用这个”）不再结合手持消歧，其余不变。

因此 IR 的解析全程不等待外部上下文，`IR_RESULT` 的发布时间只取决于识别与本地解析。仲裁所需的上下文由映迹通过 `PRESENCE.INTERACTION_CONTEXT` 推送、由 IA 消费（见 IA 文档）。

模块 stop/destroy 时会先停止接收输入，不会在退出世界期间继续发布过期 `IR_RESULT`。同一实例重新 prepare 时恢复接收；重新装配实例时只处理新的 ASR 输入。

## 9. 边界总结

- ASR 负责产生 final text。
- IR 负责文本修复和结构化事实提取。
- IA 负责预中标人、claim、租期、priority、owner 和 session。
- Protocol Center 负责 topic、capability、线程调度和投递。
- 目标功能模块只接收 IA 授权后的 `DialogueDeliveryPayload`。

新增 IR 能力时应优先扩展 `IrResultPayload` 中的事实，不应在 IR 内增加 owner 判断、定向业务投递或独立线程池。
