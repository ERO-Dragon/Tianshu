# TTS 模块架构说明

## 1. 模块定位

TTS 模块是天枢语音输出链路的核心模块，负责把结构化文本请求转换为可播放语音，并把播报状态反馈给协议中心和上层业务。

TTS 当前已经从旧式工作线程和散装引擎调用中收口为 common 层的宿主化模块。它面向 `TianshuManagedModule` 生命周期、协议中心、统一线程执行器和平台音频桥工作，不直接依赖 Minecraft GUI，也不把内部运行时状态暴露给外部模块。

TTS 模块的主题不是“播放一段声音”这么简单，而是稳定处理以下问题：

- 文本请求如何进入语音合成链路
- 普通播报、提醒播报、预览试听和流式文本如何区分
- 多个播报请求冲突时如何调度
- 运行时中断、停止、替换和排队如何保持一致
- 模型、后端、合成、播放和状态反馈如何分层
- GUI 如何通过服务边界调用 TTS，而不是直接触碰内部 runtime

## 2. 当前边界

TTS 模块当前主要由以下部分组成：

- [TtsModule](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsModule.java)
- [TtsProtocolAdapter](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsProtocolAdapter.java)
- [TtsModuleService](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsModuleService.java)
- [TtsModelService](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsModelService.java)
- [VoiceNotificationService](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/VoiceNotificationService.java)
- [TtsVoiceLibraryService](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsVoiceLibraryService.java)
- [TtsRuntime](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/runtime/TtsRuntime.java)
- [TtsSessionManager](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/runtime/TtsSessionManager.java)
- [TtsStreamRegistry](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/runtime/TtsStreamRegistry.java)
- [TtsStreamBuffer](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/text/TtsStreamBuffer.java)
- [DefaultTtsSynthesisEngine](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/DefaultTtsSynthesisEngine.java)
- [SherpaOnnxTtsBackend](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/SherpaOnnxTtsBackend.java)
- [MossTtsBackend](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/MossTtsBackend.java)
- [TtsPlaybackController](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/playback/TtsPlaybackController.java)

## 3. 模块职责划分

### 3.1 TtsModule

`TtsModule` 是 TTS 的模块装配根。

它负责：

- 作为 `TianshuManagedModule` 接入模块宿主生命周期
- 在 register 阶段注册 TTS 服务
- 在 prepare 阶段创建合成引擎和运行时
- 在 start / stop / destroy 阶段启动、停止和释放运行时资源
- 注册 TTS 协议能力
- 把协议 payload 转换成运行时请求
- 发布播放状态和播放完成事件

它不负责：

- 模型目录解析细节
- 后端初始化细节
- 文本归一化细节
- 会话终态保护细节
- GUI 展示和玩家设置布局

这些职责分别下沉到模型服务、合成层、文本层、运行时层和 client GUI 层。

### 3.2 TtsProtocolAdapter

`TtsProtocolAdapter` 是 TTS 对协议中心的公开入口。

当前公开能力保持收敛，只包含：

- `TTS_SPEAK`
- `TTS_ALERT`
- `TTS_STOP`
- `TTS_CONTROL`

其中 `preview` 不属于公开协议能力。试听是 TTS 自己的服务能力，由 GUI 或模块内调用 `TtsModuleService.preview(...)` 完成。

这个边界可以避免把面向玩家设置页或模块内部工具操作扩散成跨模块协议能力。

### 3.3 TtsModuleService

`TtsModuleService` 是 TTS 面向本地调用方的服务边界，也是未来 GUI 对接 TTS 的主要入口。

它提供：

- `snapshot()`：读取运行时摘要
- `modelSnapshot()`：读取模型摘要
- `backendSnapshot()`：读取后端摘要
- `preview(...)`：试听文本
- `stopPreview(...)`：停止试听
- `stopAll(...)`：停止当前播报
- `reloadModel()`：重载语音模型
- `ready()`：判断 TTS 是否可用

GUI、client 操作和其他本地集成点应该优先通过这个服务与 TTS 交互，而不是直接访问 `TtsRuntime`、`TtsSessionManager`、合成引擎或后端对象。

### 3.4 TtsRuntime

`TtsRuntime` 是 TTS 的运行时核心。

它负责：

- 接收普通请求和流式请求
- 检查运行状态
- 创建和登记会话
- 执行播放策略
- 调度合成任务
- 连接合成输出与音频播放
- 维护播放状态摘要
- 记录最后失败信息
- 执行 stop / reload 等控制动作

运行时不是对外状态模型。外部只能通过快照和服务方法观察必要信息。

### 3.5 TtsSessionManager

`TtsSessionManager` 负责维护当前 TTS 会话集合。

它的重点是会话一致性，而不是对外展示状态。

它需要保证：

- 活跃会话可定位
- 排队会话可管理
- 终态会话不会被重复改写
- stopAll 能同时处理运行中和等待中的会话
- 替换、打断和取消不会造成会话串线

### 3.6 TtsModelService

`TtsModelService` 是模型解析和模型资源管理边界。

它负责：

- 根据配置解析当前 TTS 模型目录
- 匹配模型 catalog
- 判断模型目录是否存在
- 判断模型目录是否有内容
- 提供模型摘要
- 执行模型下载、暂停、恢复、取消和删除等资源操作

当前 GUI 第一阶段不应该把完整模型下载管理直接塞进玩家设置页。模型管理可以后续作为单独的资源管理区展开。

### 3.7 TtsSynthesisEngine 与 Backend

合成层分为两层：

```text
TtsRuntime
  -> TtsSynthesisEngine
  -> TtsBackend
```

`TtsSynthesisEngine` 负责选择、初始化和调用具体后端。

`TtsBackend` 负责具体合成实现，例如：

- Sherpa ONNX 类模型
- MOSS 类模型
- 后续可能接入的其他本地或远程合成后端

后端细节不应出现在玩家 GUI 中。玩家只需要知道语音是否可用、当前语音是什么、能不能试听。

### 3.8 TtsPlaybackController

`TtsPlaybackController` 是播放边界。

它负责把合成产生的 PCM 音频交给平台音频桥，并将播放开始、完成、失败等结果反馈给运行时。

播放控制层不决定协议语义，也不决定模型选择。它只负责“如何可靠播放”。

## 4. 当前链路

### 4.1 普通播报链路

普通播报适用于聊天助手回复、系统提示等场景。

```text
外部模块
  -> TTS_SPEAK
  -> TtsProtocolAdapter
  -> TtsModule
  -> TtsRuntime
  -> TtsSynthesisEngine
  -> TtsPlaybackController
  -> IAudioBridge
```

### 4.2 提醒播报链路

提醒播报适用于优先级更高的警告或系统提醒。

```text
提醒来源
  -> TTS_ALERT
  -> TtsProtocolAdapter
  -> TtsRuntime
  -> 播放策略
  -> 合成与播放
```

提醒请求可以通过更高优先级和不同播放策略影响当前播报。

### 4.3 试听链路

试听不走公开协议能力。

```text
GUI / 本地调用方
  -> TtsModuleService.preview(...)
  -> TtsRuntime
  -> TtsRequestSource.PREVIEW
  -> 合成与播放
```

试听默认使用替换当前试听的策略，避免玩家连续点击后产生多段试听排队。

### 4.4 流式文本链路

流式文本用于上游文本逐段生成、TTS 逐步接收的场景。

```text
stream chunk
  -> TtsStreamRegistry
  -> TtsStreamBuffer
  -> final chunk flush
  -> TtsRuntime.submit(...)
```

流式链路需要保证：

- 非 final chunk 只进入缓冲
- final chunk 触发完整文本提交
- streamId 缺失时有稳定兜底身份
- 空文本和无效 chunk 会给出结构化失败

### 4.5 控制链路

控制链路用于停止、打断和重载。

```text
TTS_STOP / TTS_CONTROL / TtsModuleService
  -> TtsRuntime
  -> TtsSessionManager
  -> TtsSynthesisEngine / TtsPlaybackController
```

当前控制结果使用 `TtsControlResult` 表达，避免调用方只能从异常或日志中猜测结果。

## 5. 协议边界

TTS 的公开协议边界保持业务语义收敛。

公开协议适合表达：

- 请朗读一段文本
- 请播报一条提醒
- 请停止某类播报
- 请执行明确控制动作

公开协议不适合表达：

- GUI 分类注册
- 玩家试听按钮
- 内部 backend 状态
- 内部 session 状态
- 模型下载细节

因此 `preview` 被放在 `TtsModuleService`，而不是 `ProtocolCapabilities`。

## 6. GUI 边界

TTS 的 GUI 属于 NeoForge client 层，不属于 common 模块。

common 只提供服务和快照：

- `TtsModuleService`
- `TtsRuntimeSnapshot`
- `TtsModelSnapshot`
- `TtsBackendSnapshot`

client GUI 应该使用设置框架声明模板，而不是自己创建 Minecraft 控件。

推荐边界是：

```text
TtsSettingsRegistrySource
  -> TtsModuleService
  -> snapshot / preview / stop / reload
```

GUI 不应该直接访问：

- `TtsRuntime`
- `TtsSession`
- `TtsSessionState`
- `TtsSynthesisEngine`
- `TtsBackend`

面向玩家的 GUI 也不应该展示专业运行时字段。内部字段需要映射成人类可理解的信息，例如：

| 内部状态 | 玩家显示 |
|---|---|
| runtime 未绑定 | 语音服务未启动 |
| model 无内容 | 语音模型未安装 |
| backend 未初始化 | 语音暂不可用 |
| ready | 可用 |
| synthesis failed | 语音生成失败 |
| playback failed | 播放失败 |

## 7. 播放策略

TTS 运行时支持多种播放策略，用于处理多个播报请求之间的冲突。

当前核心策略包括：

- `QUEUE`：按顺序排队播放
- `DROP_IF_BUSY`：忙碌时丢弃新请求
- `REPLACE_CURRENT`：替换当前请求
- `LATEST_ONLY`：只保留最新请求
- `INTERRUPT_LOWER_PRIORITY`：打断低优先级请求

这些策略属于业务运行时语义，不应该直接暴露到玩家 GUI。

玩家只需要看到简单动作：

- 试听语音
- 停止播报
- 重载语音

## 8. 错误和结果模型

TTS 当前使用结构化结果表达运行情况。

主要对象包括：

- `TtsOperationResult`
- `TtsControlResult`
- `TtsFailure`
- `TtsFailureCode`

常见失败码包括：

- `RUNTIME_NOT_RUNNING`
- `EMPTY_TEXT`
- `SYNTHESIS_ENGINE_UNAVAILABLE`
- `SYNTHESIS_FAILED`
- `PLAYBACK_FAILED`
- `REQUEST_NOT_FOUND`
- `INVALID_REQUEST`
- `CANCELLED`
- `UNKNOWN`

这些失败码用于模块间稳定传递和测试断言。玩家 GUI 应该把失败码翻译成简单提示，而不是直接显示枚举名称。

## 9. 运行时状态暴露原则

TTS 内部有会话状态，但外部不应该依赖这些内部状态。

内部可以使用：

- `TtsSessionState`
- session id
- request id
- source
- priority
- backend type

外部应该优先使用：

- `TtsPlaybackPhase`
- 服务快照
- 操作结果
- 控制结果

这条边界的目的，是防止 GUI 或其他模块把 TTS 内部调度状态当成公共 API，从而限制后续 runtime 演进。

## 10. 测试覆盖

当前 TTS 已经补充了围绕运行时核心语义的测试。

重点覆盖：

- session 终态保护
- stopAll 对运行中和排队会话的一致处理
- stream chunk 的缓冲与 final flush
- runtime 未运行时的结构化拒绝
- 空文本和 null chunk 的结构化失败
- `DROP_IF_BUSY`、`REPLACE_CURRENT`、`LATEST_ONLY`、`INTERRUPT_LOWER_PRIORITY`、`QUEUE` 等播放策略
- `TtsModuleService` 的 preview、stopPreview、stopAll、reloadModel 边界

这些测试的价值不是验证某个 mock 是否被调用，而是锁定运行时调度、控制和失败语义。

## 11. 当前设计原则

TTS 当前架构最重要的原则是：

1. **模块宿主化**
   - TTS 作为 managed module 接入 CoreManager 生命周期，而不是由外层散装启动。

2. **协议面收敛**
   - 公开协议只保留 speak、alert、stop、control。

3. **试听内聚**
   - preview 是 TTS 服务能力，不是公开协议能力。

4. **运行时内聚**
   - 调度、session、stream、控制和失败语义由 `TtsRuntime` 统一处理。

5. **状态有限暴露**
   - 外部只能看到必要快照和公共播放阶段，不依赖内部 session 状态。

6. **GUI 不穿透 runtime**
   - GUI 通过 `TtsModuleService` 与 TTS 交互。

7. **玩家 GUI 面向玩家**
   - 玩家设置页只展示启用、当前语音、试听、停止和简单可用性提示。

## 12. 后续可继续推进的方向

TTS 后续最值得推进的方向包括：

- 在 client GUI 中接入玩家向语音播报设置页
- 增加独立的 TTS 启用开关，并让聊天助手和提醒播报统一尊重该开关
- 把模型选择整理成玩家语音预设，而不是直接暴露技术模型名
- 为 TTS 模型下载管理设计独立的资源管理区
- 补充更多端到端测试，覆盖 LLM 流式输出到 TTS 播放的完整链路
- 根据实际使用反馈继续完善播放打断、淡出和优先级策略

## 13. 结论

TTS 模块当前已经从旧式 TTS 工作器和散乱调用方式，进入到宿主化模块、协议适配器、运行时调度、服务边界和结构化失败结果并存的架构阶段。

它的基础能力已经比较完整，下一步重点不是继续堆内部状态，而是把玩家可感知的语音播报设置以简洁方式接入 GUI，同时保持 common 层和 client GUI 层的边界清晰。
