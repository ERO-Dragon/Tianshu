# ASR 模块架构说明

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

- hotword 路径选择
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
- 选择 hotwords 文件
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
- 提交输入
- 取消输入
- 响应运行时中断

控制器内部会根据 `TriggerMode` 决定：

- PTT 模式如何开始和结束
- 连续识别模式如何启动和 flush
- 是否允许在未就绪状态下接受输入

### 3.6 AsrRecognitionService

`AsrRecognitionService` 是识别执行层。

它负责：

- 完整识别
- 流式识别
- 结果归一化
- 识别结果过期保护
- 流式 session 生命周期管理

ASR 不再做唤醒词截断或归属判断。识别层只输出完整文本，wake word 修复、匹配与仲裁归 IR/IA 处理。

目前它已经不再是单纯的“一个 shared queue + 若干标志位”的实现，而是围绕流式 runtime 做了更清晰的生命周期封装。

#### 流式识别原则

- 一个流式 session 对应一个独立 runtime
- stop 会关闭 runtime，并唤醒阻塞的处理线程
- 结果发布前会再次确认 runtime 仍然有效
- 过期 session 的结果不会继续向外发布
- 连续输入的产品语义不依赖底层模型是否支持在线识别：手动按键可以提前提交，VAD 结束当前语音段时也会自动提交。
- 每次提交后回到等待状态，必须检测到下一次新的语音活动才开始累计下一段；没有新音频的重复提交不会产生识别广播。
- 在线和离线模型都由手动提交或 VAD 分段边界触发最终识别；底层在线模型只负责持续接收音频，不再绕过统一边界直接发布文本。

这能避免旧流和新流串线。

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
- 区分 PTT 录音与流式录音
- 在高通滤波 / RNNoise 等 `AudioFrameProcessor` 之后，在连续输入路径执行轻量语音活动检测
- 只在连续输入中、有效语音活动状态变化时发布 `INPUT.ASR_SPEECH_ACTIVITY`
- 通过同一个自适应 VAD 分段器向识别层提供 `START_SEGMENT` / `END_SEGMENT` 边界
- 统一清理录音状态
- 保持 PTT 与流式输入共享同一条处理后音频链路

`ASR_SPEECH_ACTIVITY` 表示处理后音频中检测到用户正在说话，不表示按键按下、麦克风开始采集或流式 session 已启动。这样 IA、TTS、AX 等下游模块可以把它当作真实说话活动信号。

当前 VAD 保留以下策略：噪声底动态估计、开始/结束双门限滞回、最短语音时长、噪声底上限恢复，以及随当前语音时长缩短的静音结束门限（短语音约 450 ms、中等语音约 350 ms、长语音约 250 ms）。VAD 只在检测到新的语音后开始新的段，静音结束只触发一次自动提交。

### 3.9 AsrModelService / AsrPreviewCoordinator

`AsrModelService` 保留模型查询、下载、删除和预览的稳定 public facade。预览的计时、取消、音频采集和一次性资源释放已下沉到 `AsrPreviewCoordinator`。

预览生命周期遵守：

- prepare 与识别运行在单并发 `ASR_STREAM` lane。
- 约 5 秒录音窗口由 scheduled timer 计时；timer 只回投 finish task，不执行音频或推理。
- 录音窗口期间不占用 `ASR_STREAM` lane。
- `stopPreview()` 只请求取消并投递后台 finish，不在 GUI 或 Minecraft 调用线程直接停止音频桥。
- stop、timer、executor rejection 和模块关闭共享一次性 finish gate，临时预览引擎只关闭一次。
- 普通采集、识别和 native linkage failure 会保留 cause；严重 JVM error 不会被 `catch (Throwable)` 降级。
- common 只向 `PreviewCallback.onError` 提交稳定资源键；NeoForge ASR 页面负责按当前语言本地化，底层 cause 只进入技术日志。
- 使用当前共享引擎时预览不拥有引擎；使用指定模型时，临时引擎由 preview operation 创建并在 session 结束时关闭。

### 3.10 下载与运行时失败边界

`AsrModelDownloadCoordinator.DownloadSession` 是单次下载的 pause/cancel owner。暂停使用 session 私有 condition monitor；resume 和 cancel 主动唤醒 waiter，不再通过固定 `Thread.sleep` 轮询。底层 Hugging Face、HF Mirror、GitHub 与 proxy 降级链仍由 downloader 负责，coordinator 不复制下载策略。

ASR 运行时只把普通 `RuntimeException` 和 native `LinkageError` 当作可恢复的模块失败或资源关闭失败：

- 音频 stop/release 会记录原 cause，并继续尝试其余独立资源。
- recognizer shutdown 先清空 Java ownership，再分别尝试释放 online/offline handle，避免一个 release 失败阻断另一个。
- 引擎初始化失败继续映射到原有 capability 和 module status 语义。
- 其他严重 JVM `Error` 不会被宽泛捕获和伪装成普通 ASR failure。

这些规则属于内部健壮性边界，不改变 ASR 的 topic、payload、宿主服务方法或玩家操作流程。

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

当前识别链路分为两类：

#### 完整识别

适用于 PTT 收束后的单次识别。

```text
采集音频 -> 高通/可用音频处理 -> 累计处理后音频 -> complete recognition -> AsrRecognitionResult -> final text topic
```

#### 流式识别

适用于连续输入场景。连续输入的段边界由手动提交或 VAD 自动提交决定，不向外暴露 partial/interim 文本。

```text
streaming session -> 高通/RNNoise -> VAD 等待新语音 -> 累计当前段 -> 手动提交或动态静音结束 -> complete/online flush -> final text topic
```

底层在线模型和离线模型都在同一段边界上产生最终文本。下游不需要区分这两种模型，只消费最终文本事件。

### 4.3 模型预览链路

```text
GUI preview intent
  -> AsrModelService
  -> AsrPreviewCoordinator start task (ASR_STREAM)
  -> startRecording
  -> scheduled 5s window (ASR_STREAM free)
  -> finish task (ASR_STREAM)
  -> stopRecording -> recognizeComplete -> callback
```

主动停止走同一 finish task；不会从 GUI/MC 调用线程直接触碰音频桥。

## 5. 协议输出

ASR 的结构化结果会发布到协议中心的 final text 主题，供 IR / LLM / UI 等后续模块消费。

ASR 不是协议中心本身，它只是协议中心中的一个上游生产者。

当前稳定协议还包括 speech activity 和 `module.asr` 模块状态。ASR 不提供公共请求 capability；`AsrInputService` 与 `AsrModelService` 是同一宿主内的窄端口，不应被外部模块当作跨模块协议。完整 topic、payload、字段和订阅示例见 [ASR 协议中心使用文档](ASR_协议中心使用文档.md)。

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
   - 高通、可用的 RNNoise 后端和轻量活动检测位于采集到识别之间；RNNoise 当前仍是预留接口，VAD 的动态分段策略已经作为连续输入正式路径使用。

6. **流式会话必须可隔离**
   - 不能让旧 command、旧 session、旧结果串到新流里。

7. **预览等待不能占用识别 lane**
   - 录音窗口必须通过受控 timer 与 session gate 管理，音频和识别只在后台 lane 执行。

## 8. 后续可继续推进的方向

ASR 模块后续最值得继续做的方向有：

- 把流式 runtime 再进一步明确成单一会话对象，减少共享状态
- 为 RNNoise 接入实际后端；只有在现有自适应 VAD 的实测效果不足时，才评估替换检测后端
- 检查 ASR final text 到 IR / LLM / TTS 的端到端时序语义
- 当 client / GUI 重构完成后，再把新的输入语义接上去

## 9. 结论

ASR 模块现在已经从“旧控制流残留 + 散乱耦合”进入到“宿主化模块 + 识别服务 + 协议输出”阶段。

它还不是最终完全收口的版本，但已经具备一个健康的可继续演进基础。

## 10. 配置边界

ASR common 只依赖只读 `AsrConfiguration`，读取启用状态、触发模式、麦克风、音频预处理开关、模型标识和 ASR 模块根。配置值仍由 NeoForge 的单一 `config/tianshu-client.toml` 提供；ASR 不创建配置文件，也不保存 GUI 状态。模型标识解析和模型文件检查留在 ASR/model 域，其他模块不能通过 ASR 配置端口读取或修改设置。

启用 ASR 时，设置页必须同时存在有效且已完整下载的模型；未选择模型或模型未安装时保存失败并提示先下载、选择模型。禁用 ASR 时可以保留空模型选择。
