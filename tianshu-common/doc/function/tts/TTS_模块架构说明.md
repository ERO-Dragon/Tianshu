# TTS 模块架构说明

### 快速摘要

- 两条语音链路

  - 播放链路把文本组织成语音 Session，并在本地直接播放给玩家。
  - 纯合成链路只生成音频并返回给调用方，适合后台准备音频或交给其他功能继续处理。

- 文本与 Session

  - 完整文本可以一次提交并由 TTS 分句；已经分好句子的模块也可以逐句送入；任意文本流则由 TTS 跨片段整理句子。
  - AX 使用已经分好句子的输入，TTS 会保持这些句子的边界，不再重新做语义分句。
  - 排队方式、音色、speaker 和语速只在一个 Session 第一次进入时确定，后续句子不会让同一 Session 重新排队或改变参数。

- 播放顺序与打断

  - 新 Session 可以选择忙时放弃、普通排队、当前 Session 后插入、当前句结束后插入、取消当前句后播放，或取消整个当前 Session 后播放。
  - 插入和打断策略只在新 Session 第一次进入时判断一次，排入队列后不会按每个句子重复判断。
  - 如果 A 被 B 插入、B 又被 C 插入，C 播完后先恢复 B，再恢复 A，保证嵌套打断的顺序稳定。

- 纯合成

  - 播放和纯合成共享同一个模型，但各自拥有独立的任务身份和取消结果。
  - 已经开始的纯合成句会完整生成；句子结束后可以暂时让等待播放的语音先执行，随后继续剩余合成。
  - 普通播放插入不会取消纯合成，只有显式停止、任务超时、模块停止或真实失败才会结束对应合成任务。

- 音色与状态

  - 玩家可以选择全局默认音色、speaker 和语速，调用方也可以在自己的请求中覆盖这些参数。
  - 明确指定但尚未加载的音色会直接失败，不会静默换成另一种声音。
  - 进入世界后 TTS 会自动加载已选模型并尽早完成默认音色预热；加载、播放、合成和请求终态都会通过模块状态公开。

本文面向 TTS 维护者。外部模块接入方式见 [TTS_协议中心使用文档.md](TTS_协议中心使用文档.md)。

## 1. 职责与边界

TTS 负责两条业务链：

- `TTS_SPEAK`：把文本组织成播放 Session，并在本地客户端播放。
- `TTS_SYNTHESIZE`：生成 PCM 并返回给调用方，不介入播放位置和 3D 声源。

`TtsModule` 只负责生命周期和协议装配；跨模块通信只经过协议中心。模型推理、文本分句、文件和音频 IO 都进入协议 lane，不占用 Minecraft 主线程。

| 层 | 主要类型 | 职责 |
|---|---|---|
| 模块与协议 | `TtsModule`、`TtsProtocolAdapter` | 注册能力，转换公开 payload，发布状态。 |
| 文本接入 | `TtsSpeechInputAssembler` | 把完整文档、已分句流、原始文本流统一成句子事件。 |
| 媒体焦点 | `TtsSpeechSessionCoordinator` | 首包 admission、active、挂起栈、普通队列和 Session 终态。 |
| 播放运行时 | `TtsRuntime` | 冻结 Session 参数，调度句子合成、播放、取消和状态。 |
| 纯合成 | `TtsSynthesisTaskCoordinator` | 管理 TTL、requestId 取消、full/stream PCM 和句间让出。 |
| 后端资源 | `TtsSynthesisScheduler`、`TtsModelLifecycleCoordinator` | 串行占用同一个 TTS backend 资源键。 |
| 播放桥 | `TtsPlaybackController` | 仅在 `AUDIO_IO` lane 串行执行 start/feed/finish/stop。 |
| 音色 | `TtsVoiceCloneRegistry`、`TtsVoiceLibraryService` | 管理参考音频、owner 和结构化音色覆盖。 |
| 后端 | `TtsBackend`、`MossTtsBackend`、`SherpaOnnxTtsBackend` | 执行具体模型推理。 |

## 2. 文本和 Session 模型

公开输入分三类：

| `TtsTextInputMode` | 输入含义 | 分句责任 |
|---|---|---|
| `DOCUMENT` | 一次提交完整文本并结束 Session | TTS 分句。 |
| `SENTENCE_STREAM` | 每个 chunk 已是完整句子 | 调用方分句；TTS 只规范化。 |
| `RAW_TEXT_STREAM` | chunk 可以在任意字符处截断 | TTS 跨 chunk 缓冲分句。 |

`COMMAND` 只接受 `DOCUMENT`。`STREAM_CHUNK/STREAM_END` 只接受两种 stream mode。AX 使用 `SENTENCE_STREAM`，因此 AX 的完整句子不会被 TTS 再次做语义分句。

逻辑 Session 身份是 `sourceId + sessionId + turnId`；没有业务 sessionId 的文档请求使用 trace/envelope 身份。不同模块的相同数字 id 不冲突。

placement、协议优先级、音色、speaker 和语速只在首包 admission 时冻结。后续 chunk 即使携带不同值也不能重新排队或改变当前 Session 参数。

## 3. 媒体焦点调度

`TtsSpeechSessionCoordinator` 同时只允许一个 Session 持有焦点：

- `QUEUE_AFTER_SESSION` 进入有界普通队列；同类请求按协议优先级、再按 FIFO 排序。
- `INSERT_AFTER_SESSION` 在当前 Session 完整结束后、普通队列前执行。
- `INSERT_AFTER_SENTENCE` 等当前播放句真正结束后挂起旧 Session。
- `CANCEL_SENTENCE_AND_PLAY` 取消正在播放的句子，但保留旧 Session 后续句子。
- `CANCEL_SESSION_AND_PLAY` 取消当前 Session 的全部剩余内容。
- `DROP_IF_BUSY` 忙时丢弃整个新 Session，并忽略其后续 chunk 直到 `STREAM_END`。

所有 placement 共用同一个有界 pending Session 容量，紧急插入不能绕过容量持续占用开放流。句界插入绑定 admission 当时的目标 Session，不使用全局插入队列；目标 Session 被挂起或取消时，其插入子链仍会被恢复或提升，不会错误打断后来获得焦点的 Session，也不会成为无终态的悬挂状态。

挂起 Session 使用后进先出恢复。A 被 B 插入、B 被 C 插入时，顺序固定为 C 完成后恢复 B，再恢复 A。策略只在新 Session 首包到达时判断一次。

一个句子只有在 `IAudioBridge` 报告播放结束后才形成安全边界。下一播放 Session 不会仅因上一个句子“合成完毕”就越过该边界。

## 4. 播放与纯合成双链路

播放和纯合成共享一个非线程安全模型后端，但取消语义独立：

- 播放 placement 不会隐式取消 `TTS_SYNTHESIZE`。
- 纯合成长文本按句子形成原子工作单元。
- 已开始的纯合成句不会被普通播放中断；句子结束后若有播放等待，播放先执行，纯合成之后继续。
- 只有显式 STOP、TTL、模块停止或真实失败会终止纯合成任务。
- `TtsSynthesisScheduler` 为每个原子工作记录 owner；取消播放句只在该句仍占用 backend 时中断，不能误中断正在执行的纯合成。
- TTL 在排队和单个长句推理期间都有效；到期时只中断对应纯合成 owner。
- 活跃纯合成的 `requestId` 必须唯一，重复 id 在 admission 时结构化拒绝，不能覆盖旧任务的取消身份。
- full 模式最终仍返回合并 PCM；stream 模式保持递增 chunkIndex 和单独 terminal chunk。

这与 LLM 的 CHAT/TASK 双链路相同：共享执行资源，不共享业务取消语义。

## 5. 状态边界

`TTS_SPEAK` 的协议 complete 只表示校验和 admission 完成，不等待玩家听完。

公开状态分两层：

- `TTS.PLAYBACK`：模块级 `IDLE / SPEAKING / ALERTING`。
- `TTS.REQUEST_STATUS`：请求级 `QUEUED / PLAYING / COMPLETED / CANCELLED / FAILED`，携带稳定的 requestId、sourceId、sessionId 和 turnId。

内部 `TtsSessionState` 仅服务于物理句子的合成与播放，不是外部协议契约。

## 6. 音色与参数覆盖

公开请求使用 `TtsVoiceOptions`：

- `voiceId` 为空：使用当前模型和 TTS 设置页的默认音色。
- `speed` 为空：使用模型设置的默认语速。
- `speakerId` 为空：使用模型设置的默认 speaker。
- 非空字段覆盖默认值，并在 Session 首包冻结。

显式 `voiceId` 必须已经通过 `LOAD_VOICE` 或 `IMPORT_VOICE` 注册。不存在时结构化失败，不能静默回退成另一声音。

音色 id 有 owner。另一个 sourceId 不能覆盖或卸载已有 id；导入失败时不会留下未注册文件。TTS 设置页负责全局默认音色、语速和多 speaker 模型的默认 speaker。AX 当前继承全局默认；`AXOutputSettings.ttsVoiceOptions()` 保留结构化模块覆盖端口。

## 7. 生命周期、线程和世界重进

进入世界后：

1. `prepare` 只装配服务和 runtime，不同步加载模型。
2. `start` 让 runtime 接受请求，并在默认 1 秒后提交 TTS 自动加载。
3. LLM 默认 3 秒后提交自动加载。
4. 两者共用协议中心的单线程 `MODEL_LOAD` lane；TTS 在 1 秒时先提交，加载超过 2 秒时 LLM 仍只会排队，不会并行占用重模型资源。

退出世界时取消尚未触发的自动加载，停止 Session、纯合成和播放，并使旧 generation 的回调失效。模块持有的 runtime 和已加载 backend 可以跨世界复用，避免重进世界时重复构造重资源；重新进入世界只创建新的运行会话，不复用旧 Session、旧回调或旧音频。若退出时模型仍在加载，下一次 `prepare` 会合并到同一加载结果并接收新的 capability 状态，不会因旧操作 busy 被误判失败。只有模块 `destroy`、模型切换或显式重载才关闭或替换 backend/ORT 资源。

| 工作 | Lane |
|---|---|
| 非自回归合成 | `TTS_FAST` |
| 自回归合成 | `TTS_AUTOREGRESSIVE` |
| 播放桥 IO | `AUDIO_IO` |
| 模型初始化/切换/关闭 | `MODEL_LOAD` |
| 延迟触发 | `SCHEDULED` |

合成和模型生命周期使用同一个 `module.tts:backend` concurrency key。即使 lane 不同，shutdown 也必须等待当前原子推理返回，不能在 ORT run 中并发关闭 session。

## 8. MOSS 性能敏感边界

- `MossModelRuntime` 单一持有 ORT environment、session、tokenizer 和 manifest。
- `MossTensorState` 直接接管 global KV past 的 `OnnxTensor` handle；不得把 global past 恢复成 `getValue()`、Java 数组复制和 tensor 重建。
- local cached-step 因结果生命周期约束保留现有 clone，不与 global past 交接混为一谈。
- streaming decoder 固定累计四个生成 frame 解码一次。
- `interrupt()` 的取消信号进入自回归帧循环，取消后不继续生成剩余 frame。
- backend shutdown 调用 `MossTtsService.close()`，并由 backend 资源键保证不与推理并发。
- 默认参考音频在自动加载阶段预编码并缓存。

性能验收分别记录冷启动、默认音色预加载、首包和稳定推理。RTF 只统计预热后的稳定推理，目标必须小于 1。

## 9. 模型下载和诊断

TTS 复用 model 域下载能力，不重写 transport：

- Hugging Face 仓库按 repo/revision 拼接特定文件，并支持 HF Mirror 降级。
- GitHub archive 支持 proxy 到 direct URI 降级。
- 下载使用 staging、完整性校验、暂停/继续/取消和原子提交，半成品目录不可被选为模型。

TTS 原文、模型、音色和播放诊断只进入宿主集中诊断服务，并受 TTS 模块诊断开关控制。backend 不自行创建日志文件或线程。

## 10. 配置和 GUI

配置统一由宿主的 `config/tianshu-client.toml` 提供。common 只依赖只读 `TtsConfiguration`；client 设置页通过 `TtsModuleService`、`TtsModelService` 和快照工作，不穿透 backend。

试听默认文本来自语言资源，不在 Java/config 中固定某种语言。已删除未使用的 `ttsPort`。未来 Qwen/Fish 等后端通过新的 model/backend descriptor 接入，由玩家在 GUI 显式选择，运行时不静默切换。

## 11. 验收重点

- 一个 Session 无论多少句都只 admission 一次。
- A/B/C 嵌套严格按 C、B、A 恢复。
- AX 句子流、完整文档和 RAW stream 都能正确结束。
- placement 不取消纯合成；纯合成在句间让出后继续。
- 播放取消只中断属于该播放句的 backend work；长句 TTL 和重复 requestId 有确定终态。
- stop/destroy、退出重进、模型切换没有旧回调和资源泄漏。
- MOSS handle 交接、四帧 cadence、取消和预热后 RTF 无回归。
- 正式 jar 不包含 smoke 类、测试 WAV 或生成音频。
