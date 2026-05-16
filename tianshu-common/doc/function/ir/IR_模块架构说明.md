# IR 模块架构说明

## 1. 模块定位

IR 模块是天枢语音指令链路中的语音触发与物品增强编排层，负责把 ASR 文本或兼容的 IR 解析输入，转换为可用于模块触发的标准语义，并向命中的模块转发 `VOICE_TRIGGER` 信封。

IR 不承担协议中心职责，不作为业务路由大脑，也不直接执行具体游戏动作。它的职责是：

- 统一输入
- 做第一阶段文本预处理
- 做物品增强与同音词修复桥接
- 做 voice trigger 命中
- 发送带上下文的触发包
- 发布 IR 结果观测事件

## 2. 当前边界

IR 当前主要由以下部分组成：

- [IrModule](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrModule.java)
- [IrModuleInstaller](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrModuleInstaller.java)
- [IrProtocolAdapter](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrProtocolAdapter.java)
- [IrVoiceTriggerIndexer](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrVoiceTriggerIndexer.java)
- [IrVoiceTriggerMatcher](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrVoiceTriggerMatcher.java)
- [IrCompiledVoiceTrigger](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrCompiledVoiceTrigger.java)
- [IrCompiledVoiceWord](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrCompiledVoiceWord.java)
- [IrMatchBatch](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrMatchBatch.java)
- [IrVoiceMatch](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrVoiceMatch.java)
- [input/IrInputText](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/input/IrInputText.java)
- [input/IrInputMapper](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/input/IrInputMapper.java)
- [input/IrInputPreprocessor](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/input/IrInputPreprocessor.java)
- [input/IrPreparedInput](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/input/IrPreparedInput.java)
- [enhance/IrItemEnhancer](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/enhance/IrItemEnhancer.java)
- [enhance/IrItemEnhancementResult](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/enhance/IrItemEnhancementResult.java)
- [enhance/DefaultIrItemEnhancer](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/enhance/DefaultIrItemEnhancer.java)

## 3. 模块职责划分

### 3.1 IrModule

`IrModule` 是 IR 的主编排入口。

它负责：

- 接收模块注册上下文
- 订阅 `INPUT_ASR_FINAL_TEXT`
- 注册 `IR_PARSE` 兼容能力
- 维护 voice trigger 注册表快照
- 调度输入预处理、物品增强和 voice 匹配
- 命中后转发 `VOICE_TRIGGER`
- 发布 `IR_RESULT`

它不负责：

- 管理协议中心底层路由
- 决定具体模块的业务执行逻辑
- 直接处理 ASR 模型
- 直接处理 TTS、LLM 或其他模块内部状态

### 3.2 IrModuleInstaller

`IrModuleInstaller` 是模块安装入口。

它的作用是把 IR 作为一个可装配单元交给模块宿主，避免外层直接散装创建模块实例。

NeoForge 侧可通过自己的 installer 注入客户端增强能力，common 侧则保持基础能力。

### 3.3 IrProtocolAdapter

`IrProtocolAdapter` 是 IR 的协议收发封装层。

它负责：

- 订阅 ASR 最终文本
- 注册 `IR_PARSE` capability
- 发布 `IR_RESULT`
- 发送 `VOICE_TRIGGER`

IR 主流程不直接拼接信封细节，而是通过适配器统一完成协议交互。

## 4. 输入与预处理阶段

IR 的第一阶段不是直接 voice 匹配，而是先做输入分流。

### 4.1 统一输入

不同入口会先映射成 [IrInputText](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/input/IrInputText.java)。

当前入口包括：

- ASR final text
- `IR_PARSE` 兼容输入

对应映射由 [IrInputMapper](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/input/IrInputMapper.java) 负责。

### 4.2 第一阶段预处理

[IrInputPreprocessor](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/input/IrInputPreprocessor.java) 会把输入拆成两个视图：

- `voiceText`
  - 保留完整句子
  - 只做同音词修复的输入视图
  - 用于 voice trigger 匹配

- `filteredText`
  - 去掉 `FILLER_WORDS`
  - 去掉 `ENTITY_BOUNDARY_WORDS`
  - 用于物品增强和结构切分

预处理结果由 [IrPreparedInput](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/input/IrPreparedInput.java) 承载。

### 4.3 预处理语义

当前语义是：

```text
原始输入
  ├─ 完整替换视图 → voice 匹配
  └─ 过滤视图      → 物品增强
```

也就是说，过滤词不会污染 voice trigger 命中判断。

## 5. 物品增强阶段

IR 的物品增强通过 [IrItemEnhancer](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/enhance/IrItemEnhancer.java) 抽象完成。

增强结果由 [IrItemEnhancementResult](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/enhance/IrItemEnhancementResult.java) 承载，主要包含：

- `repairedText`
- `matchedItemNames`
- `matchedItemIds`
- `matched`

### 5.1 common 默认增强器

[DefaultIrItemEnhancer](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/enhance/DefaultIrItemEnhancer.java) 是 common 层默认实现，主要用于在已有 `IRCommandService` 的情况下执行物品增强。

### 5.2 NeoForge 客户端增强器

NeoForge 侧的 [ClientIrItemEnhancer](file:///d:/Minecraft/Tianshu/tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/ir/ClientIrItemEnhancer.java) 会把 `filteredText` 送入客户端物品解析链路，再把命中的物品 ID 和显示名转换成 IR 结构需要的数据。

这样可以把 MC 客户端侧的物品字典、上下文和本地化信息保留在客户端侧，不把 Minecraft 依赖泄漏到 common 主体里。

## 6. voice trigger 匹配阶段

voice trigger 命中由 [IrVoiceTriggerMatcher](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrVoiceTriggerMatcher.java) 完成。

它匹配的对象是：

- `voiceText`
- 协议中心里的 voice trigger 注册表快照

为了避免每次输入都重新计算热词，IR 会先将注册表编译成索引：

- [IrVoiceTriggerIndexer](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrVoiceTriggerIndexer.java)
- [IrCompiledVoiceTrigger](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrCompiledVoiceTrigger.java)
- [IrCompiledVoiceWord](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrCompiledVoiceWord.java)

当前匹配特征：

- hotwords 和 extraWords 等权
- 输入做归一化后再做包含匹配
- confidence 仅作观测分数，不作为是否转发的唯一依据

匹配结果会封装成 [IrMatchBatch](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrMatchBatch.java) 和 [IrVoiceMatch](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrVoiceMatch.java)。

## 7. 命中后的转发

当 voice trigger 命中后，IR 会通过 [IrProtocolAdapter](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrProtocolAdapter.java) 向目标模块发送 `VOICE_TRIGGER`。

对应 payload 为 [VoiceTriggerPayload](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/payload/VoiceTriggerPayload.java)，当前包含：

- `sourceText`
- `moduleId`
- `matchedHotwords`
- `matchedExtraWords`
- `matchedItemNames`
- `matchedItemIds`
- `confidence`

这意味着目标模块收到的不是简单“触发通知”，而是带上下文的触发包。

## 8. 结果观测

IR 还会发布 [IrResultPayload](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/payload/IrResultPayload.java) 作为观测事件。

它的用途主要是：

- 记录是否命中
- 记录命中的模块
- 记录置信度
- 记录原始输入和命中原因

`IR_RESULT` 更偏调试和观测，不是业务执行的最终结果。

## 9. 典型执行顺序

```text
原始输入
  ↓
IrInputMapper
  ↓
IrInputPreprocessor
  ├─ voiceText
  └─ filteredText
        ↓
IrItemEnhancer
  ├─ matchedItemNames
  └─ matchedItemIds
        ↓
IrVoiceTriggerIndexer / IrVoiceTriggerMatcher
        ↓
VOICE_TRIGGER
        ↓
IR_RESULT
```

## 10. 模块边界总结

IR 当前的角色可以概括为：

- 不是业务大脑
- 不是协议中心
- 不是 ASR
- 不是 LLM
- 不是 TTS
- 也不是具体模块执行器

它是一个把语音输入整理成可触发模块事件的编排层。

如果后续继续演进，优先方向应当是：

1. 继续稳定输入预处理与物品增强边界
2. 继续收敛增强器策略
3. 继续验证热词动态更新和客户端上下文桥接
4. 继续保持 IR 只负责触发，不负责业务裁决
