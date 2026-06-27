# 映迹：游戏内反馈与交互上下文模块设计

## 1. 模块命名

中文名：

```text
映迹
```

英文/代码名：

```text
Presence
```

完整说明名：

```text
映迹：游戏内反馈与交互上下文模块
```

模块 ID：

```text
module.presence
```

命名含义：

- **映**：把天枢后台状态映射为玩家可见的游戏内反馈。
- **迹**：捕获玩家输入、界面状态和客户端事件留下的轻量交互痕迹。

映迹不是完整 GUI 框架，也不是通用客户端运行时。第一版定位保持克制：负责状态反馈、交互上下文快照，以及必要的客户端世界/聊天事件发布。

## 2. 模块定位

映迹是 `tianshu-neoforge` 侧的客户端功能模块，负责读取 NeoForge/Minecraft 客户端状态、维护不可变快照、渲染轻量 HUD，并通过协议中心暴露必要能力和 topic。

一句话定位：

```text
映迹负责游戏内状态反馈展示，并把 NeoForge 客户端交互状态快照化后接入协议中心。
```

模块关系：

```text
Minecraft / NeoForge client
  ↓
映迹采集、归一化、渲染
  ↓
PresenceStateStore / Presence local model
  ↓
Presence capability / Presence owned topic / DialogueContextProvider
  ↓
IA、AX 或其他协议消费者
```

实现边界：

- 代码主体放在 `tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/presence`。
- common 只承载协议中心、公共 payload、IA 已有的 `DialogueContextProvider` 等稳定契约。
- 映迹不在 common 新建 function 模块，不把 NeoForge 活对象泄露到 common。

## 3. 第一版目标

第一版只解决三个问题：

1. **后台状态反馈**
   在游戏内以轻量 HUD 展示天枢后台状态，例如正在听、正在识别、正在思考、正在播报、正在压缩或出现错误。

2. **交互上下文快照**
   在 NeoForge 侧维护一份轻量客户端交互快照，为 IA 的 `DialogueContextProvider` 和 `PRESENCE.QUERY_CONTEXT` 提供数据来源。

3. **客户端事件发布**
   对明确有跨模块消费价值的低频事件发布 Presence 自有 topic，例如玩家获得成就、玩家死亡、玩家聊天消息。

第一版不做：

- 不做大型 HUD/Overlay 框架。
- 不接管全部 GUI。
- 不实现复杂输入拦截。
- 不维护聊天历史或交互事件历史。
- 不把映迹扩展为全局状态总线。

## 4. 职责边界

### 4.1 负责

映迹负责：

- 渲染轻量游戏内状态 HUD。
- 订阅 ASR、LLM、TTS、模块状态等已有业务 topic，映射为玩家可见状态。
- 采集 NeoForge 客户端轻量交互状态。
- 将 Minecraft 活对象转换为协议可承载的 ID、标签和快照。
- 作为 IA `DialogueContextProvider` 的 NeoForge 数据来源。
- 通过 `PRESENCE.QUERY_CONTEXT` 提供当前交互上下文查询能力。
- 发布映迹自己拥有的低频客户端 topic。

### 4.2 不负责

映迹不负责：

- 不做 AX prompt 编排。
- 不做 IA owner 仲裁。
- 不做 IR 文本修复或意图解析。
- 不做 LLM 推理或任务调度。
- 不接管设置中心 GUI。
- 不实现通用 GUI contribution 框架。
- 不做复杂 Overlay、Drawer、Panel 或图谱编辑器。
- 不把 `Screen`、`Player`、`Level`、`Entity`、`ItemStack` 等 Minecraft 活对象放入协议 payload。
- 不通过 UI 事件直接修改服务端真实世界状态。

## 5. 与 IA 的关系

IA 的职责是开放对话 owner 仲裁。映迹不替 IA 判断 owner，也不决定是否接管对话。

映迹提供平台侧上下文来源：

```text
NeoForge 当前状态
  ↓
映迹维护轻量快照
  ↓
DialogueContextProvider.capture(...)
  ↓
IA 在需要仲裁时读取快照
  ↓
IA 使用快照 + IR 文本结果执行 claim 仲裁
```

映迹给 IA 的数据只表达玩家当前或冻结时处于什么交互上下文，例如：

- 手持物品 ID。
- 装备物品 ID。
- 准星命中目标的简化引用。
- 是否按住交互键。
- 是否潜行。
- 当前 screen 或 container 的简化类别。
- 可用于 claim profile 的简化 `CONTEXT_FACT`。

是否命中、由谁成为 owner、是否继续 attention，都由 IA 根据 participant 注册表和仲裁策略决定。

## 6. 与协议中心的关系

映迹作为普通模块接入协议中心：

- 注册自身 capability。
- 注册自身拥有的 topic。
- 订阅其他模块拥有的状态 topic。
- 响应查询请求。

跨模块命令、事件、查询和生命周期走协议中心。协议处理器不能临时读取 `Minecraft.getInstance()`、`Player`、`Level`、`Screen` 等活对象，只能读取映迹已经维护好的不可变快照。

IA 仲裁热路径读取映迹提供的只读快照接口，这是平台快照数据源，不是业务通信通道。IA 不能通过它请求映迹执行动作，映迹也不能通过它向 IA 推送业务事件。

## 7. 状态反馈模型

第一版状态反馈只做本地 HUD 汇聚，不发布 Presence 状态 topic，也不提供“模块主动设置展示状态”的 capability。

状态来源仍由各业务模块自己负责发布：

| 来源 | 当前形态 | 映迹处理方式 |
|---|---|---|
| ASR | `INPUT.ASR_SPEECH_ACTIVITY / AsrSpeechActivityPayload` | 映射为听音或输入活动状态 |
| LLM | `LLM.STATUS / LlmStatusPayload` | 映射为思考、生成中、失败等反馈状态 |
| TTS | `TTS.PLAYBACK / TtsPlaybackStatusPayload` | 映射为播报、结束、失败 |
| 模块状态 | `MODULE.STATUS / ModuleStatusPayload` | 映射为后台维护、压缩、错误等状态 |

映迹不替这些模块汇总发布二次状态 topic。谁拥有业务状态，谁发布业务 topic；映迹只订阅并用于本地展示。

第一版展示状态抽象为：

```text
IDLE
LISTENING
TRANSCRIBING
THINKING
SPEAKING
COMPRESSING
ERROR
```

这些状态不是业务流程真相全集，只是给玩家看的反馈层表达。

状态合并由 `PresenceDisplayPolicy` 和 `PresenceStatusPriority` 处理，渲染类只读取 `PresenceStateStore` 的当前状态。

默认优先级：

```text
ERROR > LISTENING > TRANSCRIBING > THINKING > SPEAKING > COMPRESSING > IDLE
```

长期原则：

- 玩家可见的后台运行状态，优先经业务 topic 被映迹观察后显示。
- 真正聊天消息、系统命令结果或早期启动错误，可以保留直接提示。
- 没有明确使用场景前，不新增主动展示入口。

## 8. 交互上下文模型

映迹维护的是轻量快照，不是完整客户端状态。

当前快照字段包括：

- `playerId`
- `dimensionId`
- `screenKind`
- `screenClassName`
- `heldItemId`
- `equippedItemIds`
- `crosshairTarget`
- `interactionKeyDown`
- `sneaking`
- `recentInputKind`
- `playerStatus`
- `worldEnvironment`
- `inventoryItems`
- `activeEffects`
- `facts`
- `capturedAtMillis`

`recentInputKind` 只是快照里的单值输入来源标记，不代表输入历史或事件历史。

`facts` 用于承载少量简化标签，例如：

```text
screen.pause
screen.inventory
screen.chat
container.open
input.text_active
interaction.key_down
```

这些 fact 不应扩展为业务语义库。业务 owner 需要的 claim 条件应尽量通过 participant 的 claim profile 明确声明。

### 8.1 采集策略

映迹不做全量世界扫描，不逐帧构建复杂上下文。第一版按四类采集策略控制成本：

| 策略 | 适用字段 | 说明 |
|---|---|---|
| 实时读取 | 交互键、潜行、主手物品、副手物品、准星命中基础信息 | 成本低，只允许在 NeoForge tick/event 边界读取并写入轻量快照。 |
| 脏数据变化 | screen kind、container kind、输入类型 | 由 screen/input/container 事件更新轻量快照，必要时标记详细快照 dirty。 |
| 请求时读取 | IA capture 或 `PRESENCE.QUERY_CONTEXT` 需要的上下文 | 请求时只读取映迹缓存快照，不临时访问 Minecraft 活对象。 |
| 固定间隔 | 玩家状态、环境、背包摘要、药水效果 | 低频刷新详细快照，必须有上限，不能跟随高频输入反复扫描。 |

第一版明确不做：

- 不常态扫描完整背包。
- 不常态扫描附近所有实体。
- 不为通用状态展示读取 NBT 或复杂组件。
- 不把 AX 动态事实采集并入映迹。
- 不在协议线程、LLM/AX/IA worker 线程中读取 Minecraft 活对象。

如果后续某个功能需要完整背包、附近实体列表或复杂世界信息，应由该功能模块自己维护专用数据源，或另行设计明确的低频查询能力，不把映迹变成通用世界扫描器。

## 9. 协议入口

第一版协议入口保持少量、明确。

### 9.1 Capability

```text
PRESENCE.QUERY_CONTEXT
```

用途：

- 其他模块查询当前客户端交互上下文。
- 请求方通过 `PresenceContextQueryPayload.requestedFactIds` 指定需要的事实项。
- 映迹返回 `PresenceContextSnapshotPayload`。
- 查询结果只表达当前快照事实，不返回聊天历史或交互历史。

### 9.2 Topic

```text
PRESENCE.WORLD_EVENT
PRESENCE.CHAT_MESSAGE
```

`PRESENCE.WORLD_EVENT`：

- 发布低频、客户端可观察的世界事件。
- 当前包含玩家获得成就、玩家死亡。
- payload 为 `PresenceWorldEventPayload`。
- 事件来自纯客户端可观察信号，适配单人、联机和服务器环境。

`PRESENCE.CHAT_MESSAGE`：

- 只发布玩家聊天消息。
- 采集条件为 NeoForge `ClientChatReceivedEvent.Player` 且不是 system message。
- 指令结果、成就提示、死亡信息、系统消息不计入该 topic。
- payload 为 `PresenceChatMessagePayload(senderId, senderName, messageText)`。

### 9.3 Payload

common 协议层承载跨模块公共 payload：

```text
PresenceContextQueryPayload
PresenceContextSnapshotPayload
PresenceWorldEventPayload
PresenceChatMessagePayload
```

NeoForge 映迹包内保留本地采集模型：

```text
PresenceContextSnapshot
PresenceInventoryItem
PresencePlayerStatus
PresenceWorldEnvironment
PresencePotionEffect
PresenceTargetSnapshot
PresenceStatusSnapshot
```

所有 payload 必须是不可变快照，不携带 Minecraft 活对象。

## 10. 当前 NeoForge 侧结构

当前映迹代码位于：

```text
tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/presence/
```

结构：

```text
client/presence/
  PresenceClientHooks
  PresenceClientRuntime
  PresenceModule
  PresenceModuleInstaller
  PresenceProtocolAdapter
  PresenceStateStore

client/presence/capture/
  PresenceAdvancementTracker
  PresenceChatMessageSink
  PresenceEventCollector
  PresenceRefreshPolicy
  PresenceScreenClassifier
  PresenceWorldEventSink

client/presence/context/
  PresenceContextFactMapper
  PresenceContextProvider

client/presence/model/
  PresenceContextSnapshot
  PresenceInputKind
  PresenceInventoryItem
  PresencePlayerStatus
  PresencePotionEffect
  PresenceScreenKind
  PresenceSeverity
  PresenceStatusSnapshot
  PresenceStatusType
  PresenceTargetSnapshot
  PresenceWorldEnvironment

client/presence/status/
  PresenceDisplayPolicy
  PresenceModuleStatusMapper
  PresenceStatusPriority

client/presence/render/
  PresenceHudRenderer
  PresenceRenderer
```

职责概览：

| 包/类 | 职责 |
|---|---|
| 根包 | 模块生命周期、协议适配、运行时装配、线程安全状态存储 |
| `capture` | NeoForge/Minecraft 事件采集和活对象读取 |
| `context` | IA 上下文只读接口和 capability fact 映射 |
| `model` | 映迹本地不可变快照模型 |
| `status` | 玩家可见状态映射、优先级、TTL 和降级策略 |
| `render` | 轻量 HUD 渲染 |

这个结构的核心原则是：允许读取 Minecraft 活对象的代码集中在 `capture` 和 `render`，协议处理、上下文查询和状态策略只读映迹快照。

## 11. 线程与性能原则

映迹必须遵守 Minecraft 客户端线程限制：

- 读取 Minecraft 活对象只发生在 NeoForge client tick、render 或客户端事件回调中。
- 协议能力处理器只读取 `PresenceStateStore` 中的快照。
- 后台 worker 不访问 `Minecraft.getInstance()`。
- HUD 渲染只读当前状态，不做复杂扫描。
- 详细快照低频刷新，且通过 dirty 标记和时间间隔控制成本。

请求/响应不会在映迹里等待主线程即时扫描。快照过旧时，返回已有轻量快照或空结果，由调用方按业务语义降级。

## 12. 旧结构迁移进展

已经完成：

1. 新增映迹模块骨架和 NeoForge 侧 runtime。
2. 将轻量 HUD 状态渲染放入映迹。
3. 将 IA 使用的 NeoForge `DialogueContextProvider` 切到映迹上下文快照。
4. `TianshuClient` 保留为启动与总装配入口，只把 NeoForge listener 转发给 `PresenceClientRuntime`。
5. 删除旧的 NeoForge 对话上下文双路径，避免 IA 上下文来源分叉。
6. Presence 自有 topic 由 `PresenceProtocolAdapter` 注册和发布，不放进协议中心 bootstrap。
7. 聊天消息 topic 收敛为最小 payload，只发布玩家聊天信号。
8. 移除映迹侧聊天历史、交互事件历史和 recent event 查询体系。

后续迁移方向：

- `api/IGameEnvironment`、`api/IAudioBridge`、`api/ITianshuConfig` 不进入映迹，后续按运行时 port 或平台 port 整理。
- AX 需要的动态事实通过 `PRESENCE.QUERY_CONTEXT` 查询 Presence 提供的当前上下文事实，或由 AX 自己维护专用 fact source。
- 各模块面向玩家的运行状态提示逐步减少直接 `displayMessageToPlayer`，优先让映迹订阅业务 topic 后做本地 HUD 表达。
- 如果需要更复杂 UI，再在映迹内拆出 UI renderer 边界，不把渲染逻辑塞进协议 adapter 或采集器。

## 13. 设计原则

1. 映迹是 NeoForge 层模块，不是 common 业务脑。
2. 玩家可见状态反馈要克制、短 TTL、可降级。
3. IA 只使用映迹提供的上下文快照，不把映迹变成仲裁策略的一部分。
4. Presence payload 只承载 DTO 和 snapshot，不接收 Minecraft 活对象。
5. 高频事件默认不广播，先进入本地 latest snapshot。
6. 协议入口保持少量、稳定，后续功能按真实需求扩展。
7. 业务 topic 由业务模块拥有；映迹只注册自己拥有的 topic。
8. 映迹采集必须遵守实时、脏数据变化、请求时、固定间隔四类策略，不做常态全量扫描。
9. 协议处理和后台 worker 不读取 Minecraft 活对象，只读取映迹已经维护的不可变快照。
