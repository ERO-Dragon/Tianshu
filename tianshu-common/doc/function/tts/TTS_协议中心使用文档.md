# TTS 协议中心使用文档

本文面向想调用天枢 TTS 的模块开发者，只说明跨模块协议怎么用。

TTS 对外只做三件事：

1. 本地播放一段文本。
2. 合成一段音频并把 PCM 返回给调用方。
3. 控制播放、任务和音色克隆缓存。

## 1. 公开能力

| 能力 | PayloadType | Payload | 用途 |
|---|---|---|---|
| `TTS_SPEAK` | `TTS_TEXT` | `TtsSpeakPayload` | 合成文本并在本地客户端播放。 |
| `TTS_SYNTHESIZE` | `TTS_TEXT` | `TtsSynthesisRequestPayload` | 只合成音频，通过协议响应返回 PCM，不由 TTS 播放。 |
| `TTS_CONTROL` | `CUSTOM` | `TtsControlPayload` | 停止、取消、重载模型、导入、加载或卸载音色。 |

旧的 `TTS_ALERT` 不再存在。提醒、预警、插话和打断都通过 `TTS_SPEAK` 的 `placement` 字段表达。

## 2. 本地播放

如果你只是想让 TTS 在玩家客户端说一句话，用 `TTS_SPEAK`。

```java
TtsSpeakPayload payload = new TtsSpeakPayload(
    "前方发现敌对生物。",
    0,
    0L,
    TtsPlaybackPlacement.INSERT_AFTER_SENTENCE,
    ""
);

adapter.commandCapability(
    ProtocolCapabilities.TTS_SPEAK,
    PayloadType.TTS_TEXT,
    payload
);
```

`TtsSpeakPayload` 字段：

| 字段 | Java 类型 | 怎么填 |
|---|---|---|
| `text` | `String` | 要说的文本。 |
| `turnId` | `int` | 调用方自己的轮次 id。没有就填 `0`。 |
| `sessionId` | `long` | 调用方自己的会话 id。没有就填 `0L`。 |
| `placement` | `TtsPlaybackPlacement` | 播放策略，见下一节。 |
| `voiceStyle` | `String` | 音色 id。不用指定音色就填空字符串 `""`。使用音色克隆时填已加载的 `voiceId`。 |

## 3. 播放策略

`placement` 决定本次播报遇到已有播报时怎么处理。

| `TtsPlaybackPlacement` | 行为 | 适合场景 |
|---|---|---|
| `DROP_IF_BUSY` | 忙碌时丢弃本次请求。 | 过期就没意义的提示。 |
| `QUEUE_AFTER_SESSION` | 排到当前会话后面。 | 普通聊天回复、普通播报。 |
| `INSERT_AFTER_SESSION` | 插到当前会话后、普通队列前。 | 不打断当前会话，但希望尽快播报。 |
| `INSERT_AFTER_SENTENCE` | 当前句子播完后插队。 | 普通提醒、轻量预警。 |
| `CANCEL_SENTENCE_AND_PLAY` | 取消当前句子并播放新请求，不取消当前会话后续句子。 | 较高优先级提醒。 |
| `CANCEL_SESSION_AND_PLAY` | 取消当前会话剩余内容并播放新请求。 | 用户打断助手回复、强预警。 |

## 4. 只合成音频

如果你的模组要自己控制声音播放位置，例如 NPC 头顶、实体 3D 声源、方块声源或跨端同步，不要用 `TTS_SPEAK`，应使用 `TTS_SYNTHESIZE`。

```java
TtsSynthesisRequestPayload payload = new TtsSynthesisRequestPayload(
    "maid-line-001",
    "主人，前方很危险。",
    true,
    30_000L,
    "maid_default"
);

adapter.commandCapability(
    ProtocolCapabilities.TTS_SYNTHESIZE,
    PayloadType.TTS_TEXT,
    payload
);
```

`TtsSynthesisRequestPayload` 字段：

| 字段 | Java 类型 | 怎么填 |
|---|---|---|
| `requestId` | `String` | 调用方生成的请求 id。为空时 TTS 会使用协议信封 id。 |
| `text` | `String` | 要合成的文本。 |
| `streaming` | `boolean` | 是否允许分片返回音频。 |
| `ttlMillis` | `long` | 任务存活时间，单位毫秒。最小值为 `1000L`。 |
| `voiceStyle` | `String` | 音色 id。不指定就填 `""`。 |

响应 payload 是 `TtsAudioPayload`：

| 字段 | Java 类型 | 说明 |
|---|---|---|
| `requestId` | `String` | 对应的请求 id。 |
| `audio` | `byte[]` | PCM 音频数据。 |
| `sampleRate` | `int` | 采样率。 |
| `channels` | `int` | 声道数。 |
| `chunkIndex` | `int` | 分片序号，从 `0` 开始。 |
| `last` | `boolean` | 是否为最后一个分片。 |

调用方拿到 `TtsAudioPayload` 后自行播放。TTS 不知道你的实体位置，也不会帮你做 3D 声源。

## 5. 音色克隆怎么接入

先区分两个概念：

| 概念 | 传什么 | 走哪个字段 |
|---|---|---|
| 要说的话 | 文本 | `TtsSpeakPayload.text` 或 `TtsSynthesisRequestPayload.text` |
| 克隆音色的参考声音 | 音频文件 | `TtsControlPayload.voiceSample` |

当前协议不支持在 `TTS_SPEAK` 或 `TTS_SYNTHESIZE` 里直接传参考音频。音色样本只在 `TTS_CONTROL` 阶段处理。

TTS 提供两种音色入口：

| 场景 | Action | 传什么 |
|---|---|---|
| 参考音频已经在 TTS voice library 目录里 | `LOAD_VOICE` | `voiceSample` 填文件名或目录内路径。 |
| 联动模组把参考音频放在自己 jar/resource 里 | `IMPORT_VOICE` | `voiceId` 和音频 `byte[]`。 |

### 5.1 准备参考音频

参考音频建议使用短 WAV 文件，例如 `maid_default.wav`。

文件需要位于 `config.getVoiceLibraryPath()` 指向的目录内。玩家可以在 TTS 设置页打开这个目录；同进程联动模组如果能访问天枢服务，也可以通过 `TtsVoiceLibraryService.importVoiceSample(Path source)` 导入文件，并拿到导入后的文件名。

对玩家或配置侧已有文件来说，最简单的方式是：

1. 让用户或模组把 `maid_default.wav` 放到 TTS voice library 目录。
2. 发送 `LOAD_VOICE`，其中 `voiceSample` 填文件名 `maid_default.wav`。
3. 后续播报或合成时，`voiceStyle` 填同一个 `voiceId`。

### 5.2 加载音色

```java
TtsControlPayload payload = new TtsControlPayload(
    TtsControlPayload.Action.LOAD_VOICE,
    "maid_default",
    "maid_default.wav",
    "主人，前方很危险。",
    "load maid voice"
);

adapter.commandCapability(
    ProtocolCapabilities.TTS_CONTROL,
    PayloadType.CUSTOM,
    payload
);
```

这段代码里的字段含义：

| 字段 | Java 类型 | 示例 | 说明 |
|---|---|---|---|
| `action` | `TtsControlPayload.Action` | `LOAD_VOICE` | 加载音色。 |
| `voiceId` | `String` | `"maid_default"` | 你给这个音色起的 id。 |
| `voiceSample` | `String` | `"maid_default.wav"` | voice library 目录里的参考音频文件名。 |
| `referenceText` | `String` | `"主人，前方很危险。"` | 参考音频里实际说的文本。ZipVoice 这类后端会用到。 |
| `reason` | `String` | `"load maid voice"` | 日志和调试用说明。 |

`voiceSample` 不是 Java 类型名，也不是音频对象。它就是一个 `String`，通常填文件名。TTS 会拒绝 voice library 目录外的文件。

### 5.3 从联动模组 jar/resource 导入音色

如果你的模组把参考音频打包在自己的 jar 里，例如：

```text
assets/create/tianshu/voice/wrench.wav
```

不要把资源路径直接交给 TTS。TTS common 层不会读取别的模组 jar，也不依赖 NeoForge 资源系统。资源归属方应该自己读取资源，然后通过协议把音频字节交给 TTS。

```java
byte[] audio;
try (InputStream input = CreateMod.class.getResourceAsStream(
        "/assets/create/tianshu/voice/wrench.wav"
)) {
    if (input == null) {
        throw new IllegalStateException("voice resource not found");
    }
    audio = input.readAllBytes();
}

TtsControlPayload payload = new TtsControlPayload(
    TtsControlPayload.Action.IMPORT_VOICE,
    "create:wrench",
    audio
);

adapter.commandCapability(
    ProtocolCapabilities.TTS_CONTROL,
    PayloadType.CUSTOM,
    payload
);
```

`IMPORT_VOICE` 字段：

| 字段 | Java 类型 | 示例 | 说明 |
|---|---|---|---|
| `action` | `TtsControlPayload.Action` | `IMPORT_VOICE` | 导入音色样本。 |
| `voiceId` | `String` | `"create:wrench"` | 后续 `voiceStyle` 引用的音色 id。 |
| `voiceAudio` | `byte[]` | `audio` | 模组自己从 jar/resource 读取出来的音频文件内容。 |

如果后端需要参考文本，可以使用可选构造器：

```java
TtsControlPayload payload = new TtsControlPayload(
    TtsControlPayload.Action.IMPORT_VOICE,
    "create:wrench",
    audio,
    "扳手已经准备好了。"
);
```

TTS 收到后会：

1. 用协议信封里的 `sourceId` 作为 owner。
2. 校验音频大小。
3. 根据音频头和 `voiceId` 生成安全文件名。
4. 写入 voice library 的 owner 子目录。
5. 立刻注册为可用 `voiceId`。

外部模组不需要知道最终落盘路径，也不应该依赖这个路径。

### 5.4 使用音色

本地播放：

```java
TtsSpeakPayload payload = new TtsSpeakPayload(
    "主人，前方发现敌对生物。",
    0,
    0L,
    TtsPlaybackPlacement.INSERT_AFTER_SENTENCE,
    "maid_default"
);
```

只合成音频：

```java
TtsSynthesisRequestPayload payload = new TtsSynthesisRequestPayload(
    "maid-warning-001",
    "主人，前方发现敌对生物。",
    true,
    30_000L,
    "maid_default"
);
```

这里的 `"maid_default"` 必须是之前 `LOAD_VOICE` 成功加载过的 `voiceId`。

### 5.5 卸载音色

```java
TtsControlPayload payload = new TtsControlPayload(
    TtsControlPayload.Action.UNLOAD_VOICE,
    "maid_default",
    "",
    "",
    "unload maid voice"
);
```

清理当前模块加载的所有音色：

```java
TtsControlPayload payload = new TtsControlPayload(
    TtsControlPayload.Action.CLEAR_VOICE_CACHE,
    "",
    "",
    "",
    "clear voices"
);
```

## 6. 控制播放和任务

```java
TtsControlPayload payload = new TtsControlPayload(
    TtsControlPayload.Action.STOP,
    "maid-warning-001",
    "cancel warning"
);
```

`TtsControlPayload` 完整字段：

| 字段 | Java 类型 | 用途 |
|---|---|---|
| `action` | `TtsControlPayload.Action` | 控制动作。 |
| `targetRequestId` | `String` | 要停止的请求 id。 |
| `targetSource` | `String` | `STOP_SOURCE` 使用的来源模块 id。 |
| `reason` | `String` | 停止原因。 |
| `voiceId` | `String` | 音色 id，供 `LOAD_VOICE / UNLOAD_VOICE` 使用。 |
| `voiceSample` | `String` | 参考音频文件名，供 `LOAD_VOICE` 使用。 |
| `referenceText` | `String` | 参考音频文本，供 `LOAD_VOICE / IMPORT_VOICE` 使用。 |
| `voiceAudio` | `byte[]` | 参考音频文件内容，供 `IMPORT_VOICE` 使用。 |

控制动作：

| Action | 行为 |
|---|---|
| `STOP_CURRENT` | 停止当前正在播放的本地播报。 |
| `STOP` | `targetRequestId` 为空时停止全部；非空时停止指定请求，也可取消纯合成任务。 |
| `STOP_SOURCE` | 停止指定来源的本地播报。 |
| `RELOAD_MODEL` | 重载 TTS 模型。 |
| `LOAD_VOICE` | 加载音色克隆 profile。 |
| `IMPORT_VOICE` | 从调用方传入的音频字节导入音色样本，并立刻加载。 |
| `UNLOAD_VOICE` | 卸载指定音色。 |
| `CLEAR_VOICE_CACHE` | 清理当前模块加载的音色缓存。 |

## 7. 状态 Topic

TTS 会发布 `TTS.PLAYBACK` topic，payload 为 `TtsPlaybackStatusPayload`。

对外状态只有三种：

| 状态 | 说明 |
|---|---|
| `IDLE` | 当前没有本地播报。 |
| `SPEAKING` | 正在普通播报。 |
| `ALERTING` | 正在播放插话、提醒或打断式播报。 |

这个 topic 是模块级状态，不暴露内部 session 阶段。

## 8. 接入建议

- 普通系统播报：用 `TTS_SPEAK`。
- NPC 或实体配音：用 `TTS_SYNTHESIZE`，拿 PCM 后自己按位置播放。
- 玩家或配置侧音色克隆：先 `LOAD_VOICE`，再在 `voiceStyle` 填 `voiceId`。
- 联动模组内置音色克隆：先读取 jar/resource 为 `byte[]`，用 `IMPORT_VOICE` 导入，再在 `voiceStyle` 填 `voiceId`。
- 不要在 `TTS_SPEAK` 和 `TTS_SYNTHESIZE` 里传参考音频。
- 不要依赖 TTS 内部 session 状态，只使用公开 payload、能力和 topic。
