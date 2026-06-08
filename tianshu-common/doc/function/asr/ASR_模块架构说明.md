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
- 唤醒词后处理
- 识别结果过期保护
- 流式 session 生命周期管理

目前它已经不再是单纯的“一个 shared queue + 若干标志位”的实现，而是围绕流式 runtime 做了更清晰的生命周期封装。

#### 流式识别原则

- 一个流式 session 对应一个独立 runtime
- stop 会关闭 runtime，并唤醒阻塞的处理线程
- 结果发布前会再次确认 runtime 仍然有效
- 过期 session 的结果不会继续向外发布

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
- 在高通滤波 / RNNoise 等 `AudioFrameProcessor` 之后执行轻量语音活动检测
- 只在有效语音活动状态变化时发布 `INPUT.ASR_SPEECH_ACTIVITY`
- 统一清理录音状态
- 保持 PTT 与流式输入共享同一条处理后音频链路

`ASR_SPEECH_ACTIVITY` 表示处理后音频中检测到用户正在说话，不表示按键按下、麦克风开始采集或流式 session 已启动。这样 IA、TTS、AX 等下游模块可以把它当作真实说话活动信号。

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
采集音频 -> 高通/RNNoise -> 语音活动检测 -> 累计处理后音频 -> complete recognition -> AsrRecognitionResult -> final text topic
```

#### 流式识别

适用于连续输入与边说边识别场景。

```text
streaming session -> 高通/RNNoise -> 语音活动检测 -> chunk feed -> endpoint / flush -> normalized text -> final text topic
```

## 5. 协议输出

ASR 的结构化结果会发布到协议中心的 final text 主题，供 IR / LLM / UI 等后续模块消费。

ASR 不是协议中心本身，它只是协议中心中的一个上游生产者。

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
   - 高通、RNNoise、轻量活动检测位于采集到识别之间；后续可以替换为更完整的 VAD 后端。

6. **流式会话必须可隔离**
   - 不能让旧 command、旧 session、旧结果串到新流里。

## 8. 后续可继续推进的方向

ASR 模块后续最值得继续做的方向有：

- 把流式 runtime 再进一步明确成单一会话对象，减少共享状态
- 为 RNNoise / VAD 实现更完整的后端，并替换当前轻量活动检测器
- 检查 ASR final text 到 IR / LLM / TTS 的端到端时序语义
- 当 client / GUI 重构完成后，再把新的输入语义接上去

## 9. 结论

ASR 模块现在已经从“旧控制流残留 + 散乱耦合”进入到“宿主化模块 + 识别服务 + 协议输出”阶段。

它还不是最终完全收口的版本，但已经具备一个健康的可继续演进基础。
