# 映迹：游戏内反馈与交互上下文模块设计

## 快速摘要

1. **功能**：映迹为游戏提供当前环境信息，并用一个动态图标反馈系统当前所处的交互阶段。
2. **上下文**：IA、IR、AX 等模块通过协议中心按需请求玩家、世界、背包、效果和交互信息；返回内容只对应当前世界。
3. **图标阶段**：对玩家只呈现准备中、聆听中、处理中、回应中、空闲或不可用等少量产品状态，不展示模块名、任务编号或模型内部阶段。
4. **世界边界**：退出世界时清空上下文、状态和排队查询；重新进入后重新建立当前世界的数据。

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

## 2. 模块定位

映迹是 `tianshu-client` 中的平台无关客户端模块；`tianshu-neoforge` 只负责接入 Minecraft 状态和实际 HUD 绘制。映迹负责：

- 通过外层 platform 端口读取客户端状态
- 维护不可变快照
- 输出轻量 HUD 显示状态
- 通过协议中心暴露交互上下文查询能力
- 广播低频世界事件
- 发布映迹自己拥有的低频客户端 topic

实际 Minecraft GUI 绘制放在 `tianshu-neoforge` 的 `ui/hud`，不放进映迹采集和协议链路。

一句话定位：

```text
映迹负责游戏内状态反馈展示，并把 NeoForge 客户端交互状态快照化后接入协议中心。
```

## 3. 三期规划

### 3.1 一期：模块骨架与基础反馈

一期解决“能看见、能订阅、能查询”。

已确定范围：

- 轻量 HUD 状态展示
- 交互上下文快照
- Presence topic 发布
- Presence capability 查询
- 世界事件广播

一期不做：

- 大型 GUI 框架
- 复杂输入拦截
- 聊天历史
- 消息历史
- 通用世界扫描器

### 3.2 二期：字段收敛与链路收口

二期解决“快照怎么更新、谁来请求什么、字段怎么分组”。

已确定目标：

- 把 Presence 从“能拿到一堆字段”收口成“按字段组按需拿字段”
- 让 IA / IR / AX 都通过 `PRESENCE.QUERY_CONTEXT` 请求
- 让映迹只读取本次明确请求的字段组
- 让快照结构稳定，不再依赖同步桥

已完成：

- `PRESENCE.QUERY_CONTEXT` capability 已接通
- `PRESENCE.WORLD_EVENT` / `PRESENCE.CHAT_MESSAGE` topic 已收敛
- 旧的同步上下文桥已移除
- IA / IR / AX 已切到 Presence 请求链路
- 空 fact 请求不再隐式返回默认上下文

当前规则：

- 交互上下文由事件驱动并保留短期快照。
- 玩家状态、背包、药水效果和世界环境等动态事实在收到请求时读取当前世界，不长期复用旧数据。
- 同一 client tick 中的多个请求共享一次平台快照，避免重复读取 Minecraft 对象。

二期原则：

- 不做固定保鲜扫描
- 不做 interest 体系
- 没请求就不扫
- 交互快照失效只影响下次按需读取，不主动触发扫描
- 请求方必须显式传 `requestedFactIds`

动态事实按请求捕获；`dirty` 只作为交互快照或平台事件的失效提示，不会触发后台轮询。

当前字段组：

```text
INTERACTION_CONTEXT
PLAYER_INVENTORY
PLAYER_ACTIVE_EFFECTS
PLAYER_STATUS
WORLD_ENVIRONMENT
```

### 3.2.5 二点五期：平台端口解耦

二点五期解决“映迹核心不直接绑定 NeoForge / Minecraft 版本 API”。

已确定目标：

- 映迹采集链只依赖 `ClientGameContextProvider`
- 映迹文本映射只依赖 `PresenceTextProvider`
- NeoForge / Minecraft 的活对象读取、screen 分类、成就包解析、注册表读取、本地化 API 都放在外层 adapter 和 event 包
- 映迹事件入口尽量接收普通值或平台端口，不让 `Screen`、`Component`、packet 等版本敏感类型流入映迹核心

已完成：

- `PresenceEventCollector` 不再直接读取 `Minecraft.getInstance()`
- `PresenceEventCollector` 不再接收 `Screen`、`Component`、`ClientboundUpdateAdvancementsPacket`
- `PresenceContextFactMapper` 和 `PresenceDisplayPolicy` 不再直接调用 Minecraft `I18n`
- NeoForge 细节集中到 `NeoForgePresencePlatform`、`NeoForgePresenceAdvancementTracker`、`NeoForgePresenceScreenClassifier`、`NeoForgePresenceTextProvider`

### 3.3 三期：UI 解耦与能力扩展

三期解决“映迹怎么继续长，但不把自己长成怪物”。

三期目标：

- 把 HUD 渲染进一步从采集链路里拆开
- 把游戏内 HUD 绘制放到 `tianshu-neoforge` 的 `ui/hud`
- 让映迹核心只输出纯显示状态，不直接碰底层 GUI API
- 设置页负责控制哪些模块状态进入 HUD，以及哪些 HUD 元素显示
- HUD 元素允许逐步扩展成文本、icon、shader 等多种绘制形态，但只能停留在 GUI 层
- 给未来调试页预留 GUI 层入口，但不回流到协议 adapter
- 让 Presence 继续保持“轻量上下文模块”，而不是全局运行时

已完成：

- `PresenceHudDisplay` 作为 HUD 纯显示数据
- `PresenceClientRuntime.currentHudDisplay()` 输出当前 HUD 状态
- `tianshu-neoforge` 的 `PresenceHudRenderer` 负责 HUD 元素调度
- `PresenceHudElementFrame` 承载元素类型、状态、显示数据和状态时间
- `PresenceStatusTextElementController` 负责状态文本元素的可见性和状态机
- `PresenceStatusTextElementRenderer` 负责状态文本元素的 Minecraft 绘制
- `PresenceHudSettings` 控制 HUD 总开关、状态文本开关和模块来源可见性
- 映迹设置页已接入设置控制台，用于控制 HUD 元素和模块状态来源
- 内测调试开关已接入设置页，默认关闭
- 模块流水线调试视图只读读取 `ModuleStatusCache`，不订阅新 topic，不保存历史
- AX responding 状态通过 `presenceStatusType=SPEAKING` 接入映迹状态展示

三期预留：

可能方向：

- icon / shader 类 HUD 元素
- shader 参数由具体元素 controller 从状态和 `stateAgeMillis()` 推导，不写进映迹核心
- 更细的状态展示样式
- 新的低频 Presence topic
- 新模块接入统一查询

三期不做：

- 不把映迹改成完整 GUI 框架
- 不把 Presence 改成业务调度中心
- 不把状态展示改成消息历史系统
- 调试流水线不常开，必须由内测/上线开关控制

## 4. 当前实现边界

映迹只做这些：

- 通过 platform 端口采集客户端状态
- 维护 `PresenceStateStore`
- 处理 `PRESENCE.QUERY_CONTEXT`
- 发布 `PRESENCE.WORLD_EVENT`
- 发布 `PRESENCE.CHAT_MESSAGE`
- 输出本地 HUD 显示状态

映迹不做这些：

- 不替 AX 编排 prompt
- 不替 IA 做 owner 仲裁
- 不替 IR 做文本修复
- 不改协议中心功能定义
- 不在 common 引入 NeoForge 活对象
- 不在采集、查询、状态映射链路里直接依赖 NeoForge / Minecraft 版本 API
- 不直接执行 Minecraft HUD 绘制
- 不决定 GUI 元素的具体绘制形态

## 5. 协议入口

### 5.1 Capability

```text
PRESENCE.QUERY_CONTEXT
```

用途：

- IA / IR / AX 等模块按需通过 `PRESENCE.QUERY_CONTEXT` 请求当前客户端上下文
- 请求方通过 `requestedFactIds` 指定需要的事实
- 映迹返回 `PresenceContextSnapshotPayload`
- 需要时由下一次 client tick 捕获或刷新

### 5.2 Topic

```text
PRESENCE.WORLD_EVENT
PRESENCE.CHAT_MESSAGE
```

`PRESENCE.WORLD_EVENT`：

- 玩家获得成就
- 玩家死亡
- 作为映迹自己的世界事件广播通道
- 供 AX / 其他订阅方消费低频世界变化
- 不写入聊天历史，不伪装成玩家聊天

`PRESENCE.CHAT_MESSAGE`：

- 只广播玩家聊天消息
- 不计入指令、成就、死亡、系统消息
- payload 保持克制，只包含说话人 UUID、说话人名称和消息文本

### 5.3 产品状态图标

映迹对外只提供一个动态图标，不为每个模块分别显示图标。内部模块状态只用于推导当前产品阶段：

- `准备中`：相关服务尚未就绪，暂时不能提供完整服务。
- `聆听中`：正在接收玩家语音。
- `处理中`：正在进行仲裁、文本修复、检索或生成。
- `回应中`：正在播放或展示 AI 回复。
- `空闲`：当前没有活动。
- `不可用`：功能未启用或当前流程无法继续。

模块名、任务编号、队列长度、模型加载阶段和异常代码不进入图标状态；需要调试时只在设置页的调试区域查看。

## 6. 交互上下文

映迹维护的是轻量快照，不是完整客户端状态。

当前快照字段：

- `playerId`
- `dimensionId`
- `screenKind`
- `screenClassName`
- `heldItemId`
- `equippedItemIds`
- `crosshairTarget`
- `interactionKeyDown`
- `attackKeyDown`
- `sneaking`
- `playerStatus`
- `worldEnvironment`
- `inventoryItems`
- `activeEffects`
- `facts`
- `capturedAtMillis`

### 6.1 采集策略

| 策略 | 适用字段 | 说明 |
|---|---|---|
| 事件驱动 | screen、聊天、世界事件、输入 | 事件发生时更新轻量状态 |
| 请求捕获 | `INTERACTION_CONTEXT` | 请求到达后，下一次 client tick 捕获 |
| 请求刷新 | 背包、药水、玩家状态、世界环境 | 每次只捕获本次明确请求的动态字段组 |
| Dirty 标记 | 交互快照 | dirty 不主动扫描，没人请求不刷新 |

第一版明确不做：

- 不常态扫描完整背包
- 不常态扫描附近实体
- 不做固定间隔保鲜
- 不做 request interest

## 7. IA / IR 的关系

映迹不直接参与业务决策，只提供当前上下文。

IA / IR 的调用方式：

```text
业务模块
  -> 请求 PRESENCE.QUERY_CONTEXT
  -> 映迹下一次 client tick 捕获 / 刷新
 -> 返回不可变快照
 -> 业务模块继续处理

世界退出后，旧查询不会延迟到新世界继续完成；排队请求会以世界会话结束失败，业务模块应按自身生命周期处理该结果。
```

IA 使用快照做 owner 仲裁。

IR 使用快照中的物品上下文做命名物体增强。

## 8. 当前 NeoForge 结构

```text
tianshu-client/src/main/java/.../client/presence/
  PresenceClientRuntime
  PresenceModule
  PresenceModuleInstaller
  PresenceProtocolAdapter
  PresenceStateStore
  PresenceTextProvider

tianshu-client/src/main/java/.../client/presence/capture/
  PresenceChatMessageSink
  PresenceEventCollector
  PresenceRefreshPolicy
  PresenceWorldEventSink

tianshu-client/src/main/java/.../client/presence/context/
  PresenceContextFactMapper
  PresenceContextGroup
  PresenceContextQueryCoordinator

tianshu-client/src/main/java/.../client/presence/model/
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

tianshu-client/src/main/java/.../client/presence/status/
  PresenceDisplayPolicy
  PresenceHudDisplay
  PresenceModuleStatusMapper
  PresenceStatusPriority

tianshu-client/src/main/java/.../client/presence/hud/
  PresenceHudSettings

tianshu-neoforge/src/main/java/.../ui/hud/
  PresenceHudRenderer
  PresenceHudElementController
  PresenceHudElementFrame
  PresenceHudElementRenderer
  PresenceHudElementState
  PresenceHudElementTiming
  PresenceHudElementType
  PresenceStatusTextElementController
  PresenceStatusTextElementRenderer

tianshu-client/src/main/java/.../client/presence/diagnostics/
  PresenceDebugPipelineSnapshot

tianshu-client/src/main/java/.../client/settings/module/presence/
  PresenceSettingsRegistrySource

tianshu-client/src/main/java/.../client/host/
  ClientGameContextProvider

tianshu-neoforge/src/main/java/.../adapter/
  NeoForgePresencePlatform
  NeoForgePresenceScreenClassifier
  NeoForgePresenceTextProvider

tianshu-neoforge/src/main/java/.../config/
  ClientConfigPresenceHudSettings

tianshu-neoforge/src/main/java/.../event/
  NeoForgePresenceHooks
  NeoForgePresenceAdvancementTracker

```

## 9. 当前完成度

已完成：

- 模块骨架
- HUD 状态展示
- Presence topic
- Presence capability
- IA / IR / AX 接入 Presence 查询
- 旧的同步上下文桥已移除
- 世界事件 topic 已纳入 Presence 一期/二期范围
- HUD 绘制位于 `tianshu-neoforge` 的 `ui/hud`
- 映迹核心只输出 `PresenceHudDisplay`
- 世界退出会清空旧快照、状态和排队查询，重新进入后建立新世界会话
- 单一动态图标只呈现产品交互阶段，加载细节和调试信息不进入图标
- 设置页已可控制 HUD 总开关、状态文本和 ASR / LLM / TTS / AX 状态来源
- 内测调试页已可查看 ASR / IA / AX / LLM / TTS / Presence 最新模块状态

待继续观察：

- 快照字段组是否还需要再收紧
- UI 是否需要进一步拆分
- HUD 是否需要新增 icon / shader 等元素

## 10. 设计原则

1. 映迹核心位于 `tianshu-client`，NeoForge 只提供 Minecraft 状态读取和 HUD 绘制适配。
2. 玩家可见状态要克制，优先轻量、短 TTL、可降级。
3. IA / IR 只使用映迹提供的快照。
4. payload 只承载 DTO，不携带 Minecraft 活对象。
5. 业务 topic 由业务模块拥有，映迹只订阅或发布自己拥有的 topic。
6. 动态详细字段按请求捕获，没人请求不读取，不进行固定保鲜扫描。
7. 协议处理线程不直接读取 Minecraft 活对象。
8. 采集和文本映射依赖 client 端口；NeoForge / Minecraft 版本敏感实现留在 adapter、event 和 UI 包。
9. HUD 绘制属于 GUI 层；映迹核心只做显示状态控制。
10. 设置页只控制显示策略，不影响采集、协议订阅和状态生成。
11. shader / icon / 动画参数属于具体 HUD 元素，不进入 Presence 协议和采集模型。
12. 内测调试视图只读观察现有状态缓存，不新增协议能力、不记录历史流水。
