# CoreManager

> 模组：`tianshu-core`。宿主运行时：装配、生命周期、状态与能力机制。
> 它不做任何产品功能，也不做协议分发——协议见 [协议中心](协议中心.md)。

## 目录

- [1. 定位与边界](#1-定位与边界)
- [2. 无模块也能运行](#2-无模块也能运行)
- [3. 生命周期模型](#3-生命周期模型)
- [4. 世界会话与 refresh](#4-世界会话与-refresh)
- [5. 能力机制（不含产品语义）](#5-能力机制不含产品语义)
- [6. 与协议中心的分界](#6-与协议中心的分界)
- [7. 失败状态保留](#7-失败状态保留)
- [8. 销毁语义](#8-销毁语义)
- [9. 装配边界](#9-装配边界)
- [10. 配置与资产归属](#10-配置与资产归属)

## 1. 定位与边界

`TianshuCoreManager` 是运行时宿主：**稳定、模块无关、功能黑盒化**。它不是任何功能的总管，职责固定为七件事：

- 创建核心运行时基础设施
- 创建并持有协议运行时
- 委托装配器装配模块
- 托管模块生命周期
- 维护宿主生命周期状态
- 暴露核心状态与运行时能力状态
- 响应 `startRuntimeSession` / `refreshRuntime` / `stopRuntimeSession` / `destroy`

**不负责**：语音识别引擎、LLM 推理、语音合成与播放、指令解析、对话编排、GUI 状态、协议 topic/capability/response 投递与任务队列。
那些属于功能模组、客户端或协议层。

四个生命周期入口统一返回 `CompletableFuture<CoreRuntimeStatus>`：调用线程只提交命令，不同步执行模块的模型、
磁盘、native 引擎或 checkpoint 工作。

## 2. 无模块也能运行

默认装配器是**空装配器**。没有传入任何模块工厂时，CoreManager 仍然能构造、初始化、销毁：协议运行时能创建、
生命周期协调器能工作、状态查询能工作，能力列表为空，不会因为缺某个功能模组而失败。

这保证了 core 是真正的宿主，而不是绑定某组产品功能的启动器。

## 3. 生命周期模型

核心阶段：`CREATED` → `INITIALIZING` → `RUNNING` ↔ `REFRESHING`，异常进 `FAILED`，终态 `DESTROYING` → `DESTROYED`。

模块阶段：`register` → `prepare` → `start` → `stop` → `destroy`（都是 `TianshuManagedModule` 回调），
最后是 `unregister`——它不是模块接口上的回调，而是宿主通过协议运行时撤销模块注册的清理阶段。

**线程边界**：`CoreModuleLifecycleCoordinator` 用独立的单线程 `CoreLifecycleCommandQueue` 串行执行模块生命周期。

- 这个 worker 只做装配与 `register/prepare/start/stop/destroy/unregister`；
- 协议通信、broker、capability/topic、执行 lane 仍归 `ProtocolRuntime` / `ProtocolExecutorManager`；
- 独立 worker 避免把"关闭协议执行器"的终态动作提交给正在被关闭的执行器自己；
- 状态查询用线程安全快照，不要求 Minecraft 主线程等重型生命周期锁。

## 4. 世界会话与 refresh

- 退出世界调 `stopRuntimeSession()`：清理当前模块，**保留** `ProtocolRuntime`。
- 再进世界调 `startRuntimeSession()`：重新装配新的模块实例，完整走一遍生命周期。
- 快速发生的 `start → stop → start`、refresh 与 destroy 由同一个命令队列定序：旧会话的迟到 refresh
  不得污染新会话；终态 destroy 后不得再启动。
- `refreshRuntime(reason)` 是 single-flight：同一轮未完成刷新返回同一个 future，不重复重建模块。
  世界 stop 或 destroy 会让尚未开始的旧 refresh 失效。
- refresh 原因是宿主级的，不是产品语义：`MANUAL` / `ENVIRONMENT_READY` / `RESOURCE_CHANGED` / `RESTART_REQUESTED`。

模块生命周期注册与模块内部业务注册不是一回事：CoreManager 只把模块纳入生命周期托管，不管关键词表重载、
引擎重载或触发词登记——那些由模块自己通过端口做。

## 5. 能力机制（不含产品语义）

core 只保留机制：`RuntimeCapability`、`RuntimeCapabilityRegistry`、`RuntimeCapabilityState`、`RuntimeCapabilityStatus`。
产品能力定义放在各功能模组侧（`AsrRuntimeCapabilities.INPUT`、`LlmRuntimeCapabilities.CHAT` 等）。

CoreManager 提供**通用查询**：整体状态、某个能力是否 ready、某个能力的状态快照。
不提供 `canAcceptVoiceInput()` 这类产品语义判断——那该由功能层或客户端组合能力状态得出。

## 6. 与协议中心的分界

`ProtocolRuntime` **不是** CoreManager 的一部分；CoreManager 创建并持有它，但不把它当门面暴露。

装配器拿到的是 `TianshuModuleAssemblyContext.moduleRuntime()`，类型为 `ModuleRuntimeAccess`，只组合三类受控能力：

| 端口 | 给什么 |
|---|---|
| `ModuleProtocolAccess` | 登记 capability/topic/response、提交信封、访问受控语音触发入口 |
| `ModuleExecutionAccess` | 按 `ProtocolTaskSpec` submit / schedule 任务 |
| `ProtocolRegistrationView` | 只读查询 capability provider 与 topic subscriber |

装配器与功能模块**拿不到** lifecycle store、dead-letter、broker、cancellation、executor manager 或其它协议内部对象。
宿主的 runtime interrupt 只表达"要中断当前处理"，真正的信封构建由协议侧完成。

## 7. 失败状态保留

生命周期失败时清理活动运行资源，但**不抹掉最后的失败快照**：required 模块失败后 core 进入失败状态，
模块失败信息与 capability 失败信息仍可通过状态快照观察。清理与诊断分离，避免"失败后被清空，看不到原因"。

## 8. 销毁语义

`destroy()` 提交终态命令并返回同一个完成 future，重复调用不会重复销毁。释放顺序：

1. 标记核心进入销毁阶段
2. 销毁模块生命周期
3. 关闭协议运行时（连带关闭协议执行器资源）
4. 清理事件队列
5. 标记核心已销毁

`stopRuntimeSession()` 与 `destroy()` 不同：前者回到可重新装配的状态，后者进入不可恢复的 `DESTROYED`。

## 9. 装配边界

三条规则取代了早期"组合根写死七个 installer"：

1. **模块自注册**：模块在自己的 `@Mod` 构造里调 `TianshuModuleProviderRegistry.register(factory)`；
   内核每个世界会话把工厂实例化成 provider。
2. **两个装配入口**：`TianshuModuleRegistrar` 只有 `addModule(installer)` 与 `addSettings(page)`。
   内置页与外置贡献走同一条路（`ModuleSettingsSourceRegistry`），没有特权路径。
3. **只交端口**：`TianshuModuleAssemblyContext` 给出 `ClientPorts`（scheduler / uiHost / texts / filePicker /
   settingsLayers / audioDevices / configStores / modelLoadQueue / hud / gameContext / debugEnabled）、
   `ModuleRuntimeServices`（findService / requireService / requestRefresh / queryModuleStatuses）与宿主信号；
   **不交** CoreManager、ProtocolRuntime、ClientConfig。

## 10. 配置与资产归属

| 内容 | 归属 |
|---|---|
| 各功能模组的设置 | `config/Tianshu/module/<moduleId>.properties`，键名、默认值、路径都在模块内（`ModuleConfigStore`） |
| 内核设置 | `config/tianshu_ferry-client.toml`，只剩内核自己的 debug 开关与语音资源路径 |
| 具名对象索引 | 听澜自建自持；内核只能通过 `NamedObjectIndex` 端口观测 |
| 模型清单与读取器 | 各功能模组自持；`tianshu-core/model` 只留传输与状态机 |

`ClientConfig` 只实现内核自己的配置接口，不再充当配置总接口；`TianshuClient` 在组合根把它映射成各模块需要的小端口。