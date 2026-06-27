# 映迹：游戏内反馈与交互上下文模块设计

## 1. 模块命名

建议中文名：

```text
映迹
```

建议英文/代码名：

```text
Presence
```

完整说明名：

```text
映迹：游戏内反馈与交互上下文模块
```

模块 ID 建议：

```text
module.presence
```

命名含义：

- **映**：把天枢后台状态映射到玩家可见的游戏内反馈。
- **迹**：捕获玩家输入、界面状态和客户端事件留下的轻量交互痕迹。

映迹不是完整 GUI 框架，也不是通用客户端运行时。它的第一版定位应保持克制：负责状态反馈、交互上下文快照，以及必要事件的协议化返回。

代码层采用 `Presence`，而不是拼音，是为了让包名、协议前缀和外部集成语义更清楚。中文名仍使用【映迹】，用于延续 `Design.md` 中的模块命名风格。

## 2. 模块定位

映迹是 NeoForge 层的客户端功能模块，负责在游戏内向玩家展示天枢后台运行状态，并采集必要的客户端交互上下文。

第一版实现代码建设在 `tianshu-neoforge` 侧。common 只承载协议中心、payload 基础接口、`DialogueContextProvider` 与稳定协议契约，不为映迹新增 common function 包或通用 provider 包。

一句话定位：

```text
映迹负责游戏内状态反馈展示，并把 NeoForge 客户端交互状态快照化后接入协议中心。
```

它处在 Minecraft/NeoForge 活对象与 common 协议模型之间：

```text
Minecraft / NeoForge client
  ↓
映迹采集、归一化、渲染
  ↓
NeoForge 侧快照 / protocol payload / DialogueContextFrame
  ↓
IA、IR、AX 或其他协议消费者
```

## 3. 第一版目标

第一版不做大型 HUD/Overlay 框架，不接管全部 GUI，也不实现复杂输入拦截。

第一版只解决三个问题：

1. **后台状态反馈**
   在游戏内以轻量 HUD 形式展示天枢当前后台状态，例如正在听、正在识别、正在思考、正在播报、正在压缩或出现错误。

2. **交互上下文快照**
   在 NeoForge 侧维护一份轻量客户端交互快照，为 IA 的 `DialogueContextProvider` 提供数据来源。

3. **事件查询与返回**
通过协议中心 capability，为 NeoForge 侧或外部接入方提供当前客户端交互状态或最近交互事件的查询结果。

## 4. 职责边界

### 4.1 负责

映迹负责：

- 渲染轻量游戏内状态 HUD。
- 订阅或接收 ASR、LLM、TTS、IA、AX 后台维护等模块的状态信号。
- 合并状态并选择当前最适合展示给玩家的状态。
- 采集 NeoForge 客户端轻量交互状态。
- 将 Minecraft 活对象转换为协议可承载的 ID、标签和快照。
- 作为 IA `DialogueContextProvider` 的 NeoForge 数据来源。
- 通过协议中心提供当前交互上下文查询能力。
- 在必要时发布低频、脱敏的交互状态事件。

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

映迹提供的是平台侧上下文来源：

```text
NeoForge 当前状态
  ↓
映迹维护轻量快照
  ↓
DialogueContextProvider.capture(...)
  ↓
IA 在 ASR speaking=true 时冻结快照
  ↓
IR 提交 DialogueArbitrationRequest
  ↓
IA 使用冻结快照 + IR 文本结果执行 claim 仲裁
```

因此，映迹给 IA 的数据应表达“当前或冻结时玩家处于什么交互上下文”，例如：

- 手持物品 ID。
- 装备物品 ID。
- 准星命中目标的简化引用。
- 是否按住交互键。
- 是否潜行。
- 当前 screen 或 container 的简化类别。
- 可用于 claim profile 的简化 `CONTEXT_FACT`。

这些信息只作为 IA claim 判断的输入。是否命中、由谁成为 owner、是否继续 attention，都由 IA 自己根据 participant 注册表和仲裁策略决定。

## 6. 与协议中心的关系

映迹应作为普通模块接入协议中心：

- 注册自身 capability。
- 订阅需要展示的状态 topic。
- 发布低频状态 topic。
- 响应查询请求。
- 只在 NeoForge client tick、render 和事件回调中读取 Minecraft 活对象。

跨模块访问必须走协议中心，不允许其他模块直接 import NeoForge 渲染或事件采集实现。

协议能力处理器不能临时读取 `Minecraft.getInstance()`、`Player`、`Level`、`Screen` 等活对象。它只能读取映迹已经维护好的不可变快照，并在快照过旧时降级为轻量快照或空结果。

## 7. 状态反馈模型

第一版状态反馈不要求立即定死视觉形式，但必须先收敛状态传递出口。

当务之急是：把 common 和 NeoForge 中散乱的玩家可见状态、后台运行状态、短提示状态统一汇聚到映迹，由映迹的 `PresenceProtocolAdapter` 进行协议发送和状态发布。

这只是第一方向“后台状态反馈”的实现约束，不把映迹扩展成全局状态总线。ASR、LLM、TTS、IA、AX 或外部模块可以继续拥有自己的业务状态；只有需要给玩家看的运行状态，才进入映迹。

第一版展示状态可以抽象为：

```text
IDLE
LISTENING
TRANSCRIBING
THINKING
SPEAKING
COMPRESSING
ERROR
```

这些状态不是业务流程真相的全集，只是给玩家看的反馈层表达。

状态来源可以包括：

| 来源 | 示例 |
|---|---|
| ASR | 正在听、正在识别 |
| LLM | 请求排队、正在生成、失败 |
| TTS | 正在播报、播放被打断 |
| AX | 后台压缩、记忆整理 |
| IA | 当前 owner preview、session 状态 |

状态合并不应写死在渲染类中，应由独立 policy 决定优先级、TTL、过期和降级。

建议默认优先级：

```text
ERROR > LISTENING > TRANSCRIBING > THINKING > SPEAKING > COMPRESSING > IDLE
```

后续可根据实际体验调整。

### 7.1 状态源收敛

当前工程已经存在多个状态来源：

| 来源 | 当前形态 | 映迹处理方式 |
|---|---|---|
| ASR | `INPUT.ASR_SPEECH_ACTIVITY / AsrSpeechActivityPayload` | 映射为 `LISTENING` 或输入活动状态 |
| LLM | `LLM.STATUS / LlmStatusPayload` | 映射为 `THINKING`、排队、生成中、失败等反馈状态 |
| TTS | `TTS.PLAYBACK / TtsPlaybackStatusPayload` | 映射为 `SPEAKING`、播放结束、播放失败 |
| IA | `DIALOGUE_SESSION_EVENTS`、`DIALOGUE_OWNER_PREVIEW` | 映射为当前 owner、会话状态或低存在感提示 |
| AX | 内部输出、后台维护、压缩任务 | 后续优先通过专用业务 topic 或模块服务快照汇入，第一版不新增主动设置入口 |
| 外部模块 | `StateSummary`、自定义 envelope、集成 API | 第一版只读已有摘要或后续扩展，不先开放主动展示能力 |
| 直接聊天提示 | `IGameEnvironment.displayMessageToPlayer(...)` | 面向运行状态的提示应逐步迁到映迹，真正聊天消息除外 |

这里的目标不是删除原业务 topic。原业务 topic 仍服务业务模块和诊断链路；映迹订阅这些 topic，把它们归一化为玩家可见状态。

### 7.2 Presence 状态观察

映迹第一版不提供“模块主动设置展示状态”的 capability。原因是当前明确场景不足，提前开放会让映迹变成通用提示面板。

第一版只做状态观察和本地汇聚：

```text
PRESENCE.STATE_CHANGED
```

`PRESENCE.STATE_CHANGED` 只用于需要被其他模块观察的低频玩家可见状态变化，不是所有状态变化的固定出口。

映迹的状态来源先来自：

- 订阅 ASR / LLM / TTS / IA 的既有业务 topic。
- 读取模块服务快照。
- 读取本地交互状态。
- 必要时读取 `StateSummaryRegistry` 中已有摘要。

如果后续出现明确需求，例如某业务模块需要主动请求“显示一条短 TTL 玩家反馈”，再新增 `PRESENCE.SET_STATUS` 或更具体的能力。新增前仍优先评估是否应由原业务 topic、状态摘要或映迹本地状态推导完成。

### 7.3 状态路由规则

状态传递不能全部固定走 topic，也不能全部走 capability。第一版按语义分流：

| 状态类型 | 推荐路径 | 说明 |
|---|---|---|
| 持续运行态 | 本地 `PresenceStateStore` | 例如正在听、正在思考、正在播报。频繁变化时只更新本地 HUD，不必每次广播。 |
| 低频状态变化 | `PRESENCE.STATE_CHANGED` topic | 例如从空闲进入听音、从思考进入失败、owner preview 变化。只发布脱敏摘要。 |
| 查询当前状态 | `PRESENCE.QUERY_CONTEXT` 或后续 `PRESENCE.QUERY_STATUS` capability | 需要当前快照时使用 request/response，不靠订阅 topic 猜最新状态。 |
| 一次性请求结果 | capability response | 例如查询最近交互事件、查询当前上下文。回包只给请求方，不广播。 |
| 内部渲染状态 | 不出协议 | 例如动画进度、淡入淡出计时、HUD 布局状态，只保存在 NeoForge 渲染侧。 |
| 原业务状态 | 原业务 topic 保留 | ASR/LLM/TTS/IA 的原 topic 继续服务业务链路；映迹只是按需订阅并映射。 |

判断规则：

```text
是否只有映迹 HUD 自己需要？
  是 -> 本地状态，不发协议

是否多个模块需要观察这个低频变化？
  是 -> PRESENCE.STATE_CHANGED

是否某模块只想拿一次当前结果？
  是 -> capability request/response
```

### 7.4 状态映射

第一版不要做复杂状态框架。映迹只需要在 adapter 或 module 内部完成简单映射：

```text
业务状态 topic / module service snapshot / StateSummary
  ↓
PresenceStateStore
  ↓
PresenceDisplayPolicy
  ↓
PresenceProtocolAdapter.publishStateChanged(...)
  ↓
PresenceHudRenderer
```

如果映射逻辑变复杂，再拆 `PresenceStatusMapper`。第一版可以先把映射逻辑集中在 `PresenceModule` 或 `PresenceProtocolAdapter` 附近，避免提前建设太多类。

### 7.4 直接提示迁移原则

当前 common 模块里存在一些 `env.displayMessageToPlayer(...)` 调用，用于模型就绪、失败或运行状态提示。

长期原则：

- 玩家可见的后台运行状态，不直接调用 `displayMessageToPlayer`。
- 第一版先由映迹订阅既有业务 topic 或读取模块快照生成状态。
- 映迹决定是否显示、显示多久、显示在哪里、是否合并或降级。
- 真正的用户聊天消息、系统命令结果或无法通过协议中心表达的早期启动错误，可以保留直接提示。

这样可以避免 ASR、LLM、TTS、AX 各自弹不同风格的聊天消息，也便于后续统一 HUD、Toast、角标或低存在感提示。

## 8. 交互上下文模型

映迹维护的交互上下文应是轻量快照，而不是完整客户端状态。

建议第一版包含：

- `playerId`
- `dimensionId`
- `screenKind`
- `containerKind`
- `heldItemIds`
- `equippedItemIds`
- `crosshairTarget`
- `interactionKeyDown`
- `sneaking`
- `recentInputKind`
- `facts`
- `capturedAtMillis`

其中 `facts` 用于承载少量简化标签，例如：

```text
screen.pause
screen.inventory
screen.chat
container.open
input.text_active
interaction.key_down
```

这些 fact 不应无限扩展为业务语义库。业务 owner 需要的 claim 条件应尽量通过 participant 的 claim profile 明确声明。

### 8.1 IA 热路径与协议边界

映迹相对 IA 是更底层的客户端交互状态来源。IA 仲裁时需要在明确时序点读取上下文，尤其是 ASR `speaking=true` 时冻结“玩家开始说话那一刻”的手持物、准星、交互键和界面状态。

这类热路径不适合走协议 request/response。否则响应可能晚到，玩家状态已经变化，冻结语义会失真。

第一版采用折中规则：

```text
跨模块命令、事件、查询和生命周期走协议中心。
IA 仲裁热路径读取映迹维护的只读快照，不触发业务动作，不产生跨模块副作用。
```

该只读接口不是绕过协议中心做业务通信，而是平台快照数据源。IA 不能通过它请求映迹执行动作，映迹也不能通过它向 IA 推送正文或业务事件。

### 8.2 采集策略

映迹不做全量世界扫描，不做全量背包扫描，不逐帧构建复杂上下文。第一版按四类采集策略控制成本，不引入额外的 interest 机制。

| 策略 | 适用字段 | 说明 |
|---|---|---|
| 实时读取 | 交互键、潜行、主手物品、副手物品、准星命中基础信息 | 成本低，只允许在 NeoForge tick/event 边界读取并写入轻量快照。 |
| 脏数据变化 | screen kind、container kind、最近输入类型、最近聊天 | 由 screen/input/chat/container 事件更新轻量快照，必要时标记详细快照 dirty。 |
| 请求时读取 | IA capture 或 `PRESENCE.QUERY_CONTEXT` 需要的上下文 | 请求时只读取映迹缓存快照，不临时访问 Minecraft 活对象；快照过旧则降级。 |
| 固定间隔 | 玩家状态、环境、背包摘要、药水效果、最近聊天摘要 | 低频刷新详细快照，必须有上限，不能跟随高频输入反复扫描。 |

第一版明确不做：

- 不常态扫描完整背包。
- 不常态扫描附近所有实体。
- 不为通用状态展示读取 NBT 或复杂组件。
- 不把 AX 动态事实采集并入映迹。
- 不在协议线程、LLM/AX/IA worker 线程中读取 Minecraft 活对象。

如果后续某个功能需要完整背包、附近实体列表或复杂世界信息，应由该功能模块自己维护专用数据源，或另行设计明确的低频查询能力，不把映迹变成通用世界扫描器。

## 9. 协议草案

协议名称后续可以调整，第一版建议保持少量入口。

第一版的稳定查询契约放在 common 的 `protocol.payload` 中，因为 AX 等 common 模块需要通过协议中心查询映迹上下文。NeoForge 映迹包内只保留采集后的本地快照 DTO 和私有事件 DTO，不承载跨模块公共契约。

### 9.1 Capability

```text
PRESENCE.QUERY_CONTEXT
```

`QUERY_CONTEXT`：

- 其他模块查询当前客户端交互上下文。
- 返回 `PresenceContextSnapshotPayload`。
- 适合 request/response。
- payload 类位于 common `protocol.payload`，由 NeoForge 映迹实现能力处理。
- 查询方通过 `PresenceContextQueryPayload.requestedFactIds` 指定需要的事实项，映迹只返回被请求的事实。
- 最近低频交互事件作为 `presence.interaction.recent_events` fact 返回，不单独开放 NeoForge 私有 capability。

### 9.2 Topic

```text
PRESENCE.STATE_CHANGED
PRESENCE.INTERACTION_EVENT
```

`STATE_CHANGED`：

- 低频发布当前展示状态变化。
- 不发布 prompt、玩家正文或完整上下文。
- 只发布经过映迹合并、节流和脱敏后的最终反馈状态。

`INTERACTION_EVENT`：

- 仅发布必要低频交互事件。
- 高频输入、tick、鼠标移动不走普通 topic。

### 9.3 Payload

common 协议层已有：

```text
PresenceContextQueryPayload
PresenceContextSnapshotPayload
```

NeoForge 映迹包内保留本地采集 DTO：

```text
PresenceContextSnapshot
PresenceInventoryItem
PresencePlayerStatus
PresenceWorldEnvironment
PresencePotionEffect
PresenceChatMessage
PresenceInteractionEvent
```

Payload 必须是不可变快照，不携带 Minecraft 活对象。

`PresenceStatusPayload` 建议至少包含：

```text
statusId
sourceModuleId
statusType
severity
messageKey
messageText
traceId
startedAtMillis
ttlMillis
attributes
```

其中 `messageText` 只能用于短提示，不能携带玩家正文、prompt、LLM response、RAG hit 或记忆内容。能用 `messageKey` 的场景优先使用 `messageKey`。

## 10. NeoForge 侧建议结构

建议路径：

```text
tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/presence/
```

建议类：

```text
PresenceModule
PresenceModuleInstaller
PresenceProtocolAdapter
PresenceStateStore
PresenceDisplayPolicy
PresenceHudRenderer
PresenceContextProvider
PresenceEventCollector
```

职责概览：

| 类 | 职责 |
|---|---|
| `PresenceModule` | 生命周期入口，注册协议和事件桥 |
| `PresenceModuleInstaller` | 接入 `ClientTianshuModuleAssembler` |
| `PresenceProtocolAdapter` | capability/topic 封装 |
| `PresenceStateStore` | 保存当前展示状态和交互快照 |
| `PresenceDisplayPolicy` | 状态优先级、TTL 和降级策略 |
| `PresenceHudRenderer` | 轻量 HUD 渲染 |
| `PresenceContextProvider` | 为 IA 提供 `DialogueContextProvider` 数据来源 |
| `PresenceEventCollector` | 从 NeoForge 事件更新快照 |

## 11. 干净分层方案

如果不顾虑旧结构，映迹应按“模块协议、纯策略、平台适配、模块装配”四层设计。四层都位于 `tianshu-neoforge`，只有依赖的接口来自 common。

### 11.1 模块协议边界

跨模块稳定协议放在 common，用于协议中心通信：

```text
protocol/
  PresenceContextQueryPayload
  PresenceContextSnapshotPayload
  PresenceContextFactIds
```

NeoForge 映迹包内保留协议适配器和本地快照 DTO：

```text
client/presence/
  PresenceProtocolAdapter
  PresenceContextSnapshot
  PresenceInteractionEvent
  PresenceStatusSnapshot
  PresenceScreenKind
  PresenceInputKind
```

这一层只做协议注册、请求响应和本地快照表达，不读取 MC 活对象，不做渲染。

### 11.2 模块纯策略层

放在 NeoForge 映迹包内，前提是不依赖 Minecraft：

```text
client/presence/core/
  PresenceDisplayPolicy
  PresenceStatusSnapshot
  PresenceContextSnapshot
  PresenceInteractionEvent
```

这一层负责状态优先级、TTL、过期、降级、脱敏等纯逻辑。虽然当前放在 NeoForge 内，但应避免 import Minecraft，便于未来需要时上移或跨加载器复用。

### 11.3 NeoForge 适配层

放在 NeoForge，只做 MC/NeoForge 活对象转换：

```text
client/presence/adapter/
  NeoForgePresenceEventCollector
  NeoForgePresenceContextCollector
  NeoForgePresenceHudRenderer
  NeoForgeScreenClassifier
  NeoForgeCrosshairSnapshotter
  NeoForgeItemSnapshotter
```

这一层允许 import `net.minecraft.*` 和 `net.neoforged.*`，但输出必须是映迹快照或协议 payload。

### 11.4 NeoForge 模块层

放在 NeoForge，负责模块生命周期和协议接入：

```text
client/presence/
  PresenceModule
  PresenceModuleInstaller
  PresenceProtocolAdapter
  PresenceStateStore
  PresenceContextProvider
```

`PresenceContextProvider` 实现 common 中 IA 已有的 `DialogueContextProvider`，从 `PresenceStateStore` 读取冻结或最新快照，而不是让 IA 直接读取 Minecraft。

`PresenceContextProvider` 是映迹提供给 IA 的只读快照接口。它不继承 `AbstractProtocolAdapter`，也不负责协议收发；协议收发由 `PresenceProtocolAdapter` 负责。

## 12. 旧结构迁移进展

当前 `TianshuClient` 已经承担了较多客户端事件注册和 HUD 渲染职责。映迹落地时应逐步迁移，而不是一次性重写。

第一步已经执行：

1. 新增映迹模块骨架。
2. 把轻量 HUD 状态渲染放入映迹。
3. 将 IA 使用的 NeoForge `DialogueContextProvider` 能力硬切到映迹的上下文快照。
4. 保留 `TianshuClient` 作为启动与总装配入口，只负责把 NeoForge listener 转发给 `PresenceClientRuntime`。
5. 删除旧 `NeoForgeDialogueContextProvider`，避免 IA 上下文出现双路径。
6. 删除 common 顶层 `provider/` 与 `snapshot/` 中未接线的旧世界状态 provider 体系。

不建议在第一版中移动所有 GUI、设置页和 AX HUD 逻辑。已有功能可以继续保留，等映迹边界稳定后再按需整合。

后续迁移方向：

- `api/IGameEnvironment`、`api/IAudioBridge`、`api/ITianshuConfig` 不进入映迹，后续移动到 core port 或 runtime port。
- `provider/WorldStateProvider` 不再作为公共入口继续扩张，已从 common 根目录移除。
- AX 需要的动态事实来源迁入 AX 自己的 fact source 边界，或通过 `PRESENCE.QUERY_CONTEXT` 查询 Presence 提供的缓存上下文事实。
- IA 需要的对话上下文来源由映迹的 `PresenceContextProvider` 接管。
- `snapshot/` 不作为 common 顶层 DTO 回收站继续使用；真正通用的 DTO 后续按归属进入对应模块或协议 payload。
- 各模块面向玩家的运行状态提示逐步从 `displayMessageToPlayer`、私有 HUD state、散落 topic 迁入映迹的状态观察链路；第一版不新增主动设置能力。
- 原有 ASR/LLM/TTS/IA 业务 topic 保留业务语义，但由映迹统一订阅和映射为玩家可见状态。

## 13. 设计原则

1. 映迹是 NeoForge 层模块，不是 common 业务脑。
2. 玩家可见状态反馈要克制、短 TTL、可降级。
3. IA 只使用映迹提供的上下文快照，不把映迹变成仲裁策略的一部分。
4. 映迹协议 payload 只承载 DTO 和 snapshot，不接收 Minecraft 活对象。
5. 高频事件默认不广播，先进入本地 latest snapshot。
6. 协议入口保持少量、稳定，后续功能按真实需求扩展。
7. 第一版优先打通状态展示和 IA 上下文来源，不提前建设大型 UI 框架。
8. 映迹采集必须遵守实时、脏数据变化、请求时、固定间隔四类策略，不做常态全量扫描。
9. 协议处理和后台 worker 不读取 Minecraft 活对象，只读取映迹已经维护的不可变快照。
