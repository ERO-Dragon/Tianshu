# IR 模块架构说明

## 1. 模块定位

IR 模块是天枢语音/文本链路中的输入修复与文本特征提取层。它接收 ASR final text 或显式的 `IR_PARSE` command，输出 IA 仲裁所需的文本侧结果：

- 修复后的自然语言正文。
- 归一化/过滤后的文本视图。
- 命中的 wake word。
- 命中的物品结构化 ID。
- 用于调试和观测的 `IR_RESULT`。

跨模块调用方应以 [IR 协议中心使用文档](IR_协议中心使用文档.md) 为稳定接入契约；本文件只解释内部职责和算法边界。

IR 不决定本轮对话归属，不向业务模块直投玩家正文，也不执行具体游戏动作。当前对话 owner 由 IA 统一仲裁；IR 的职责是在进入 IA 前，把文本整理成稳定、可判断、可传递的候选输入。

## 2. 当前边界

IR 当前主要由以下部分组成：

- `IrModule`：主编排入口。
- `IrProtocolAdapter`：协议收发封装。
- `IrInputMapper` / `IrInputPreprocessor`：输入映射与预处理。
- `IrNamedObjectEnhancer` / `IrNamedObjectEnhancementResult`：命名对象（物品、实体）名称修复与结构化 ID 提取。
- `IRCommandService`：命名对象索引的构建、快照和读写生命周期。
- `CommandParser`：命令片段切分、候选采纳和解析结果编排。
- `CommandCandidateRanker`：基于索引的候选检索、拼音相似度评分与排序。
- `CommandTextRepairer`：只根据已排序候选执行保守的命名对象文本修复。
- `IrWakeWordEnhancer`：基于已注册 wake word 的保守修复。
- `IrVoiceTriggerIndexer` / `IrVoiceTriggerMatcher`：wake word / extra word 命中特征提取。
- `IrDialogueArbitrationRequestMapper`：将 IR 结果映射为 `DialogueArbitrationRequestPayload`。
- `IrRoutingPolicy`：决定非空输入进入 IA 仲裁，空输入拒绝。

IR 与 IA 的边界是：

```text
ASR / IR_PARSE
  ↓
IR 修复文本并提取文本特征
  ↓
DialogueArbitrationRequestPayload
  ↓
IA 仲裁 owner 并定向投递 DialogueDeliveryPayload
```

## 3. 输入与预处理

不同入口会先映射成 `IrInputText`：

- ASR final text。
- `IR_PARSE` command 输入。

`IrInputPreprocessor` 会产生两个文本视图：

- `voiceText`：保留完整句子，用于 wake word 修复和匹配。
- `filteredText`：去掉填充词、边界词等噪声，用于物品增强和结构化提取。

`normalizedText` 在 IA payload 中使用 `filteredText`，表示 IR 处理后的归一化文本视图；它不是 ASR 原文。ASR 原文通过 `IrInputText.rawText()` 保留在 IR 内部输入模型中。

## 4. 物品增强

命名对象增强通过 `IrNamedObjectEnhancer` 抽象完成。增强结果由 `IrNamedObjectEnhancementResult` 承载：

- `repairedText`：修复后的自然语言正文。
- `matchedItemNames`：命中物品的显示名，主要用于 UI、上下文提示或调试。
- `matchedItemIds`：命中物品的结构化 ID，用于 IA claim 判断和 owner 模块处理。
- `matched`：是否命中。

`repairedText` 只做自然语言修复，不把正文改成资源 ID。

`IRCommandService` 内部的索引检索、候选评分和文本修复保持为包内协作：

- `CommandParser` 只负责片段边界和候选采纳，不承载评分或文本替换细节。
- `CommandCandidateRanker` 复用单次解析的 LCS 工作区，避免在同一解析流程中重复分配评分缓冲区。
- `CommandTextRepairer` 只消费已排序候选，不重新查询索引，保证“候选判断”和“文本修复”可以独立维护。
- `CommandParserPolicy` 是包内不可变算法策略；它不承载语言内容，也不作为玩家 GUI 配置。

这些 core 协作类不依赖 Minecraft 或 NeoForge 类型，也不调度或占用 MC 主线程。它们是同步、纯计算组件；调用方应继续通过协议执行路径运行解析，而不是把大词典检索放进游戏 tick 热路径。

示例：

```text
ASR 原文：下届合金能做什么
repairedText：下界合金能做什么
matchedItemIds：minecraft:netherite_ingot
```

NeoForge 侧可通过客户端增强器接入 Minecraft 物品字典、上下文和本地化信息；common 层只接收修复结果和结构化 ID，不依赖 Minecraft 活对象。

NeoForge 的 `ClientNamedObjectIndexManager` 是客户端进程级资源，不是世界会话资源：

- `TianshuClient.init()` 在游戏启动阶段调用 `initializeAsync()`，由单一 `Tianshu-IR-Index` worker 构建或恢复索引。
- 世界登录/退出不重建也不清空该索引，不同世界复用同一份物品/实体注册表索引。
- 客户端资源 reload 通过同一 worker 串行重载关键词并重建索引，避免与正在进行的构建并发写入。
- `IRCommandService` 的读写锁允许协议解析读取当前完整快照；重建提交新快照时不会暴露半成品。
- 最终客户端 shutdown 才关闭 index worker。

该 worker 只拥有 IR 索引构建和缓存 IO，不承担协议 handler 或通用功能调度；IR 请求解析仍由 Protocol executor lane 执行。

## 5. Wake Word 修复与匹配

IR 会从协议中心的 `VoiceTriggerRegistry` 读取当前注册的 wake word / extra word，并编译为轻量索引。

处理顺序是：

```text
物品修复后的文本
  ↓
IrWakeWordEnhancer 修复 wake word
  ↓
IrVoiceTriggerMatcher 提取 matchedWakeWords / matchedExtraWords
```

`IrWakeWordEnhancer` 只修复注册表中的 wake word，不修复 extra word。它使用拼音 token 相似度做保守修复，阈值比物品修复更严格，并跳过分数接近的重叠歧义候选，避免把普通正文误改成唤醒词。

示例：

```text
注册 wake word：酒狐
输入：九狐帮我种地
修复后：酒狐帮我种地
matchedWakeWords：酒狐
```

Wake word 命中不代表 IR 直接把正文投给某个模块。命中的 wake word 会进入 `DialogueArbitrationRequestPayload.matchedWakeWords`，由 IA 的 claim engine 结合 participant claim profile、priority、attention 衰减和上下文快照决定 owner。

## 6. 仲裁请求映射

IR 对非空输入统一提交 IA 仲裁：

```text
ProtocolCapabilities.DIALOGUE_ARBITRATE
PayloadType.DIALOGUE_ARBITRATION_REQUEST
DialogueArbitrationRequestPayload
```

payload 中来自 IR 的字段包括：

- `repairedText`：最终修复后的自然语言正文，包含物品修复和 wake word 修复。
- `normalizedText`：预处理后的过滤文本视图。
- `matchedWakeWords`：IR 命中的 wake word。
- `matchedItemIds`：IR 提取出的物品结构化 ID。
- `sourceSessionId` / `turnId`：用于 IA 关联 ASR 说话开始时冻结的上下文快照。

IR 不在仲裁请求 payload 里提供手持物、身上装备、准星实体、附近实体、按键状态或维度信息。IR 如需物品上下文增强，会通过 `PRESENCE.QUERY_CONTEXT` 显式请求 `INTERACTION_CONTEXT` 和 `PLAYER_INVENTORY`；IA 仲裁仍会按自己的规则重新请求所需 Presence 字段。

## 7. 结果观测

IR 会发布 `IR_RESULT` 作为观测事件。它用于调试和链路可视化，不是业务执行结果，也不是 owner 授权结果。

当前语义：

- 非空输入进入 IA 后，`intentType = DIALOGUE_ARBITRATION`，`reason = DIALOGUE_ROUTED`。
- 空输入不会进入 IA，`reason = EMPTY_INPUT`。
- `targetCapability` 仅作为观测摘要，记录 IR 文本特征命中的 module id 列表；最终 owner 仍以 IA 的 session/delivery 为准。

## 8. 典型执行顺序

```text
原始输入
  ↓
IrInputMapper
  ↓
IrInputPreprocessor
  ├─ voiceText
  └─ filteredText
        ↓
IrNamedObjectEnhancer
  ├─ repairedText
  └─ matchedItemIds
        ↓
IrWakeWordEnhancer
        ↓
IrVoiceTriggerMatcher
  ├─ matchedWakeWords
  └─ matchedExtraWords
        ↓
DialogueArbitrationRequestPayload
        ↓
IA
```

## 9. 模块边界总结

IR 当前的角色可以概括为：

- 不是业务 owner。
- 不是 IA。
- 不是协议中心。
- 不是 ASR / LLM / TTS。
- 不向外部业务模块直投玩家正文。

IR 是输入修复和文本特征提取层；IA 是对话归属仲裁层。后续新增文本特征时，应优先保持这个边界：IR 只产出可仲裁的文本侧事实，owner 选择和正文投递仍由 IA 完成。
