# TTS 模块架构说明

### 快速摘要

- 两条语音链路

  - 播放链路把文本组织成语音 Session，并在本地直接播放给玩家。
  - 纯合成链路只生成音频并返回给调用方，适合后台准备音频或交给其他功能继续处理。

- 文本与 Session

  - 完整文本可以一次提交并由 TTS 分句；已经分好句子的模块也可以逐句送入；任意文本流则由 TTS 跨片段整理句子。
  - AX 使用已经分好句子的输入，TTS 会保持这些句子的边界，不再重新做语义分句。
  - 首句立即使用流式模式开始合成；后续只使用已经完整到达的连续句子，并在 MOSS tokenizer 上限内尽可能保留更多语义上下文。TTS 根据当前剩余播放时长、首音耗时和稳定 RTF 一次决定句组大小与完整/流式模式，只有预计首音或持续生成赶不上播放时才缩小句组。
  - 排队方式、音色、speaker 和语速只在一个 Session 第一次进入时确定，后续句子不会让同一 Session 重新排队或改变参数。

- 播放顺序与打断

  - 新 Session 可以选择忙时放弃、普通排队、当前 Session 后插入、当前句结束后插入、取消当前句后播放，或取消整个当前 Session 后播放。
  - 插入和打断策略只在新 Session 第一次进入时判断一次，排入队列后不会按每个句子重复判断。
  - 如果 A 被 B 插入、B 又被 C 插入，C 播完后先恢复 B，再恢复 A，保证嵌套打断的顺序稳定。

- 纯合成

  - 播放和纯合成共享同一个模型，但各自拥有独立的任务身份和取消结果。
  - 一句话或一段话完整合成后作为一个请求交付；内部上下文分组不会泄露为外部分块。
  - 每个完整 PCM 请求必须由调用方 ACK 接管。完成交付队列满时暂停启动后续纯合成，确认后继续；不会因为背压自动丢弃内容。
  - 普通播放插入不会取消纯合成，只有显式停止、任务超时、模块停止或真实失败才会结束对应合成任务。

- 音色与状态

  - 玩家可以选择全局默认音色、speaker 和语速，调用方也可以在自己的请求中覆盖这些参数。
  - 明确指定但尚未加载的音色会直接失败，不会静默换成另一种声音。
  - 进入世界后 TTS 会自动加载已选模型并尽早完成默认音色预热；健康和失败通过模块状态公开，真实加载与非 AX 任务通过产品活动公开。

本文面向 TTS 维护者。外部模块接入方式见 [TTS_协议中心使用文档.md](TTS_协议中心使用文档.md)。

## 1. 职责与边界

TTS 负责两条业务链：

- `TTS_SPEAK`：把文本组织成播放 Session，并在本地客户端播放。
- `TTS_SYNTHESIZE`：生成一份完整 PCM 并通过一次 `TTS_AUDIO` 返回给调用方，不介入播放位置和 3D 声源；调用方接收后发送 `TTS_AUDIO_ACK` 完成音频所有权交接。

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

一个句子只有在 `IAudioBridge` 报告播放结束后才形成安全边界。后续句的 PCM 可以提前准备，但不会仅因“合成完毕”而越过该边界；插入、取消和恢复仍以真正播放句界生效。

## 4. 播放与纯合成双链路

播放和纯合成共享一个非线程安全模型后端，但取消语义独立：

- 播放 placement 不会隐式取消 `TTS_SYNTHESIZE`。
- 纯合成以整个 `requestId` 作为交付和所有权单元；长文本可按后端上下文能力形成内部推理组，最终合并成一个 PCM。
- 已开始的纯合成推理组不会被普通播放中断；安全边界后若有播放等待，播放先执行，纯合成之后继续。
- 只有显式 STOP、TTL、模块停止或真实失败会终止纯合成任务。
- `TtsSynthesisScheduler` 为每个原子工作记录 owner；取消播放句只在该句仍占用 backend 时中断，不能误中断正在执行的纯合成。
- TTL 在排队和单个长句推理期间都有效；到期时只中断对应纯合成 owner。
- 活跃纯合成的 `requestId` 必须唯一，重复 id 在 admission 时结构化拒绝，不能覆盖旧任务的取消身份。
- 后端可以采用 full 或 streaming 推理模式，但协议只返回一次合并 PCM，不公开 chunkIndex 或 terminal。MOSS 纯合成遇到连续重复音频码帧时，只在后端内部换 Seed 重试一次；两次都失败才让完整请求失败，不交付残缺音频。
- 调用方以相同 sourceId 和 requestId 发送一次 `TTS_AUDIO_ACK`；ACK 后原请求完成，表示调用方已接管完整 PCM。
- 已完成待 ACK 请求进入有界交付队列。队列满时不再启动新的纯合成，待执行请求保持排队；ACK、STOP、TTL、模块停止和真实失败会释放相应请求槽位。
- 等待外部 ACK 不阻塞 TTS backend lane，其他可执行的播放工作仍可获得模型资源。

这与 LLM 的 CHAT/TASK 双链路相同：共享执行资源，不共享业务取消语义。

## 5. 状态边界

`TTS_SPEAK` 的协议 complete 只表示校验和 admission 完成，不等待玩家听完。

公开状态分两层：

- `TTS.PLAYBACK`：模块级 `IDLE / SPEAKING / ALERTING`。
- `TTS.REQUEST_STATUS`：请求级 `QUEUED / PLAYING / COMPLETED / CANCELLED / FAILED`，携带稳定的 requestId、sourceId、sessionId 和 turnId。
- `PRESENCE.ACTIVITY`：玩家可感知的产品活动。真实模型准备使用 `tts.model.load / LOADING`；非 AX 播放和纯合成使用 `tts.request.<requestId> / PROCESSING_TASK`。

纯合成只有在 `TtsSynthesisTaskCoordinator` 真正取得执行机会时才开始产品活动，排队拒绝不产生短暂忙碌状态。完成、显式取消、TTL 过期、失败和模块 stop 都通过同一终态回调结束活动。AX 来源不由 TTS 发布 `RESPONDING`，回复阶段只由 AX 根据 IA delivery 和首段可见输出控制。

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
| MOSS 流式 codec 解码 | `TTS_FAST` |
| 播放桥 IO | `AUDIO_IO` |
| 模型初始化/切换/关闭 | `MODEL_LOAD` |
| 延迟触发 | `SCHEDULED` |

合成和模型生命周期使用同一个 `module.tts:backend` concurrency key。即使 lane 不同，shutdown 也必须等待当前原子推理返回，不能在 ORT run 中并发关闭 session。

## 8. MOSS 性能敏感边界

- `MossModelRuntime` 单一持有 ORT environment、session、tokenizer 和 manifest。
- `MossTensorState` 直接接管 global KV past 的 `OnnxTensor` handle；不得把 global past 恢复成 `getValue()`、Java 数组复制和 tensor 重建。
- local cached-step 因结果生命周期约束保留现有 clone，不与 global past 交接混为一谈。
- 自回归生成在 `TTS_AUTOREGRESSIVE` 运行；流式 codec 通过 TTS 窄执行端口提交到协议托管的 `TTS_FAST`，backend 不持有私有线程或线程池。
- streaming decoder 由单一消费者独占，固定累计四个生成 frame 解码一次；生成侧与 codec 侧通过有界批次队列并行，队列满时背压等待，不丢帧、不覆盖帧。
- 帧生成明确返回 `NATURAL_END`、`CANCELLED` 或 `FRAME_LIMIT_REACHED`。只有自然结束才 flush 最后不足四帧的尾批；达到 `max_new_frames` 但没有自然结束时以 `GENERATION_LIMIT_REACHED` 失败，参数包含输入 token、已生成帧数和帧数上限。
- MOSS 长文本按句子和子句边界组织，并使用真实 tokenizer 保证每个内部推理块不超过上下文上限；单个超长无标点文本也会按 token 安全边界继续拆分，不使用固定字符阈值。
- `interrupt()` 的取消信号同时进入自回归帧循环和 codec 消费端；返回前等待 decoder 退出并释放状态，不能与下一请求或模型关闭重叠。
- backend shutdown 调用 `MossTtsService.close()`，并由 backend 资源键保证不与推理并发。
- 默认参考音频在自动加载阶段预编码并缓存。
- MOSS ORT 自回归推理固定使用 2 个线程。该值来自固定四帧流水线的 RTF 与首音延迟实测；不会因为 JVM 可见的逻辑处理器更多而盲目增加，避免与游戏和 codec 任务争用 CPU。

性能验收分别记录冷启动、默认音色预加载、首包和稳定推理。RTF 只统计预热后的稳定推理，目标必须小于 1。2026-07-27 固定四帧流水线实测同一文本两轮：两线程配置首音为 `528 / 525 ms`、RTF 为 `0.8395 / 0.8009`；四线程参考配置首音为 `597 / 532 ms`、RTF 为 `0.8707 / 0.8413`，没有改善，因此生产配置固定两线程。

## 9. 模型下载和诊断

TTS 复用 model 域下载能力，不重写 transport：

- Hugging Face 仓库按 repo/revision 拼接特定文件，并支持 HF Mirror 降级。
- GitHub archive 支持 proxy 到 direct URI 降级。
- 下载使用 staging、完整性校验、暂停/继续/取消和原子提交，半成品目录不可被选为模型。

TTS 原文、模型、音色和播放诊断只进入宿主集中诊断服务。Debug 构建中由设置页右下角的全局 Debug 开关统一控制结构化诊断；所有天枢运行日志和已启用的诊断事件由 Client 写入独立的 `logs/tianshu-diagnostics.log`，backend 不自行创建日志文件或线程。

## 10. 配置和 GUI

配置统一由宿主的 `config/tianshu-client.toml` 提供。common 只依赖只读 `TtsConfiguration`；client 设置页通过 `TtsModuleService`、`TtsModelService` 和快照工作，不穿透 backend。

`TtsModelService.saveSettings` 必须向调用方传递模型参数写盘错误。模型参数先写同目录临时文件，再替换正式文件（文件系统支持时使用原子替换），避免直接截断原有配置。设置会话只在持久化成功后接受草稿并执行运行时更新；统一配置保存失败时，恢复先前已写入的模型参数和内存配置。

试听默认文本来自语言资源，不在 Java/config 中固定某种语言。已删除未使用的 `ttsPort`。未来 Qwen/Fish 等后端通过新的 model/backend descriptor 接入，由玩家在 GUI 显式选择，运行时不静默切换。

## 11. 验收重点

- 一个 Session 无论多少句都只 admission 一次。
- A/B/C 嵌套严格按 C、B、A 恢复。
- AX 句子流、完整文档和 RAW stream 都能正确结束。
- placement 不取消纯合成；纯合成在句间让出后继续。
- 播放取消只中断属于该播放句的 backend work；长句 TTL 和重复 requestId 有确定终态。
- stop/destroy、退出重进、模型切换没有旧回调和资源泄漏。
- MOSS handle 交接、三种生成终态、固定四帧 cadence、串并行 PCM 一致性、取消和预热后 RTF 无回归。
- 正式 jar 不包含 smoke 类、测试 WAV 或生成音频。
