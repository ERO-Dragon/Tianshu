# CoreManager 说明书

## 1. 封版定位

`TianshuCoreManager` 是天枢运行时的核心宿主，封版后的定位是：

```text
稳定、模块无关、功能黑盒化的核心运行时宿主。
```

它不再是 ASR、LLM、TTS、IR、GUI 或 Client 的功能总管，也不承担协议中心的消息分发职责。它的职责边界被固定为：

- 创建核心运行时基础设施
- 创建并持有协议运行时
- 委托模块装配器装配模块
- 托管模块生命周期
- 维护核心宿主生命周期状态
- 暴露核心状态与运行时能力状态
- 响应宿主级 `startRuntimeSession`、`refreshRuntime`、`stopRuntimeSession`、`destroy` 命令

CoreManager 可以在没有任何功能模块的情况下启动和运行。功能模块全部视为外部可选能力，CoreManager 本身不依赖具体功能模块。

## 2. 核心边界

### 2.1 CoreManager 负责的内容

CoreManager 负责宿主层面的运行时编排：

- 构建 `ProtocolRuntime`
- 构建 `ModuleRuntimeState`
- 构建 `TianshuModuleHost`
- 构建 `CoreModuleLifecycleCoordinator`
- 构建环境准备、模型准备等宿主级辅助服务
- 启动、刷新、重启、销毁模块生命周期
- 查询核心生命周期状态
- 查询运行时能力状态
- 中断当前运行时处理

四个生命周期入口统一返回 `CompletableFuture<CoreRuntimeStatus>`。调用线程只提交命令，不同步执行模块的模型、磁盘、native engine 或 checkpoint 工作。

### 2.2 CoreManager 不负责的内容

CoreManager 不负责以下内容：

- ASR 引擎创建、初始化、关闭
- LLM 服务进程、推理、流式输出
- TTS 引擎、播放队列、语音库
- IR 指令解析与平台指令实现
- ChatAssistant 业务编排
- GUI 组件状态
- Client 脚本解释
- 协议 topic 广播
- 协议 capability 投递
- 协议 response 处理器投递
- 协议任务队列调度细节

这些职责属于 function、client 或 protocol 层，不属于 core 宿主层。

## 3. 无模块运行语义

CoreManager 默认使用空模块装配器。也就是说，如果没有传入任何模块装配工厂，CoreManager 仍然可以完成自身构造、初始化和销毁。

无模块运行时的语义是：

- CoreManager 可以启动
- 协议运行时可以创建
- 生命周期协调器可以工作
- 状态查询可以工作
- 能力列表为空或没有对应能力
- 不会因为 ASR、LLM、TTS、IR 缺失而失败

这保证了 core 是真正的宿主，而不是绑定某组产品功能的启动器。

## 4. 生命周期模型

CoreManager 的生命周期由 `CoreModuleLifecycleCoordinator` 统一协调。

核心阶段包括：

- `CREATED`
- `INITIALIZING`
- `RUNNING`
- `REFRESHING`
- `FAILED`
- `DESTROYING`
- `DESTROYED`

模块生命周期包括：

- `register`
- `prepare`
- `start`
- `stop`
- `destroy`
- `unregister`

其中 `register/prepare/start/stop/destroy` 是 `TianshuManagedModule` 回调；`unregister` 是宿主通过协议运行时撤销模块注册的清理阶段，不是模块接口上的额外回调。

CoreManager 自身只发起生命周期推进，不解释具体模块的业务含义。

### 4.1 生命周期线程边界

`CoreModuleLifecycleCoordinator` 使用独立的单线程 `CoreLifecycleCommandQueue` 串行执行模块生命周期。该 worker 只负责宿主生命周期，不负责功能协议任务，也不替代 `ProtocolExecutorManager`：

- 功能模块通信、broker、capability/topic 和执行 lane 继续由 `ProtocolRuntime` / `ProtocolExecutorManager` 负责。
- Core lifecycle worker 只执行模块装配与 `register/prepare/start/stop/destroy/unregister`。
- 独立 worker 避免把“关闭 ProtocolRuntime executor”的终态动作提交给被关闭的 executor 自己。
- 状态查询使用线程安全快照，不要求 Minecraft 主线程等待重型生命周期锁。

### 4.2 世界会话重入

退出世界调用 `stopRuntimeSession()`，它会清理当前模块但保留 `ProtocolRuntime`。再次进入世界调用 `startRuntimeSession()`，必须重新装配新的模块实例并完整执行生命周期。

快速发生的 `start -> stop -> start`、refresh 与 destroy 由同一个命令队列确定顺序；旧会话的迟到 refresh 不得污染新会话，终态 destroy 后不得再次启动。

模块生命周期注册和模块内部的业务注册不是一回事。CoreManager 只负责把模块纳入生命周期托管，不负责模块的语音关键词注册、热词表更新、ASR 热词文件重载或具体协议业务处理。语音关键词应由模块通过协议/语音注册入口主动声明和更新，再由语音资源层与 ASR 模块处理后续热词物化和引擎重载。

## 5. refresh 语义

封版后，core 不再使用 `llmChanged` 这类产品语义参数。

刷新和重启使用通用运行时原因：

- `MANUAL`
- `ENVIRONMENT_READY`
- `RESOURCE_CHANGED`
- `RESTART_REQUESTED`

这些原因描述的是宿主层面的刷新触发来源，而不是某个具体功能模块的变化。

`refreshRuntime(reason)` 使用 single-flight 语义：同一轮未完成刷新返回同一个 future，不重复重建模块。世界 stop 或终态 destroy 请求会使尚未开始的旧 refresh 失效。

## 6. 能力模型

Core 层只保留能力机制，不再保留产品能力常量。

Core 层保留：

- `RuntimeCapability`
- `RuntimeCapabilityRegistry`
- `RuntimeCapabilityState`
- `RuntimeCapabilityStatus`

具体产品能力定义放在各自 function 模块侧，例如：

- `AsrRuntimeCapabilities.INPUT`
- `LlmRuntimeCapabilities.CHAT`
- `TtsRuntimeCapabilities.PLAYBACK`

CoreManager 只提供通用能力查询，不提供产品语义门面。

例如 CoreManager 保留：

- 查询整体状态
- 查询某个能力是否 ready
- 查询某个能力状态快照

CoreManager 不再提供：

- `canAcceptVoiceInput()`
- `canProcessConversation()`
- `canPlayTts()`

这些产品语义判断应由 function 或 client 层自行组合能力状态。

## 7. 协议中心边界

`ProtocolRuntime` 是协议中心，不是 CoreManager 的一部分。

CoreManager 可以创建并持有 `ProtocolRuntime`，但不作为协议中心门面暴露完整协议运行时。

装配器拿到的是 `TianshuModuleAssemblyContext.moduleRuntime()`，其类型为 `ModuleRuntimeAccess`。该端口只组合三类受控能力：

- `ModuleProtocolAccess`：登记 capability/topic/response、提交 envelope 和访问受控 voice trigger 入口。
- `ModuleExecutionAccess`：按 `ProtocolTaskSpec` submit 或 schedule 任务。
- `ProtocolRegistrationView`：只读查询 capability provider 与 topic subscriber，不返回 registry、handler 或 executor。

`ProtocolRuntime` 和 `ProtocolExecutorManager` 仍分别由 Core/Protocol 内部持有。装配器和功能模块不得从上下文取得 lifecycle store、dead-letter、broker、cancellation、executor manager 或其他协议内部对象。

协议相关职责属于 protocol 层：

- topic 注册与订阅
- capability route 注册
- response handler 注册
- envelope 生命周期
- dead letter
- storm guard
- cancellation
- executor lane
- broker 分发
- runtime interrupt envelope 构造与投递

CoreManager 的 runtime interrupt 只表达“宿主要中断当前处理”，真正的协议 envelope 构建由 `ProtocolRuntime` 通过 `RuntimeInterruptPublisher` 完成。

## 8. 模块上下文边界

模块不通过 CoreManager 获取完整协议中心。

模块生命周期上下文提供的是窄接口：

- `ModuleRegistrationContext.protocol()`
- `ModuleRuntimeContext.protocol()`

其类型为 `ModuleProtocolAccess`，用于模块注册协议能力、订阅 topic、提交协议任务等。

这避免模块通过生命周期上下文拿到协议中心全部内部对象，降低模块与协议中心实现细节的耦合。

## 9. 失败状态保留

CoreManager 在生命周期失败时会清理活动运行资源，但不会抹掉最后的失败状态快照。

这意味着：

- required 模块失败后，core 会进入失败状态
- 模块失败信息仍可通过状态快照观察
- capability failure 信息不会因为清理流程立刻消失

资源清理和失败诊断被分离，避免出现“失败后被清空，看不到失败原因”的问题。

## 10. 销毁语义

`destroy()` 提交终态命令并返回同一个完成 future；重复调用不会重复销毁。命令按宿主顺序释放资源：

1. 标记核心进入销毁阶段
2. 销毁模块生命周期
3. 关闭协议运行时
4. 清理事件队列
5. 标记核心已销毁

协议运行时关闭会进一步关闭协议执行器资源。

`stopRuntimeSession()` 与 `destroy()` 不同：前者回到 `CREATED / IDLE` 并允许下一次世界会话重新装配，后者进入不可恢复的 `DESTROYED`。

## 11. 封版结论

当前 CoreManager 可以作为 core 宿主封版。

封版理由：

- CoreManager 已不依赖具体功能模块
- CoreManager 默认无模块也能运行
- CoreManager 不再暴露完整协议中心门面
- CoreManager 不再保留 ASR / LLM / TTS 产品语义 API
- Core refresh / restart 已去产品化
- runtime interrupt 已交回协议侧构建和投递
- 模块上下文使用协议窄接口
- 模块失败状态可保留用于诊断
- 世界退出/重进使用后台串行生命周期命令，不占用 Minecraft 主线程执行模块清理
- Core lifecycle worker 与 Protocol executor 的职责互不重叠
- destroy 链包含协议运行时关闭

封版后允许继续演进 function、client、GUI、具体模块实现，但不应再把具体产品功能重新塞回 CoreManager。
