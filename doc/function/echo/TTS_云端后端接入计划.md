# 回响：云端语音后端接入计划（Edge TTS）

状态：P0 / P1a / P1b 已完成；P2 进行中：失败码、provider 目录、profile、协议、cloud backend、MP3 解码、Edge transport（JDK WebSocket）均已完成并有测试；**端到端已用真实令牌跑通**（3.12 秒音频 / 1.37 秒耗时）。**v2 令牌已用 Java 实现并与参考实现逐位对齐**（6 个固定时间戳向量），且已用自有令牌跑通端到端（4.85 秒音频 / 2.29 秒耗时）。Selection 接线已完成（`TtsBackendType.CLOUD` + `CloudTtsBackendSelection` + provider 分派 + 引擎状态泛化）。配置与运行时已接通（`backendMode` / `cloudProvider` / `cloudEndpoint` / `cloudVoice` 四个键、`TtsCloudSettings`、快照与变更集、引擎按模式选 selection；删除了失去调用点的 `applyModel`）。设置页 UI 已完成（顶部两值模式切换、云端面板：服务商/端点/音色 + 试听 + 状态行、`previewCloud` 走同一条 exclusive-preview 路径）。打包配置已落地：`jarJarPayload` 配置把 JLayer 嵌到 `META-INF/jarjar/jlayer-1.0.1.jar`，NeoForge 读 `META-INF/jarjar/metadata.json`、Fabric 读 `fabric.mod.json` 的 `jars`；已用 smoke jar 验证嵌套结构（嵌套 88 个条目、`Decoder.class` 在内）。真正跑 `:tianshu-echo:jar` 仍被 `:tianshu-adapter:1.20.2-neoforge` 的网络依赖解析拦住（log4j 动态版本 + 当前无代理）。文档已补齐（架构说明新增「云端后端」一节、协议文档补五个云端失败码、中英 README 改为可选陈述），并新增 `TtsCloudBoundaryTest` 固定四条边界规则。本轮补了两处健壮性：① 预览的恢复目标从模型名改为 selection，云端预览结束后回到云端 selection而不是清空引擎；② 连接在 turn.end 之前关闭现在报 CLOUD_INVALID_RESPONSE，不再把截断音频当成功。本轮补了设置校验：云端模式下不再校验本地模型（否则没下载过模型的玩家无法保存云端配置），改为校验端点协议与音色是否在目录内。Gradle 真实构建已通过（``:tianshu-echo:jar`` BUILD SUCCESSFUL，产物 662 KB，``META-INF/jarjar/jlayer-1.0.1.jar`` 内 88 个条目、``Decoder.class`` 在内；``:tianshu-echo:test`` 355 项 0 失败）。构建顺带抓到一个本地 javac 测不出的问题：模块按 Java 17 编译，而 provider 里用了 Java 21 的 pattern switch，已改为对 ``TtsBackendType`` 的枚举 switch（仍保持穷尽：新增后端类型会编译失败）。产物审计通过：269 个条目，无 Test/Smoke/测试音频/junit；两个 manifest 的占位符已展开；云端 provider 目录与令牌类都在包内；直接用 jar 运行 `EdgeTtsToken.generate(1800000000)` 与目录加载均正确。剩余：游戏内验收（见 §10 清单，需要真实客户端）。当前 355 项测试，349 通过 / 0 失败 / 6 aborted。
本文记录**已确认的外部事实**与**已决定的架构/许可选择**，两者都不再重复讨论。

---

## 1. 目标

给回响加一个**可选的云端语音后端**。设置页顶部用一个两值切换（本地 / 云端）整体切换面板：

- 本地：现有「模型下载 + 本地音色」原样；
- 云端：独立分区，只放 provider / 端点 / 音色 / 试听。

切换只改面板显示，运行时在后端**保存后**才替换（换后端要重新加载，不能在点按钮的瞬间打断正在播的语音）。

---

## 2. 已确认的外部事实（实测，2026-11）

| 项 | 结论 | 证据 |
|---|---|---|
| 直连可用性 | **可用**，不需要代理；代理只是更快 | 参考客户端直连：30 块 / 21,024 字节 / 首包 1.18s / 总 1.47s；走代理：首包 0.64s |
| 音频格式 | **只有 MP3 与 WebM-Opus** | `audio-24khz-48kbitrate-mono-mp3` ✅ 25,029B；`audio-24khz-96kbitrate-mono-mp3` ✅；`webm-24khz-16bit-mono-opus` ✅ 49,223B |
| PCM / WAV | **不支持** | `riff-24khz-16bit-mono-pcm`、`raw-24khz-16bit-mono-pcm`、`raw-16khz-16bit-mono-pcm`、`ogg-…-opus` 全部 0 字节，连接被关闭 |
| 鉴权 | **必须 v2 令牌** | v1（纯 SHA256）+ 新版本号 → 403；v2 → 通过 |
| v2 参数 | `Sec-MS-GEC-Version: 2-143.0.3650.75`、`Cookie: muid=<32 位大写 hex>` | 参考实现常量 |
| 令牌构成 | `<16 位 hex>-<64 位 hex>`，CS64 + SHA256 混合 | 同左 |
| 流式 | **原生流式**，服务端没有“一次性整段”开关 | 连续 `Path:audio` 二进制帧，最后 `turn.end` |

关于“整体合成”：服务端不提供该模式，但对我们等价于“累积到 `turn.end` 再交付”。回响的 `TtsAudioSink` 已经用 `preferredSynthesisMode()`（FULL / STREAMING）与 `delivery()`（PLAYBACK / COMPLETE_RESULT）表达了这个区分，云端后端只需逐块 `sink.accept(...)`。

---

## 3. 已决定的架构与许可选择

| 决定 | 理由 |
|---|---|
| **许可不动**：百工保持 MIT，回响保持 LGPL-3.0 | 改 GPL 会沿链接传染整条生态（北辰/听澜/织言/辅星/映迹 + 任何第三方依赖者），且只解决代码版权，不解决服务条款 |
| **MP3 解码器由回响自带**（JLayer，LGPL-2.1） | 回响是 LGPL-3.0，LGPL-2.1 可并入；百工不引入与本地推理无关的依赖。打包走嵌套 jar：NeoForge `META-INF/jarjar/metadata.json` + Fabric `fabric.mod.json` 的 `jars`，一个 jar 通吃各版本 |
| **v2 令牌独立实现**，不复制 `edge-tts` 代码 | 参考实现是 GPL-3.0，抄进来会把回响拖成 GPL；算法与常量属于事实，独立实现即可。放回响 `synthesis/cloud/EdgeTtsToken.java` |
| **传输层放回响**，暂不上移百工 | 目前只有回响一个消费者；百工的定位是本地推理运行时，不该背外部服务的失效风险。等出现第二个消费者再抽取 |
| 云端作为**实验性可选后端**，默认关闭 | 非官方接口，历史上多次因改令牌/改域名整体失效 |

---

## 4. 架构

### 4.1 选择（Selection）

```
TtsBackendSelection（sealed）
├─ LocalTtsBackendSelection(TtsResolvedModel)   ← 本地模型目录
└─ CloudTtsBackendSelection(TtsCloudProfile)    ← P2 引入
```

- `backendType()`、`label()`；
- sealed 的目的是让分派处的 `switch` 在新增类型时**编译失败**，而不是落进默认分支。

### 4.2 后端接口拆分

```java
interface TtsBackend { isInitialized / sampleRate / contextualSentenceLimit / synthesize / interrupt / shutdown / preloadVoice }
interface LocalTtsBackend extends TtsBackend { boolean initialize(TtsResolvedModel model); }
interface CloudTtsBackend extends TtsBackend { boolean initialize(TtsCloudProfile profile); }
```

`initialize` 从基接口移走，云端后端不会被逼着实现一个自己做不到的方法（避免“默认返回 false”式补丁）。

### 4.3 构造点唯一

`TtsEngineProvider` 是唯一构造后端的地方：

```java
private Candidate candidate(TtsBackendSelection selection) {
    return switch (selection) {
        case LocalTtsBackendSelection local -> {
            TtsBackend created = createBackend(local.backendType());
            yield new Candidate(created, () -> ((LocalTtsBackend) created).initialize(local.model()));
        }
        // P2: case CloudTtsBackendSelection cloud -> ...
    };
}
```

候选后端与其初始化器在同一分支内成对产生，杜绝“后端 A 配初始化器 B”。切换/失败回退/`sameSelection` 比较全部复用现有 `acquire` 的三态逻辑。

### 4.4 引擎与预览

- 引擎内部以 selection 为状态（`loadedSelection`）；`useModel(String)` 保留为本地便利方法。
- 新增 `TtsSynthesisEngine.selectionFor(String modelName)`：把模型名解析成 selection 的唯一入口。
- 预览统一为一条路径：`beginExclusivePreview(requestId, previewSelection, restoreSelection, …)`；当 `previewSelection.equals(currentSelection)` 时**跳过换后端**，只做 stop → submit → finish。云端预览即走这条路径。

### 4.5 云端后端

```
synthesis/cloud/
  TtsCloudProfile.java      record(providerId, endpoint, voiceId, timeoutMillis)
  TtsCloudProviders.java    读取并校验 tts_cloud_providers.json
  EdgeTtsToken.java         v2 令牌（CS64 + SHA256）、muid、版本号常量
  EdgeTtsProtocol.java      URL / SSML / 帧解析（纯函数）
  EdgeTtsTransport.java     接口 open/send/receive/close
  JdkEdgeTtsTransport.java  java.net.http.WebSocket 实现
  Mp3Decoder.java           解码接口（便于测试注入）
  JLayerMp3Decoder.java     JLayer 实现，输出 24 kHz 单声道 PCM16
  CloudTtsBackend.java      CloudTtsBackend 实现
```

- 收到 `Path:audio` 就解码并 `sink.accept(...)`，`turn.end` 结束；
- `interrupt()` 关连接并置取消标志；
- 解码失败 → `CLOUD_INVALID_RESPONSE`；`sampleRate()` 返回 24000，不需要重采样；
- 解码在 `AUDIO_SYNTH_FAST` lane 上执行，不涉及 native 加载闸门。

### 4.6 目录数据（数据，不是常量）

`src/main/resources/.../synthesis/cloud/tts_cloud_providers.json`：

```json
[{ "id": "edge",
   "labelKey": "tianshu.gui.tts.cloud.provider.edge",
   "defaultEndpoint": "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1",
   "defaultVoice": "zh-CN-XiaoxiaoNeural",
   "voices": [
     { "id": "zh-CN-XiaoxiaoNeural", "labelKey": "tianshu.gui.tts.cloud.voice.zh_cn_xiaoxiao" },
     { "id": "zh-CN-YunxiNeural",    "labelKey": "tianshu.gui.tts.cloud.voice.zh_cn_yunxi" }
   ]}]
```

**JSON 内不出现任何中英文文案**，标签一律走 `zh_cn.json` / `en_us.json`。加载严格：重复 id、缺默认音色、空音色表直接抛错。

### 4.7 配置键（`module.tts.properties`）

| 键 | 默认 | 说明 |
|---|---|---|
| `backendMode` | `local` | `local` / `cloud` |
| `cloudProvider` | `edge` | provider id |
| `cloudEndpoint` | 空 | 空 = provider 默认端点 |
| `cloudVoice` | 空 | 空 = provider 默认音色 |

v1 不含密钥字段（Edge 不需要），因此不引入明文存 secret 的问题。

### 4.8 失败码

新增：`CLOUD_UNAVAILABLE`、`CLOUD_AUTH_FAILED`、`CLOUD_RATE_LIMITED`、`CLOUD_TIMEOUT`、`CLOUD_INVALID_RESPONSE`。

| 触发 | 码 |
|---|---|
| DNS / 拒连 / TLS | `CLOUD_UNAVAILABLE` |
| 握手 401/403（令牌刷新重试一次后） | `CLOUD_AUTH_FAILED` |
| 429 | `CLOUD_RATE_LIMITED` |
| 建连 / 读超时 | `CLOUD_TIMEOUT` |
| 帧解析失败 / 解码失败 / 空音频 | `CLOUD_INVALID_RESPONSE` |

界面文案仍按 `tianshu.gui.tts.failure.<code>` 取键，`TtsLanguageCoverageTest` 会自动卡住漏文案。

---

## 5. 阶段

| 阶段 | 内容 | 出口条件 | 状态 |
|---|---|---|---|
| **P0** | 协议可行性实测（格式、令牌、延迟、代理） | 事实落表，见 §2 | ✅ 完成 |
| **P1a** | Selection 抽象：`TtsBackendSelection` / `LocalTtsBackend` / 后端接口拆分 / `TtsEngineProvider.acquire(selection)` / 引擎内部 selection 化 | 本地 294 项测试 0 失败 | ✅ 完成 |
| **P1b** | 协调器与预览统一到 selection（`requestedSelection`；`previewWithSelection`；`selectionFor` 作为唯一名称转换点） | 行为不变，测试仍 0 失败 | ✅ 完成 |
| **P2** | 云端后端：profile、provider 目录、令牌、协议、transport、MP3 解码、backend、失败码 | 单测覆盖协议、令牌向量、五类错误 | 🟡 部分：失败码/目录/profile/协议已完成并有测试；令牌、transport、MP3 解码、backend 待定 |
| **P3** | 设置层：配置键、`CloudSettings`、快照/变更集、面板分支、云端校验与试听 | 切模式面板即变；保存后才换后端 | 未开始 |
| **P4** | 文案与文档：lang、README 可选陈述、架构说明补「云端后端」 | `TtsLanguageCoverageTest` 绿 | 未开始 |
| **P5** | 验收 | 见 §7 | 未开始 |

---

## 6. 测试计划

**新增**
- `TtsCloudProvidersTest`：JSON 解析、重复 id、缺默认音色、空列表。
- `EdgeTtsTokenTest`：**固定时间戳的令牌向量**（由参考实现一次性生成后作为期望值），覆盖 5 分钟取整边界、跨时区无关性。
- `EdgeTtsProtocolTest`：URL/SSML 转义、`Path:audio` 与 `turn.end` 帧解析。
- `CloudTtsBackendTest`：假 transport 分块入 sink、interrupt 关连接、五类异常 → 失败码、解码失败 → `CLOUD_INVALID_RESPONSE`。
- `TtsBackendSelectionTest`、`TtsSettingsChangeSetTest`。
- `TtsCloudBoundaryTest`：`TtsModelService` / `TtsModelResolver` / `download/` 不得出现 cloud 类型引用；后端构造只出现在 `TtsEngineProvider`。

**更新**
- `TtsEngineProviderTest`（selection 化 + cloud 三例）、`AllTtsRtfSmokeTest`、`TtsModelLoadPathBoundaryTest`。

---

## 7. 验收

1. 本地模式：下载、切换模型、试听、播放与改动前一致。
2. 云端模式：断网试听 → 「无法连接云端语音服务」；令牌被拒 → 「云端服务拒绝了本次请求」；限流/超时各有提示。
3. 切换保存后运行时重启一次；切换动作不打断正在播放的语音。
4. 云端模式下不产生任何模型目录、staging 或下载请求。
5. 切回本地，原选中模型与音色仍在。

---

## 8. 验证方式与已知卡点

`.\gradlew.bat :tianshu-echo:test` 当前无法运行：Gradle 在配置阶段挂在 `:tianshu-adapter:1.20.2-neoforge`
（`run type 'client' was not found`），与本模块无关。

替代验证（已验证可行）：

1. `javac` 编译 `tianshu-echo/src/main/java` 与 `src/test/java`（classpath 取各模块 `bin/main` + `libs/tianshu_foundry-*.jar` + junit）；
2. `JUnit Platform Launcher` 选包 `com.rheinmetal.tianshu.function.echo` 运行，工作目录设为 `tianshu-echo`（部分边界测试按相对路径读源码）；
3. classpath 必须包含 `tianshu-echo/src/main/resources` 与 `src/main/lang`，否则 catalog/文案类测试会集体失败。

真正恢复 Gradle 需要二选一：修该适配器的 `maven-fallback` 元数据，或临时把它移出 `settings.gradle`。

---

## 9. 风险

| 风险 | 处理 |
|---|---|
| 端点非官方（逆向 Read Aloud），可能随时失效 | 可选后端、默认关闭、文档标注实验性；`CLOUD_AUTH_FAILED` 让玩家/维护者一眼看出是接口变了 |
| Chromium 版本号变更导致 403 | 版本号只出现在一个常量；失败码区分；文档写明跟版方式 |
| MP3 解码器与其它模组重复类 | 不重定位包名、不修改源码、由加载器/jarJar 裁决；`CloudTtsBackend` 对解码器做可用性探测，缺失时报明确失败码而非 `NoSuchMethodError` |
| 首包慢于本地（直连 1.18s vs MOSS 0.52s） | 文档如实说明：云端换的是「零下载、音质稳定」，不是延迟 |
| ToS | 文档标注实验性，不承诺可用性 |
| 文本出境 | README 改为可选陈述；云端面板明确「需要联网」 |

## 10. 游戏内验收清单

产物：`tianshu-echo/build/libs/tianshu_echo-1.0.0.jar`（662 KB，含嵌套 JLayer）。

1. 把 jar 放进 `mods/`，进世界，打开 **设置 → 模块 →【回响】**。
2. 顶部「合成方式」切到 **云端服务** —— 下半页应当立刻换成云端面板（本地模型 / 下载 / 音色区消失）。
3. 选服务商「Edge 语音」、音色「晓晓（女）」，点 **试听** —— 应能听到中文朗读。
4. 关掉网络后再点 **试听** —— 应显示「无法连接云端语音服务，请检查网络或代理」。
5. 切回 **本地模型** —— 原来的模型选择、语速、音色应当还在；试听应走本地模型。
6. 退出世界再进 —— 「合成方式」应保持上次保存的值。
7. 若同时装了辅星，云端模式下它的回复应走云端音色；切回本地后应恢复本地音色。
