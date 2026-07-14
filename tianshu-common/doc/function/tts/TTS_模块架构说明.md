# TTS 模块架构说明

本文面向维护 TTS 模块的开发者。外部模块调用方式见 [TTS_协议中心使用文档.md](TTS_协议中心使用文档.md)。

## 1. 模块定位

TTS 模块负责把结构化文本请求转换为语音输出。它已经从旧式工作线程和散装引擎调用收口为 common 层的宿主化模块，统一接入：

- `TianshuManagedModule` 生命周期
- 协议中心能力和 topic
- `ProtocolExecutorManager` 线程资源
- 平台 `IAudioBridge`
- TTS 模型解析和后端合成层

TTS 不直接依赖 Minecraft GUI，也不把内部 session 状态作为公共 API 暴露给其他模块。

## 2. 模块边界

核心类分层如下：

| 层 | 类 | 职责 |
|---|---|---|
| 模块装配 | `TtsModule` | 接入宿主生命周期，注册协议能力，装配 runtime、模型服务和后端。 |
| 协议边界 | `TtsProtocolAdapter` | 注册 `TTS_SPEAK`、`TTS_SYNTHESIZE`、`TTS_CONTROL`，发布播放状态，发送音频响应。 |
| 本地服务 | `TtsModuleService` | 面向 GUI 和本地调用方提供 snapshot、preview、stop、reload 等服务。 |
| 运行时 facade | `TtsRuntime` | 保持提交、控制、snapshot 和状态发布入口，编排模块内协作者。 |
| 合成调度 | `TtsSynthesisScheduler` | 统一 synthesis lane、业务优先级和单后端并发边界。 |
| 纯合成任务 | `TtsSynthesisTaskCoordinator` | 管理 TTL、stream/full PCM、任务取消和终态单次回调。 |
| 模型生命周期 | `TtsModelLifecycleCoordinator` | 在 `MODEL_LOAD` 串行执行 prepare、reload、switch、preview restore 和 shutdown。 |
| 会话管理 | `TtsSessionManager` | 管理本地播放 session、分组取消、终态保护和 active 定位。 |
| 流式文本 | `TtsStreamRegistry` / `TtsStreamBuffer` / `SentenceSegmenter` | 聚合上游文本 chunk；无状态分句器位于共享 `text` 包，供 AX/TTS 共同使用。 |
| 合成层 | `TtsSynthesisEngine` / `TtsBackend` | 统一调用 Sherpa ONNX、MOSS 等后端。 |
| MOSS facade | `MossTtsService` | 保持既有公开合成 API，编排模型、帧生成和 codec，不直接持有 ORT session 或 KV cache。 |
| MOSS 模型资源 | `MossModelRuntime` | 单一持有 ORT environment、tokenizer、manifest、metadata 和 named sessions。 |
| MOSS 自回归生成 | `MossFrameGenerator` / `MossTensorState` | 生成 audio-code frame；global past 直接接管 ONNX tensor handle 并在 decode step 间单一交接。 |
| MOSS codec | `MossAudioCodec` | 参考音频编码、full decode、四帧 streaming decode 和 codec state 生命周期。 |
| 播放层 | `TtsPlaybackController` | 维护 session admission slot，只让 active session 以严格 FIFO 写入 `IAudioBridge`。 |
| 模型资源 | `TtsModelService` / `TtsVoiceLibraryService` | 解析模型、音色样本和后续资源管理入口。 |
| 音色克隆 | `TtsVoiceCloneRegistry` | 受控加载 voice library 内的参考音频，并用 `voiceId` 暴露给 speak/synthesize。 |

## 3. 公开协议面

TTS 公开协议保持收敛：

- `TTS_SPEAK`：本地合成并播放。
- `TTS_SYNTHESIZE`：只合成音频，通过响应返回。
- `TTS_CONTROL`：停止、取消来源、重载模型、导入和加载音色。
- `TTS.PLAYBACK`：模块级播放状态 topic。

旧的 `TTS_ALERT` 不保留。提醒、预警和插话都是 `TTS_SPEAK` 的不同 `TtsPlaybackPlacement`。

preview 不属于公开协议能力。玩家设置页或本地 UI 应通过 `TtsModuleService.preview(...)` 调用，避免把 GUI 动作扩散成跨模块协议。

## 4. 运行时链路

### 本地播放

```text
TTS_SPEAK
  -> TtsModule
  -> TtsRuntime.submit
  -> TtsSessionManager
  -> TtsSynthesisEngine
  -> TtsPlaybackController
  -> IAudioBridge
```

本地播放优先于纯合成 task。进入本地播放时，runtime 会抢占当前纯合成任务，避免实时播报被后台 NPC 配音或其他低时效合成拖住。

### 纯合成

```text
TTS_SYNTHESIZE
  -> TtsRuntime.synthesize
  -> TtsSynthesisTaskCoordinator
  -> TtsSynthesisScheduler
  -> TtsSynthesisEngine
  -> TtsAudioPayload response
```

纯合成不会播放音频。它只返回 PCM chunk，调用方自行决定 2D、3D、实体声源、方块声源或跨端同步。

### 控制

```text
TTS_CONTROL / TtsModuleService
  -> TtsRuntime
  -> TtsSessionManager
  -> TtsSynthesisEngine / TtsPlaybackController
```

控制结果使用 `TtsControlResult`，普通提交结果使用 `TtsOperationResult`，失败原因使用 `TtsFailure` 和 `TtsFailureCode`。

## 5. 播放策略模型

对外策略是 `TtsPlaybackPlacement`，运行时策略是 `TtsPlaybackPolicy`。二者保持一一映射，不再额外叠加 `queueIfBusy` 这类布尔开关。

当前运行时支持：

- `DROP_IF_BUSY`
- `QUEUE`
- `INSERT_AFTER_SESSION`
- `INSERT_AFTER_SENTENCE`
- `CANCEL_SENTENCE_AND_PLAY`
- `CANCEL_SESSION_AND_PLAY`
- `REPLACE_CURRENT`
- `LATEST_ONLY`

其中 `REPLACE_CURRENT` 和 `LATEST_ONLY` 是内部服务和兼容策略，不建议作为新的跨模块业务语义继续扩散。

TTS 不再提供“暂停当前句子并插话后恢复”的策略。需要立即插话时使用 `CANCEL_SENTENCE_AND_PLAY` 或 `CANCEL_SESSION_AND_PLAY`；需要保留原播报时使用 `INSERT_AFTER_SENTENCE` 或 `INSERT_AFTER_SESSION`。

业务优先级只参与 session admission 和合成任务排序，不进入音频桥内部命令排序。等待播放的 session 保存后端交出的 PCM 引用；只有 active slot 会通过单一 `AUDIO_IO` pump 执行 `start -> feed* -> finish`。取消 active session 时先写入终态，使尚未执行的 feed 自动失效，再在同一 FIFO 边界执行 stop 和下一 slot 激活。

运行时 stop/preempt 同样遵守“状态先于副作用”：先把目标 session 转为 `CANCELLED` 并发布终态，再调用可能让阻塞合成立即返回的 `synthesisEngine.interrupt()`。这样完成回调不能在 stop 与 cancel 之间把 session 抢先写成 `COMPLETED`。

## 6. 音色克隆缓存

音色克隆入口统一由 `TTS_CONTROL` 管理。外部模块只注册 `voiceId`，后续 `TTS_SPEAK` 和 `TTS_SYNTHESIZE` 通过 `voiceStyle=voiceId` 引用，不直接携带参考音频。

当前有两种音色来源：

- `LOAD_VOICE`：加载 `config.getVoiceLibraryPath()` 目录内已经存在的参考音频。
- `IMPORT_VOICE`：由资源拥有者读取 jar/resource 中的音频为 `byte[]`，TTS 校验后写入 voice library 的 owner 子目录，并立刻加载。

`TtsVoiceCloneRegistry` 是音色样本和 clone profile 的唯一管理入口。它负责：

- 限制 `LOAD_VOICE` 只能读取 voice library 内文件。
- 限制 `IMPORT_VOICE` 的大小，根据音频头和 `voiceId` 生成安全文件名。
- 使用协议信封 `sourceId` 作为 owner，把导入样本写到 owner 子目录。
- 在加载时解析为内部 profile。

- `samplePath`：保留给 MOSS 等需要后端自行编码 prompt 的实现。
- `referenceAudio(float[])`、`referenceSampleRate`、`referenceText`：供 ZipVoice 通过 Sherpa `GenerationConfig` 直接使用。

MOSS 后端可以继续在 backend 内按 `samplePath + mtime + size` 缓存 prompt codes；ZipVoice 不需要每次读文件，直接使用 profile 中已解码的 float 数组。

### 6.1 MOSS 推理边界

MOSS 具体推理实现位于 `function.tts.synthesis.moss`，不再放在通用 `model` 包。其资源和热路径边界如下：

- `MossModelRuntime` 只管理模型下载入口、metadata、tokenizer、session 和统一关闭。
- `MossFrameGenerator` 管理 request rows、sampling、prefill/decode 和 local decoder。
- `MossTensorState` 是 global KV past 的单一 owner。prefill/decode 输出直接转交 `OnnxTensor` handle，不允许经过 `getValue()`、Java 数组和新 tensor 重建；local cached-step 因 `OrtSession.Result` 生命周期约束保留原有 clone。
- `MossAudioCodec` 管理 prompt encode、full decode、streaming state 和音频 chunk 合并。实时 streaming 固定每四个生成 frame 解码一次，不能在普通清洁中改变该节奏。
- `MossTtsService` 只保留公开入口、文本切分、内置音色解析、协作者编排和公开结果类型。

MOSS 的真实模型 smoke 位于 `src/test`，必须显式设置 `TIANSHU_MOSS_SMOKE=true` 才运行。standalone 测试先调用底层 `NativeLibraryLoader.ensureLoaded()`，复用游戏中的 Sherpa ORT 超集加载顺序。smoke 类、生成 WAV 和性能输出不得进入正式 jar，也不得由 production bootstrap 自动执行。

## 7. 线程和调度

TTS 不自行创建散装线程，所有后台工作进入 `ProtocolExecutorManager`：

| 工作 | Lane | 说明 |
|---|---|---|
| 快速 TTS 合成 | `TTS_FAST` | 非自回归或较轻量后端，最大并发 1。 |
| 自回归 TTS 合成 | `TTS_AUTOREGRESSIVE` | MOSS 等较重后端，最大并发 1。 |
| 音频播放 IO | `AUDIO_IO` | 串行写入平台音频桥。 |
| 模型加载 | `MODEL_LOAD` | prepare、reload、model switch、preview switch/restore 和 shutdown，按模块 key 串行。 |

合成和播放分离：合成任务可以在当前音频仍播放时预合成后续句子，播放层通过 `TtsPlaybackController` 串行写入音频桥。

模型初始化和切换不在调用线程执行。`TtsModule.prepare()` 只提交后台准备，并在完成回调中更新 capability；GUI preview 先进入独占模型生命周期，普通合成不会使用临时 preview 模型。模型下载暂停使用条件等待，resume/cancel 主动唤醒，不使用轮询 sleep。

## 8. 状态暴露

内部 session 状态用于 runtime 调度：

- `CREATED`
- `QUEUED`
- `SYNTHESIZING`
- `PLAYING`
- `DRAINING`
- `COMPLETED`
- `CANCELLED`
- `FAILED`

外部 topic 只暴露模块级状态：

- `IDLE`
- `SPEAKING`
- `ALERTING`

这条边界很重要：GUI 和其他模块不应该依赖内部 session 阶段，否则后续 runtime 调度策略会被公共 API 锁死。

## 9. GUI 边界

common 层只提供服务和快照：

- `TtsModuleService`
- `TtsRuntimeSnapshot`
- `TtsModelSnapshot`
- `TtsBackendSnapshot`

NeoForge GUI 应通过服务层读取摘要、试听、停止和重载，不直接访问：

- `TtsRuntime`
- `TtsSession`
- `TtsSessionManager`
- `TtsSynthesisEngine`
- `TtsBackend`

玩家界面应展示“语音服务可用性、当前语音、试听、停止、重载”等玩家能理解的概念，不展示内部枚举和后端细节。common 只传递稳定 failure code 和资源键；NeoForge 根据 `en_us.json` / `zh_cn.json` 本地化，不直接显示底层异常 message。

## 10. 失败模型

常见失败码：

- `RUNTIME_NOT_RUNNING`
- `EMPTY_TEXT`
- `SYNTHESIS_ENGINE_UNAVAILABLE`
- `SYNTHESIS_FAILED`
- `PLAYBACK_FAILED`
- `REQUEST_NOT_FOUND`
- `INVALID_REQUEST`
- `QUEUE_FULL`
- `EXPIRED`
- `CANCELLED`
- `UNKNOWN`

失败码用于模块间稳定传递和测试断言。`TtsFailure` 同时保留原始 cause；普通异常和可选 native `LinkageError` 可以转换为结构化失败，严重 JVM `Error` 继续抛出。玩家 GUI 应翻译成简短提示，不直接显示枚举名或底层诊断文本。

## 11. 测试重点

当前 TTS 测试应持续覆盖：

- session 终态保护
- stopAll / stopCurrent / stopRequest / stopSource
- 纯合成 task TTL、取消和本地播放抢占
- stream chunk 缓冲和 final flush
- `DROP_IF_BUSY`、普通排队、插队、取消句子、取消会话、流式会话取消屏障
- 合成和播放分离后的长短句交叉
- TTS topic 的三态发布
- `TtsModuleService` 的 preview、stopPreview、stopAll、reloadModel
- MOSS package/资源/generation/codec 边界、global tensor handle ownership 和四帧 streaming cadence
- 正式 universal native runtime 下的 MOSS full/streaming 真实模型输出、首包、样本数和 RTF
- 正式 jar 不包含 smoke 类、测试资源或生成 WAV

测试目标不是验证 mock 调用次数，而是锁定调度、取消、恢复、失败语义和协议边界。

## 12. 设计原则

- TTS 是宿主化模块，不在外层散装启动。
- 公开协议面只保留本地播放、纯合成和控制。
- 播放策略用枚举表达，不用多个布尔值拼装。
- 合成和播放分层，外部需要 3D 声源时走纯合成。
- GUI 通过服务边界接入，不穿透 runtime。
- 内部状态有限暴露，topic 只发布模块级状态。
