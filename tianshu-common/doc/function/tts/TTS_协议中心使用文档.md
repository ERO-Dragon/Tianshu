# TTS 协议中心使用文档

本文只说明外部模块如何通过协议中心使用 TTS。不要直接依赖 `TtsRuntime`、后端或内部 Session 类型。

## 1. 能力和 Topic

| 名称 | Payload | 用途 |
|---|---|---|
| `ProtocolCapabilities.TTS_SPEAK` | `TtsSpeakPayload` | 本地合成并播放。 |
| `ProtocolCapabilities.TTS_SYNTHESIZE` | `TtsSynthesisRequestPayload` | 返回 PCM，不播放。 |
| `ProtocolCapabilities.TTS_CONTROL` | `TtsControlPayload` | 停止、重载和音色管理。 |
| `ProtocolTopics.TTS_PLAYBACK` | `TtsPlaybackStatusPayload` | 模块级播放状态。 |
| `ProtocolTopics.TTS_REQUEST_STATUS` | `TtsRequestStatusPayload` | 单个播放 Session 的状态。 |

## 2. 播放完整文本

`COMMAND` 必须使用 `DOCUMENT`：

```java
TtsSpeakPayload payload = new TtsSpeakPayload(
        "前方发现敌对生物。",
        0,
        0L,
        TtsPlaybackPlacement.INSERT_AFTER_SENTENCE,
        TtsTextInputMode.DOCUMENT,
        TtsVoiceOptions.defaults()
);

adapter.commandCapability(
        ProtocolCapabilities.TTS_SPEAK,
        PayloadType.TTS_TEXT,
        payload
);
```

如果没有业务 Session，`turnId=0`、`sessionId=0L` 即可。TTS 使用协议信封身份区分该文档请求。

## 3. 播放句子流

调用方已经分好句时使用 `SENTENCE_STREAM`。同一 Session 的所有包必须使用相同的正数 `sessionId` 和相同 `turnId`：

```java
TtsSpeakPayload sentence = new TtsSpeakPayload(
        "第一句。",
        turnId,
        sessionId,
        TtsPlaybackPlacement.QUEUE_AFTER_SESSION,
        TtsTextInputMode.SENTENCE_STREAM,
        TtsVoiceOptions.defaults()
);

adapter.submitToCapability(
        ProtocolCapabilities.TTS_SPEAK,
        PacketType.STREAM_CHUNK,
        PayloadType.TTS_TEXT,
        sentence
);
```

每个 chunk 是一个完整句子。结束时必须发送：

```java
TtsSpeakPayload end = new TtsSpeakPayload(
        "",
        turnId,
        sessionId,
        TtsPlaybackPlacement.QUEUE_AFTER_SESSION,
        TtsTextInputMode.SENTENCE_STREAM,
        TtsVoiceOptions.defaults()
);

adapter.submitToCapability(
        ProtocolCapabilities.TTS_SPEAK,
        PacketType.STREAM_END,
        PayloadType.TTS_TEXT,
        end
);
```

任意文本 chunk 使用 `RAW_TEXT_STREAM`，TTS 会跨包保留空格和未完成句子，并在 `STREAM_END` flush 尾部。

首包冻结 placement、协议优先级和音色选项。后续包不能改变这些值，也不会再次 admission。没有 `STREAM_END` 时，TTS 不猜测 Session 已结束。

流式 Session 被 placement 或控制取消后，迟到 chunk 会被忽略到该流的 `STREAM_END`；结束包解除屏障后，同一业务 Session 身份才可用于新流。内部 pending 容量已满时，首包以结构化队列满失败结束，紧急 placement 也不能绕过容量。

## 4. 播放策略

| placement | 首包到达时的行为 |
|---|---|
| `DROP_IF_BUSY` | 忙时丢弃整个新 Session。 |
| `QUEUE_AFTER_SESSION` | 进入普通队列；按协议优先级和 FIFO 排序。 |
| `INSERT_AFTER_SESSION` | 当前 Session 完整结束后、普通队列前播放。 |
| `INSERT_AFTER_SENTENCE` | 当前播放句结束后挂起旧 Session并插入。 |
| `CANCEL_SENTENCE_AND_PLAY` | 取消当前句，保留旧 Session 后续句子。 |
| `CANCEL_SESSION_AND_PLAY` | 取消当前 Session 全部剩余内容。 |

嵌套插入按媒体焦点栈恢复。C 插入 B、B 插入 A 时，恢复顺序为 C、B、A。

## 5. 音色、语速和 speaker

请求覆盖统一使用：

```java
TtsVoiceOptions voice = new TtsVoiceOptions(
        "module.example:voice",
        1.15F,
        2
);
```

三个字段都可为空：

- `voiceId=""`：使用 TTS 全局默认音色。
- `speed=null`：使用模型默认语速。
- `speakerId=null`：使用模型默认 speaker。

显式 `voiceId` 不存在时请求失败，不会回退成另一声音。

## 6. 纯合成

需要 NPC、实体或方块 3D 声源时使用 `TTS_SYNTHESIZE`：

```java
TtsSynthesisRequestPayload payload = new TtsSynthesisRequestPayload(
        "npc-line-001",
        "前方很危险。",
        true,
        30_000L,
        new TtsVoiceOptions("", 1.0F, null)
);
```

响应为 `TtsAudioPayload`：`audio` 是 PCM，`sampleRate` 和 `channels` 描述格式，`chunkIndex` 从 0 递增，`last=true` 表示终止包。

`streaming=false` 时最后返回合并 PCM。`streaming=true` 时返回多个非终止 chunk，最后额外返回空的 terminal chunk。播放请求插队不会取消纯合成；纯合成只在句子安全边界让出后端后继续。

同一时刻活跃的纯合成任务必须使用唯一 `requestId`；重复 id 会被拒绝，避免旧任务失去 STOP 身份。`ttlMillis` 同时覆盖排队和单个长句推理，到期会终止该纯合成任务，不会中断无关播放或其他纯合成。

## 7. capability 完成和播放状态

`TTS_SPEAK` complete 表示请求已通过校验并完成 admission，不表示玩家已经听完。不要通过等待 capability complete 推断播放结束。

订阅 `TTS.REQUEST_STATUS` 获取请求级状态：

| 状态 | 含义 |
|---|---|
| `QUEUED` | Session 已 admission。 |
| `PLAYING` | Session 开始实际句子处理。 |
| `COMPLETED` | Session 所有句子播放完成。 |
| `CANCELLED` | Session 被策略、控制或生命周期取消。 |
| `FAILED` | 合成或播放失败。 |

payload 携带稳定的 `requestId/sourceId/sessionId/turnId/failureCode`。模块级 HUD 只需订阅 `TTS.PLAYBACK` 的 `IDLE/SPEAKING/ALERTING`。

## 8. 音色注册和 owner

参考音频已在 `config/Tianshu/module/tts/voices/` 时，发送 `LOAD_VOICE`；联动模组从自己的 jar 读取音频字节时，发送 `IMPORT_VOICE`。TTS common 不读取其他模组的资源系统。

```java
TtsControlPayload load = new TtsControlPayload(
        TtsControlPayload.Action.LOAD_VOICE,
        "module.example:voice",
        "sample.wav",
        "参考音频对应文本",
        "load voice"
);
```

协议信封 `sourceId` 是音色 owner。其他模块不能覆盖或卸载这个 id。同一 owner 可以重新加载自己的 id。显式 voiceId 应使用模块命名空间，避免无意义冲突。

`IMPORT_VOICE` 由调用方传入完整音频文件字节；TTS 校验大小并写入 owner 子目录。失败导入不会留下未注册文件。

## 9. 控制

| Action | 行为 |
|---|---|
| `STOP_CURRENT` | 停止当前播放 Session。 |
| `STOP` | requestId 为空时停止全部；非空时停止对应播放或纯合成任务。 |
| `STOP_SOURCE` | 停止真实 sourceId 对应的播放 Session。 |
| `RELOAD_MODEL` | 异步重载模型。 |
| `LOAD_VOICE` / `IMPORT_VOICE` | 注册音色。 |
| `UNLOAD_VOICE` / `CLEAR_VOICE_CACHE` | 释放当前 owner 的音色。 |

退出世界会停止本轮 Session、纯合成和播放。模块可以复用内部 runtime 与已加载模型以降低重进开销，但重新进入世界一定开启新的运行会话；调用方不能保存或复用旧世界的 envelope、回调或 Session 状态。

## 10. 线程与下载边界

调用方只提交协议请求。TTS 的模型、分句、合成、文件和音频 IO 不在 Minecraft 主线程执行。

模型下载来源不是公共 payload。内置 catalog 负责 Hugging Face repo/revision 文件定位、HF Mirror 降级，以及 GitHub proxy 到 direct URI 降级；下载保持 staging、完整性校验、暂停/继续/取消和原子提交。外部模块不要自行拼接或覆盖 TTS 模型 URL。

宿主设置页读取 `ModelDownloadProgress` 的 `stage`、`percent`、`downloadedBytes`、`totalBytes` 和 `detailCode`，再由 Client 语言资源生成可见文案。Common 不再发送自由文本下载标签。

## 11. 诊断

TTS 诊断只进入宿主集中诊断服务，并受 TTS 模块诊断开关控制。backend 不创建私有日志文件或线程。正式包不运行 MOSS smoke，也不包含测试 WAV 和生成音频。
