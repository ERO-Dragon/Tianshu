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

## 2. 模块定位

映迹是 `tianshu-neoforge` 侧的客户端模块，负责：

- 读取 NeoForge / Minecraft 客户端状态
- 维护不可变快照
- 渲染轻量 HUD
- 通过协议中心暴露交互上下文查询能力
- 广播低频世界事件
- 发布映迹自己拥有的低频客户端 topic

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
- 让映迹只刷新请求过且 dirty 的字段组
- 让快照结构稳定，不再依赖同步桥

已完成：

- `PRESENCE.QUERY_CONTEXT` capability 已接通
- `PRESENCE.WORLD_EVENT` / `PRESENCE.CHAT_MESSAGE` topic 已收敛
- 旧的同步上下文桥已移除
- IA / IR / AX 已切到 Presence 请求链路
- 空 fact 请求不再隐式返回默认上下文

待做：

- 通过体验验证观察字段组是否还需要再拆细
- 观察 HUD 状态文案和优先级是否需要调整

二期原则：

- 不做固定保鲜扫描
- 不做 interest 体系
- 没请求就不扫
- dirty 只表示缓存不可信，不主动触发扫描
- 每个字段组独立 dirty / missing
- 请求方必须显式传 `requestedFactIds`

当前字段组：

```text
INTERACTION_CONTEXT
PLAYER_INVENTORY
PLAYER_ACTIVE_EFFECTS
PLAYER_STATUS
WORLD_ENVIRONMENT
```

### 3.3 三期：UI 解耦与能力扩展

三期解决“映迹怎么继续长，但不把自己长成怪物”。

三期目标：

- 把 HUD 渲染进一步从采集链路里拆开
- 给更复杂的展示预留纯渲染层，不回流到协议 adapter
- 给未来的新模块预留统一查询口，但不扩成通用总线
- 让 Presence 继续保持“轻量上下文模块”，而不是全局运行时

三期预留：

可能方向：

- UI 渲染进一步解耦
- 更细的状态展示样式
- 新的低频 Presence topic
- 新模块接入统一查询

三期不做：

- 不把映迹改成完整 GUI 框架
- 不把 Presence 改成业务调度中心
- 不把状态展示改成消息历史系统

## 4. 当前实现边界

映迹只做这些：

- 采集 NeoForge 客户端状态
- 维护 `PresenceStateStore`
- 处理 `PRESENCE.QUERY_CONTEXT`
- 发布 `PRESENCE.WORLD_EVENT`
- 发布 `PRESENCE.CHAT_MESSAGE`
- 本地 HUD 展示

映迹不做这些：

- 不替 AX 编排 prompt
- 不替 IA 做 owner 仲裁
- 不替 IR 做文本修复
- 不改协议中心功能定义
- 不在 common 引入 NeoForge 活对象

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
| 请求刷新 | 背包、药水、玩家状态、世界环境 | 只刷新被请求且 missing/dirty 的字段组 |
| Dirty 标记 | 所有详细字段组 | dirty 不主动扫描，没人请求不刷新 |

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
```

IA 使用快照做 owner 仲裁。

IR 使用快照中的物品上下文做命名物体增强。

## 8. 当前 NeoForge 结构

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
  PresenceContextGroup
  PresenceContextQueryCoordinator

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

## 9. 当前完成度

已完成：

- 模块骨架
- HUD 状态展示
- Presence topic
- Presence capability
- IA / IR / AX 接入 Presence 查询
- 旧的同步上下文桥已移除
- 世界事件 topic 已纳入 Presence 一期/二期范围

待继续观察：

- 快照字段组是否还需要再收紧
- UI 是否需要进一步拆分

## 10. 设计原则

1. 映迹是 NeoForge 层模块，不是 common 业务脑。
2. 玩家可见状态要克制，优先轻量、短 TTL、可降级。
3. IA / IR 只使用映迹提供的快照。
4. payload 只承载 DTO，不携带 Minecraft 活对象。
5. 业务 topic 由业务模块拥有，映迹只订阅或发布自己拥有的 topic。
6. 详细字段按字段组 dirty 管理，没请求不刷新。
7. 协议处理线程不直接读取 Minecraft 活对象。
