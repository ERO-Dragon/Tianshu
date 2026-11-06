# ASR 模块架构说明

### 快速摘要

- 核心功能

  - 将玩家的麦克风语音转换为最终文本。
  - 只有识别得到有效文本后，才发布 `INPUT_ASR_FINAL_TEXT` topic。

- 统一音频处理

  - 按键说话采集的音频会先经过统一的音频处理链路。
  - 当前实际使用高通滤波；RNNoise 降噪仍是预留功能，尚未接入实际后端。

- 按键说话

  - 只采集玩家按住语音键期间的音频。
  - 松开语音键后提交识别，不使用自动语音分段。

- 连续输入

  - 连续输入和自适应 VAD 已封存，不由当前客户端运行时或设置页启动。
  - 相关流式识别类保留为内部代码，不能视为当前产品输入契约。

- 设置与测试

  - 玩家可以选择麦克风和识别模型。
  - 提供独立试听功能，用于检查麦克风与模型是否正常，不介入正式语音链路。

## 1. 模块定位

ASR 模块是天枢语音输入链路的前端入口，负责把麦克风音频转换为结构化的语音识别结果，再交给协议中心与后续功能模块处理。

ASR 模块不直接承担 UI 逻辑，也不直接依赖旧式 EventBus 控制流。它面向 common 层的模块宿主、运行时能力和协议中心工作。

## 2. 当前边界

ASR 模块当前主要由以下部分组成：

- [AsrModule](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrModule.java)
- [AsrModuleInstaller](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrModuleInstaller.java)
- [AsrRuntimeCapabilities](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrRuntimeCapabilities.java)
- [AsrEngineBootstrap](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/engine/AsrEngineBootstrap.java)
- [AsrController](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/control/AsrController.java)
- [AsrRecognitionService](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/recognition/AsrRecognitionService.java)
- [AsrSessionManager](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/session/AsrSessionManager.java)
- [AudioCaptureService](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/audio/AudioCaptureService.java)
- [AsrInputService](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/input/AsrInputService.java)
- [AsrInputGateway](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/input/AsrInputGateway.java)
- [AsrProtocolAdapter](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrProtocolAdapter.java)
- [AsrModelService](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrModelService.java)
- [AsrPreviewCoordinator](file:///d:/Minecraft/Tianshu/tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrPreviewCoordinator.java)

跨模块调用方应以 [ASR 协议中心使用文档](ASR_协议中心使用文档.md) 为稳定对外契约；本架构说明用于解释内部职责，不替代协议接入文档。

## 3. 模块职责划分

### 3.1 AsrModule

`AsrModule` 现在承担的是模块生命周期装配根职责。

它负责：

- 注册模型服务
- 注册模型下载/准备服务
- 注册 ASR 输入服务
- 在 prepare 阶段装配引擎、控制器、状态机、会话管理和识别服务
- 订阅运行时中断协议
- 在 destroy 阶段清理资源

它不再承担：

- 模型信息解析
- 引擎初始化细节
- 模型类型适配的具体决策

这些职责已经下沉到 `AsrEngineBootstrap`。

### 3.2 AsrModuleInstaller

`AsrModuleInstaller` 是模块侧的安装入口。

它的作用是把 ASR 模块作为一个可装配单元交给模块宿主，而不是让外层直接 new 和散装注册。

这符合当前 core 的模块解耦模式：

- 模块自带能力声明
- 模块自带安装逻辑
- 平台侧只负责组合

### 3.3 AsrRuntimeCapabilities

ASR 的运行时能力定义在模块本地。

当前 ASR 只对外暴露一个核心能力：

- `capability.asr.input`

这代表 ASR 输入链路是否已经可用。

能力状态由宿主运行时注册表管理，典型状态包括：

- `INSTALLED`
- `READY`
- `FAILED`
- `DISABLED`
- `ABSENT`

### 3.4 AsrEngineBootstrap

`AsrEngineBootstrap` 是 ASR 引擎启动边界。

它负责：

- 判断模型路径是否存在
- 解析模型目录对应的模型信息
- 初始化 `AsrEngine`
- 标记 capability ready / failed
- 处理模型缺失与引擎异常

这个类的目的，是把“引擎如何启动”从“模块如何被宿主管理”中分离出来。

### 3.5 AsrController

`AsrController` 是语义输入控制器。

它负责处理的是“人类意图”，而不是底层录音细节。

当前支持的语义动作包括：

- 开始输入
- 结束输入
- 停止手势（短按 ≤250ms）：丢弃本轮录音并请求中断，不进入识别
- 取消输入（模块释放麦克风时由宿主调用，不是平台手势）
- 响应运行时中断（经协议中心直接调用，不走输入意图）

当前控制器固定使用 PTT（按键说话）：按下语音开关开始采集，松开后提交一次完整识别；未就绪时拒绝输入并发布等待状态。原连续识别入口已封存，不再由客户端运行时或设置页暴露。

短按是停止手势：控制器丢弃本次录音、关闭聆听标记，不提交识别；随后模块通过 IA 的 `DIALOGUE.INTERRUPT` capability 请求中断广播，由 IA 用与伙伴标记相同的当前目标计算重新裁定目标。阈值固定为模块常量，不进入设置；短按不产生 `ASR_TEXT` 或 `IR_RESULT`。

### 3.6 AsrRecognitionService

`AsrRecognitionService` 是识别执行层。

它负责完整识别、结果归一化和识别结果过期保护。流式 runtime 仍保留在实现中，但当前 PTT 入口不会创建它。

ASR 不再做唤醒词截断或归属判断。识别层只输出完整文本，wake word 修复、匹配与仲裁归 IR/IA 处理。

流式 runtime 的生命周期隔离规则仍由识别服务自身维护，供后续重新启用连续输入时复用。

### 3.7 AsrSessionManager

`AsrSessionManager` 负责记录当前激活会话和 turn 序号。

它的作用不是做复杂状态机，而是提供轻量的会话一致性判断：

- 当前 session 是否仍然有效
- 当前 turn id 如何递增
- 运行时中断后如何重置 active session

### 3.8 AudioCaptureService

`AudioCaptureService` 是音频采集边界。

它的职责是：

- 封装 `IAudioBridge`
- 采集按键按住期间的 PTT 音频
- 在高通滤波 / RNNoise 等 `AudioFrameProcessor` 之后累计处理后的音频
- 统一清理录音状态
- 连续输入与 VAD 采集接口保留在封存实现中，不属于当前运行时链路

### 3.9 AsrModelService / AsrPreviewCoordinator

`AsrModelService` 保留模型查询、下载、删除和预览的稳定 public facade。预览的计时、取消、音频采集和一次性资源释放已下沉到 `AsrPreviewCoordinator`。

预览生命周期遵守：

- prepare 与识别运行在单并发 `AUDIO_INPUT_STREAM` lane。
- 约 5 秒录音窗口由 scheduled timer 计时；timer 只回投 finish task，不执行音频或推理。
- 录音窗口期间不占用 `AUDIO_INPUT_STREAM` lane。
- `stopPreview()` 只请求取消并投递后台 finish，不在 GUI 或 Minecraft 调用线程直接停止音频桥。
- stop、timer、executor rejection 和模块关闭共享一次性 finish gate，临时预览引擎只关闭一次。
- 普通采集、识别和 native linkage failure 会保留 cause；严重 JVM error 不会被 `catch (Throwable)` 降级。
- common 只向 `PreviewCallback.onError` 提交稳定资源键；设置页负责按当前语言本地化，底层 cause 只进入技术日志。
- 预览始终为所选模型创建临时引擎：由 preview operation 创建并在 session 结束时关闭，共享识别引擎不参与试听。

### 3.10 下载与运行时失败边界

`AsrModelDownloadCoordinator.DownloadSession` 是单次下载的 pause/cancel owner。暂停使用 session 私有 condition monitor；resume 和 cancel 主动唤醒 waiter，不再通过固定 `Thread.sleep` 轮询。底层 Hugging Face、HF Mirror、GitHub 与 proxy 降级链仍由 downloader 负责，coordinator 不复制下载策略。

ASR 运行时只把普通 `RuntimeException` 和 native `LinkageError` 当作可恢复的模块失败或资源关闭失败：

- 音频 stop/release 会记录原 cause，并继续尝试其余独立资源。
- recognizer shutdown 先清空 Java ownership，再分别尝试释放 online/offline handle，避免一个 release 失败阻断另一个。
- 引擎初始化失败继续映射到原有 capability 和 module status 语义。
- 其他严重 JVM `Error` 不会被宽泛捕获和伪装成普通 ASR failure。

这些规则属于内部健壮性边界，不改变 ASR 的 topic、payload、宿主服务方法或玩家操作流程。

### 3.11 模型目录

`asr_model.json` 是模块随包发布的模型目录，和回响的 TTS 目录一样按角色收口：每个角色只留一个当前最优的条目，而不是把上游仓库的模型全列出来。目录现在有三个条目：

| 条目 | 架构 / 模式 | 语言 | 质量 / 性能 | 定位 |
| --- | --- | --- | --- | --- |
| `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09` | SenseVoice / 离线 | zh、en、ja、ko、yue | 9 / 8 | 默认模型，带 ITN，适合 PTT 整句识别 |
| `sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10` | Transducer / 流式 | ar、en、id、ja、ru、th、vi、zh | 8 / 7 | 唯一的流式条目，服务封存的连续输入扩展位 |
| `sherpa-onnx-whisper-turbo` | Whisper / 离线 | zh、en、ja、ko、fr、de、ru、es | 9 / 5 | 法语/德语/俄语/西班牙语等目录内唯一覆盖 |

维护规则：

- 新条目必须补上它独占的语言族或模式；同语言族、同模式、质量与性能都不占优的条目不再加入目录（此前俄语 6 个、语义重复的 whisper 与 sensevoice 条目就是这一类）。
- 目录顺序有意义：`IntakeConfig.defaultModelName()` 在玩家从未选过模型时先找已下载模型，再取「当前界面语言对应的第一个目录项」。第一条是 SenseVoice，所以中文与英文客户端默认落到它；PTT 只做整句识别，默认项必须是离线模型。
- 卡片上的「流式」「Int8 量化」标签由目录字段推导（`isStreaming`、displayName/文件名里的 int8），目录不再声明 tags。
- `fileRoles` 决定下载哪些文件；`downloadUrl` 为空时按 Hugging Face 仓库逐文件下载。

## 4. 当前链路

### 4.1 输入链路

当前 ASR 输入链路是：

```text
外部语音输入意图
  -> AsrInputGateway
  -> AsrController
  -> AudioCaptureService / AsrRecognitionService
  -> AsrProtocolAdapter
  -> 协议中心
  -> 下游模块
```

### 4.2 识别链路

当前识别链路只有 PTT 完整识别：

#### 完整识别

适用于 PTT 收束后的单次识别。

```text
采集音频 -> 高通/可用音频处理 -> 累计处理后音频 -> complete recognition -> AsrRecognitionResult -> final text topic
```

连续输入的流式识别链路已封存；其实现仍保留在识别服务中，但没有客户端输入入口。

### 4.3 模型预览链路

```text
GUI preview intent
  -> AsrModelService
  -> AsrPreviewCoordinator start task (AUDIO_INPUT_STREAM)
  -> startRecording
  -> scheduled 5s window (AUDIO_INPUT_STREAM free)
  -> finish task (AUDIO_INPUT_STREAM)
  -> stopRecording -> recognizeComplete -> callback
```

主动停止走同一 finish task；不会从 GUI/MC 调用线程直接触碰音频桥。

## 5. 协议输出

ASR 的识别文本会发布到 `INPUT.ASR_FINAL_TEXT`；文本修复后的结构化分析由同模块的 IR 发布到 `IR.RESULT`，供 IA 等后续模块消费。

ASR 不是协议中心本身，它只是协议中心中的一个上游生产者。

当前稳定协议还包括 speech activity、`module.asr` 模块状态和 `PRESENCE.ACTIVITY` 产品活动。PTT 按下开始采集时发布 `speaking=true` 与 `LISTENING`；本轮产生文本时，`LISTENING` 由思衡在仲裁给出结果时结束，只有没录到音频、识别失败、取消、运行时打断和模块停止才由 ASR 发布 `speaking=false` 与结束。思衡据此在开口时冻结情境。这只表示按键边界，不代表启用 VAD 自动分段。ASR 不提供公共请求 capability；`AsrInputService` 与 `AsrModelService` 是同一宿主内的窄端口，不应被外部模块当作跨模块协议。完整 topic、payload、字段和订阅示例见 [ASR 协议中心使用文档](ASR_协议中心使用文档.md)。

## 6. 运行时能力语义

ASR 能力状态当前是模块可观测、可管理的。

典型语义是：

- 模块注册后，能力处于 installed
- 引擎成功初始化后，能力变为 ready
- 初始化失败时，能力变为 failed
- 模块销毁时，能力应被清理

这比旧式的“单纯 boolean ready flag”更适合宿主化管理。

## 7. 当前设计重点

当前这套 ASR 架构最重要的几个原则是：

1. **模块根尽量薄**
   - `AsrModule` 负责装配，不负责深层业务策略。

2. **能力与注册解耦**
   - 用模块本地 capability 文件和 installer 文件组织入口。

3. **输入语义与底层采集分离**
   - 控制器负责意图，采集服务负责音频。

4. **识别与协议边界分离**
   - 识别结果走协议中心，而不是直接耦合下游模块。

5. **保留音频管线扩展位**
   - 高通和可用的 RNNoise 后端位于采集到识别之间；RNNoise 当前仍是预留接口。当前 PTT 路径不启用 VAD，避免底噪波动影响输入边界。

6. **流式会话必须可隔离**
   - 不能让旧 command、旧 session、旧结果串到新流里。

7. **预览等待不能占用识别 lane**
   - 录音窗口必须通过受控 timer 与 session gate 管理，音频和识别只在后台 lane 执行。

## 8. 后续可继续推进的方向

ASR 模块后续最值得继续做的方向有：

- 为 RNNoise 接入实际后端；若未来重新启用连续输入，再评估 VAD 后端的实测效果
- 检查 ASR final text 到 IR / LLM / TTS 的端到端时序语义
- 当 client / GUI 重构完成后，再把新的输入语义接上去

## 9. 结论

ASR 模块现在已经从“旧控制流残留 + 散乱耦合”进入到“宿主化模块 + 识别服务 + 协议输出”阶段。

它还不是最终完全收口的版本，但已经具备一个健康的可继续演进基础。

## 10. 配置边界

ASR common 只依赖只读 `AsrConfiguration`，读取启用状态、麦克风、音频预处理开关、模型标识和 ASR 模块根。PTT 是固定输入方式，不通过配置保存触发模式。功能设置由模块自己的 `ModuleConfigStore` 提供，落在 `config/Tianshu/module/module.asr.properties`；模型目录与 IR 索引缓存位于同级的 `config/Tianshu/module/asr/`（`model/`、`index/`）。ASR 不读写宿主 TOML，也不保存 GUI 状态。模型标识解析和模型文件检查留在 ASR/model 域，其他模块不能通过 ASR 配置端口读取或修改设置。

启用 ASR 时，设置页必须同时存在有效且已完整下载的模型；未选择模型或模型未安装时保存失败并提示先下载、选择模型。禁用 ASR 时可以保留空模型选择。
