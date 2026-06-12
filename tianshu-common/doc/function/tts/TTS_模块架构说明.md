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
| 运行时 | `TtsRuntime` | 处理请求校验、播放策略、合成调度、控制动作、失败结果和状态发布。 |
| 会话管理 | `TtsSessionManager` | 管理本地播放 session、分组取消、终态保护和 active 定位。 |
| 流式文本 | `TtsStreamRegistry` / `TtsStreamBuffer` | 聚合上游文本 chunk，按句提交给 runtime。 |
| 合成层 | `TtsSynthesisEngine` / `TtsBackend` | 统一调用 Sherpa ONNX、MOSS 等后端。 |
| 播放层 | `TtsPlaybackController` | 将 PCM 交给 `IAudioBridge`，处理播放、取消和完成回调。 |
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

## 7. 线程和调度

TTS 不自行创建散装线程，所有后台工作进入 `ProtocolExecutorManager`：

| 工作 | Lane | 说明 |
|---|---|---|
| 快速 TTS 合成 | `TTS_FAST` | 非自回归或较轻量后端，最大并发 1。 |
| 自回归 TTS 合成 | `TTS_AUTOREGRESSIVE` | MOSS 等较重后端，最大并发 1。 |
| 音频播放 IO | `AUDIO_IO` | 串行写入平台音频桥。 |
| 模型加载 | `MODEL_LOAD` | 后续模型重载、资源准备使用。 |

合成和播放分离：合成任务可以在当前音频仍播放时预合成后续句子，播放层通过 `TtsPlaybackController` 串行写入音频桥。

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

玩家界面应展示“语音服务可用性、当前语音、试听、停止、重载”等玩家能理解的概念，不展示内部枚举和后端细节。

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

失败码用于模块间稳定传递和测试断言。玩家 GUI 应翻译成简短提示，不直接显示枚举名。

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

测试目标不是验证 mock 调用次数，而是锁定调度、取消、恢复、失败语义和协议边界。

## 12. 设计原则

- TTS 是宿主化模块，不在外层散装启动。
- 公开协议面只保留本地播放、纯合成和控制。
- 播放策略用枚举表达，不用多个布尔值拼装。
- 合成和播放分层，外部需要 3D 声源时走纯合成。
- GUI 通过服务边界接入，不穿透 runtime。
- 内部状态有限暴露，topic 只发布模块级状态。
