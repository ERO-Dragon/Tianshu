# 映迹：游戏内反馈与交互上下文模块设计

## 快速摘要

1. **功能**：映迹为游戏提供当前环境信息，并为一个状态机控制的 shader 提供稳定产品状态。
2. **上下文**：IA、IR、AX 等模块通过协议中心按需请求玩家、世界、背包、效果和交互信息；返回内容只对应当前世界。
3. **产品状态**：主状态只包含加载、空闲、处理任务、思考和回复；聆听是可与主状态同时存在的前景状态，不展示模块名、任务编号、错误或模型内部阶段。
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
- `PRESENCE.WORLD_EVENT` 已收敛（聊天作为事件类型并入，见文末修订）
- 旧的同步上下文桥已移除
- IA / AX 已切到 Presence 请求链路；IR 不再查询（见 §7）
- 空 fact 请求不再隐式返回默认上下文

当前规则：

- 所有字段组（含交互上下文）都在请求被回答的那一刻采集，没有“事件保温”路径。
- 玩家状态、背包、装备、药水效果和世界环境等动态事实每次请求都重新读取当前世界。
- 同一 client tick 中的多个请求共享一次平台快照，避免重复读取 Minecraft 对象。
- 采集抛异常时只让这一批请求失败，不能把异常抛进客户端 tick。

二期原则：

- 不做固定保鲜扫描
- 不做 interest 体系
- 没请求就不扫
- 交互快照失效只影响下次按需读取，不主动触发扫描
- 请求方必须显式传 `requestedFactIds`

动态事实按请求捕获，没有 dirty 标记：没人请求就不读，请求一次读一次。

当前字段组：

```text
INTERACTION_CONTEXT
PLAYER_INVENTORY
PLAYER_EQUIPMENT
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
- 设置页只控制 HUD 总开关和可选显示元素，不改变产品活动的来源或语义
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
- `PresenceActivityTracker` 聚合显式 `PRESENCE.ACTIVITY`，并输出主状态与独立 `listening` 标记
- `PresenceHudSettings` 控制 HUD 总开关、状态文本、图标、尺寸、视觉预设和位置
- Presence 图标由 NeoForge GUI 层按渲染帧采样，使用单调时间驱动 shader 或 Java fallback；状态切换的 loading 层采用连续透明度过渡。渲染帧消费的是 Presence client 已缓存的不可变展示快照，不在每帧扫描活动表或重新计算状态。
- 映迹设置页已接入设置控制台，不再暴露按 ASR / LLM / TTS / AX 过滤产品活动的旧选项
- 内测调试开关已接入设置页，默认关闭
- 模块流水线调试视图只读读取 `ModuleStatusCache`，不订阅新 topic，不保存历史
- AX 在 IA delivery 建立有效回合后显式发布 `THINKING`，首次可见输出后切换为 `RESPONDING`

后续方向：

可能方向：

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
- 在请求被回答时按字段组采集，同一 tick 合并成一次平台抓取
- 聚合 `PRESENCE.ACTIVITY` 并输出 `PresenceActivitySnapshot`
- 处理 `PRESENCE.QUERY_CONTEXT`
- 发布 `PRESENCE.WORLD_EVENT`（聊天是其中的 `chat_message` 事件类型）
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

- IA / AX 等模块按需通过 `PRESENCE.QUERY_CONTEXT` 请求当前客户端上下文
- 请求方通过 `requestedFactIds` 指定需要的事实
- 映迹返回 `PresenceContextSnapshotPayload`
- 需要时由下一次 client tick 捕获或刷新

### 5.2 Topic

```text
PRESENCE.WORLD_EVENT
```

`PRESENCE.WORLD_EVENT` 是映迹自己的世界事实通道，**一条 payload 描述一个 kind 在 1 秒窗口里的全部发生**：

- `occurrences` 是分组列表：相同 `values` 发生 N 次合成一组 `Occurrence(N, values)`；一秒内 20 个泥土 + 60 个石头是同一条 payload 的两组；
- `values()` 返回第一组、`totalCount()` 返回总次数——旧代码按单条事实读也能跑，只是看到第一组；
- 每种 kind 每秒最多一条 payload；每 kind 每窗口最多 32 组不同值，超出部分计数并写一条 `presence.world_event_capped` 诊断（只记 kind 与条数）；
- 发布在 `PresenceClientRuntime.tick()` 里结算，窗口满 1 秒才发；换世界直接丢弃未结算窗口，不带进新世界。

聊天不再有独立 topic：它是 `chat_message` 这个 kind（见文末修订）。

```text
PRESENCE.INTERACTION_CONTEXT
```

`PRESENCE.INTERACTION_CONTEXT` 是"玩家此刻在跟什么交互"的摘要，供思衡维护预中标人（§5.4）：

- 只含仲裁条件能读到的上下文：准星命中与目标实体、主手与装备物品 id、潜行、交互键、tags、最近白名单实体、屏幕类型；
- 在 `PresenceClientRuntime.tick()` 里比较这些值，**变了才发**，topic 用 `LATEST_ONLY`；
- 读的都是现成数据（`hitResult`、主手栈、当前 Screen、已有的白名单实体缓存）：不额外做 raycast、不扫背包、实体只带 ref id、类型 id 与距离，不带 NBT；
- 它不保温 §6 的查询快照——查询仍然现采，两条路互不共享缓存。

### 5.3 产品状态图标

映迹对外只提供一套产品状态，不为每个模块分别建立 HUD 状态。状态来自模块显式发布的 `PRESENCE.ACTIVITY`，不会从 `MODULE.STATUS`、`LLM.STATUS` 或 `TTS.PLAYBACK` 推断。

主状态固定为：

主状态（互斥，只有一个）：

- `LOADING`：模型或服务正在真实加载、预热，暂时还不能提供对应能力。
- `IDLE`：当前没有仍然有效的主活动。
- `THINKING`：IA 决定这一轮交给辅星之后，到首段可见输出之前。
- `RESPONDING`：辅星已产生首段可见输出，正在继续展示或播报本轮回复。
- `RECONNECTING`：某条能力通道启动失败后正在退避重连，恢复后自动结束。
- `ERROR`：某条能力已确定不可用，需要玩家处理（配置、凭据、模型文件）。

并行标记（可以与任意主状态同时存在）：

- `LISTENING`：玩家按住说话键开始，直到仲裁给出结果（由思衡结束）。
- `PROCESSING_TASK`：后台任务正在跑，包括 AX 记忆维护；只是"还在忙"的标记，不参与排序。

每种活动类型的所有者与显示规则声明在 `PresenceActivityType` 上（`publisherModuleId` / `priority` / `primary` / `endableByAnyModule`）：思考与回复只接受 `module.ax`；聆听由 `module.asr` 开始、由解析这一轮的模块结束；错误与重连任何模块可发布；加载与后台任务任何模块可发布。聚合器不再列举模块名。

主状态优先级为：

```text
RESPONDING > THINKING > ERROR > RECONNECTING > LOADING > IDLE
```

排序按玩家注意力：前台对话永远在上；没有对话在跑时，失败压过自恢复的重连与加载；后台任务不参与排序。失败不打断正在进行的对话——模块出错时先结束自己的阶段活动。

`LISTENING` 的生命周期按"一轮语音"算，而不是按麦克风：按下说话键开始，识别、文本修复、仲裁都属于这一轮，仲裁出结果时由思衡结束；这样"松开键到辅星开始思考"之间不会闪回待机。聆听结束后 HUD 恢复仍然有效的主状态。

`PROCESSING_TASK` 与 `LISTENING` 都是并行标记：HUD 在主状态旁边画一个标记（聆听波纹 / 后台工作小点），不创建组合枚举，也不改变主状态。`DEGRADED` 已删除——它是性能细节，不是玩家能感知的产品状态。

两种运行时状况只表达"这条能力现在能不能用"：`RECONNECTING` 在通道恢复或模块停止时显式撤回，`ERROR` 在失败时发布、恢复时撤回，TTL 只是进程崩溃时的兜底。错误码、堆栈、队列长度、细分加载阶段和下载进度仍不进入产品状态；需要调试时只在设置页调试区域、诊断或日志查看。未来 shader 只消费 `primaryState`、`listening`、`backgroundTask` 和状态持续时间，视觉参数不进入协议 payload。

### 5.4 对话伙伴标记：预中标人与中标人

映迹在主图标上显示"你现在在和谁说话"。这是**并行展示信息**，不参与主状态。这里有两个不同的东西，混在一起会让倒计时语义出错：

| | 怎么产生 | 租期 | 什么时候变 |
|---|---|---|---|
| **预中标人** | 上下文命中：准星指着谁、主手拿着什么、屏幕状态 | 没有，不倒数也不续租 | 上下文一变就变 |
| **中标人** | 某一轮真正仲裁给了谁 | 有，按注册档位开租期，每轮再中标则续租 | 每轮仲裁出结果、租期到期 |

- 预中标人回答"如果现在开口会归谁"，与 ASR 无关：不说话、不按键，指针一移它就变。
- 中标人才决定"接下来这段时间没有硬 claim 的话归谁"；租期是标记上唯一会倒数的时间。
- 主状态仍然只有 `RESPONDING / THINKING / ERROR / RECONNECTING / LOADING / IDLE`；
- 第三方伙伴（酒狐等）的思考与回复是它们自己的界面，映迹不显示、也不为它们发布 `THINKING` / `RESPONDING`；
- 仲裁出结果后，如果承接者不是辅星，主状态从"正在聆听"直接落回 `IDLE`，伙伴标记按预中标或租期继续显示；
- 不新增活动类型，`THINKING` / `RESPONDING` 的所有者规则不变。

#### 5.4.1 仲裁规则（思衡负责，映迹只消费结果）

1. **硬 claim 优先，且上下文高于醒词**：指着某个会 claim 的实体说话，一定归它，即使句子里点了别的模块的名字——被指着的人有预约，醒词没有。
2. **没有硬 claim**：上一个中标人的租期未过期 → 仍然归它，租期从这一轮重新计时。
3. **租期已过期或从来没人中标** → 回归默认承接者：辅星。

- 档位只有四档：`20s` / `10s` / `5s` / `0s`，模块注册时声明；辅星用 `10s`，`0s` 表示不持有（每轮都必须靠硬 claim）。
- 硬 claim 换人时，原中标人的租期**立即作废**；新中标人的租期从这一轮开始算。
- 硬 claim 用"说话开始时冻结的上下文"计算：即使还没有文本，也能命中上下文类 claim（准星实体、手持物、屏幕状态等）。这份冻结帧就是 §5.2 摘要的最近一次推送，不现抓。
- 租期只属于中标人：预中标人没有租期，指针移开就没了，不存在"倒数到一半"的预中标。
- 聆听期间屏幕上显示的就是预中标人——它在开口之前就已经在了，和之后的真实仲裁用的是同一份上下文，不会出现"预览说酒狐、结果给辅星"。

#### 5.4.2 契约：`DIALOGUE.OWNER_PREVIEW`

载荷放到 `tianshu-api`（映迹作为宿主需要引用），主题保持 `LATEST_ONLY`，只在变化时发布。

| 字段 | 语义 |
|---|---|
| `playerId` | 伙伴归属的玩家 |
| `moduleId` / `participantId` | 当前预中标/中标者的身份，用于诊断和活动归属 |
| `icon` | 参与者注册时必须提供的贴图路径（`namespace:textures/...`），映迹只渲染合法 `ResourceLocation`；默认承接者不绘制，但仍需提供 |
| `defaultOwner` | 是否为默认承接者（辅星）。默认承接者不画伙伴图标 |
| `holdUntilMillis` | 中标租期的截止时间；`0` 表示没有租期（预中标，或 `0s` 档） |
| `holdDurationMillis` | 该参与者的档位时长，倒计时环据此换算进度；预中标时为 `0` |
| `updatedAtMillis` | 这次预览的生成时间 |

- **不包含显示名**：HUD 不显示任何文字，伙伴身份只靠图标/效果表达；显示名仍留在参与者描述里供思衡诊断使用。
- 发布时机只有三种：**预中标人变化**、**中标（含续租）**、**租期到期回默认**；另外参与者注销、模块停止、世界退出时清空。没有固定刷新周期，没有事件就没有流量。
- 去重按"显示状态"比较，键里必须包含 `holdUntilMillis`：同一个人续租时截止时间变了，不重发一次环就不会重走。
- 世界退出后清空；没有承接者时发布空预览，映迹隐藏标记。

#### 5.4.3 渲染

- **伙伴图标**画在主 shader 图标正中央，尺寸按主图标缩放（视觉预设里的比例，例如 0.55）；图标缓慢自转。
- **换人**：旧图标旋转淡出、新图标旋转淡入。动画参数属于视觉预设，不进协议。
- **倒计时环**：沿主图标外圈画一圈进度环，进度 = `剩余租期 / holdDurationMillis`，转满一周即到期退出。**环的可见性 = 画面上这个人有没有未过期的租期**：预中标人没有租期，只画图标不画环；预中标人恰好就是租期持有人时环继续走（不会因为"看了一眼酒狐"而消失）；画的是别人时，原来那位的租期在后台照走，环不出现。
- **始终显示**：预中标期间和空闲的租期内都显示——它回答的是"当前目标是谁"，不是"正在处理"；没有预中标、租期到期或换回默认后淡出。
- **默认承接者（辅星）**：不画伙伴图标、不画倒计时环，由主图标自身的 shader 效果承担"当前伙伴是辅星"的表达（具体形态属于视觉预设）。
- 贴图缺失（资源包没有该路径）只记一条诊断并跳过图标，不显示破图，也不阻塞主状态。

#### 5.4.4 边界

- 伙伴标记不改变主状态，不参与主状态排序，也不产生组合枚举。
- 第三方伙伴的回复阶段没有任何映迹状态：聆听结束即回到 `IDLE`，伙伴标记继续按预中标或租期显示。
- 每帧只读不可变快照与单调时钟；图标、环、旋转都是本地渲染，不产生协议流量。
- 租期到期只需要在"当前没有预中标人"时排一次到期任务：有预中标在显示时，到期不改变画面，等预中标消失的那次上下文变化再落回默认。

## 6. 交互上下文

映迹维护的是轻量快照，不是完整客户端状态。

当前快照字段：

- `playerId`
- `dimensionId`
- `dimensionDisplayName`
- `screenKind`
- `heldItemId`
- `heldItemDisplayName`
- `equippedItemIds`
- `crosshairTarget`
- `interactionKeyDown`
- `attackKeyDown`
- `sneaking`
- `playerStatus`
- `worldEnvironment`
- `inventoryItems`
- `equipment`
- `activeEffects`
- `capturedAtMillis`

### 6.1 采集策略

| 策略 | 适用字段 | 说明 |
|---|---|---|
| 请求捕获 | 全部字段组 | 请求被回答的那一刻读取一次，没有保温或后台扫描 |
| 同 tick 合并 | 一次抓取的并集 | 同一 client tick 内排队的请求共享一次平台采集 |
| 变化推送 | 仅交互上下文摘要 | §5.2 的摘要：每 tick 比较摘要字段，变了才推；不参与查询快照 |
| 降级 | 任意字段组 | 采集抛异常时这一批请求失败（`PRESENCE_CAPTURE_FAILED`），不抛进客户端 tick |

变化推送不是保温：推的是一份独立的、只含仲裁条件所需字段的摘要，查询快照该现采还是现采，两者不共享缓存。

第一版明确不做：

- 不常态扫描完整背包
- 不做无白名单的常态附近扫描（claim 需要的最近实体沿用既有白名单低频扫描）
- 不做固定间隔保鲜
- 不做 request interest
- 不用按键 / 鼠标 / 屏幕事件保温查询快照（查询时现采；屏幕变化只驱动 §5.2 的预约摘要推送）

## 7. IA / IR 的关系

映迹不直接参与业务决策，只提供当前上下文。

IA / IR 的调用方式：

```text
业务模块
  -> 请求 PRESENCE.QUERY_CONTEXT
  -> 映迹下一次 client tick 采集并回答
 -> 返回不可变快照
 -> 业务模块继续处理

世界退出后，旧查询不会延迟到新世界继续完成；排队请求会以世界会话结束失败，业务模块应按自身生命周期处理该结果。
```

IA 与映迹有两条关系：

- 常态订阅 §5.2 的交互上下文摘要，维护预中标人（§5.4）——不说话、不按键，指针一动它就在更新；
- 玩家开始说话时，把最近一次摘要按 sessionId 冻结做本轮仲裁，不再现抓一次完整快照，所以仲裁没有上下文等待；
- 承接者自己需要的完整上下文按需自取（辅星已经在自己的回合里查 dynamic facts），不经过仲裁路径。

IR 不再查询映迹：命名物体增强改为在辅星侧自采（`AsrTextRepairer` 的注释说明了代价——代词不再解析到手持物，其余不变）。

## 8. 当前 NeoForge 结构

```text
tianshu-client/src/main/java/.../client/presence/
  PresenceClientRuntime
  PresenceModule
  PresenceModuleInstaller
  PresenceProtocolAdapter
  PresenceTextProvider

tianshu-client/src/main/java/.../client/presence/capture/
  PresenceEventCollector
  PresenceWorldEventSink

tianshu-client/src/main/java/.../client/presence/context/
  PresenceContextFactMapper
  PresenceContextQueryCoordinator
  PresenceContextResponder

tianshu-api/src/main/java/.../client/presence/context/
  PresenceContextGroup

tianshu-api/src/main/java/.../client/presence/model/
  PresenceActivitySnapshot
  PresenceContextSnapshot
  PresenceEquipment
  PresenceInventoryItem
  PresencePlayerStatus
  PresencePotionEffect
  PresenceScreenKind
  PresencePrimaryState
  PresenceTargetSnapshot
  PresenceWorldEnvironment

tianshu-client/src/main/java/.../client/presence/status/
  PresenceActivityTracker
  PresenceDisplayPolicy
  PresenceHudDisplay

tianshu-client/src/main/java/.../client/presence/hud/
  PresenceHudSettings
  PresenceHudVisualPreset
  PresenceHudVisualState

tianshu-adapter/neoforge-1.21.1/src/main/java/.../neoforge/ui/hud/
  PresenceHudRenderer
  PresenceHudElementController
  PresenceHudElementFrame
  PresenceHudElementRenderer
  PresenceHudElementState
  PresenceHudElementStateTracker
  PresenceHudElementTiming
  PresenceHudElementType
  PresenceHudElementUpdateContext
  PresenceHudLayout
  PresenceHudPositionEditorScreen
  PresenceHudShaderRegistry
  PresenceHudVisualParameters
  PresenceHudVisualTransition
  PresenceIconElementController
  PresenceIconElementRenderer
  PresenceStatusTextElementController
  PresenceStatusTextElementRenderer

tianshu-client/src/main/java/.../client/presence/diagnostics/
  PresenceDebugPipelineSnapshot

tianshu-client/src/main/java/.../client/settings/module/presence/
  PresenceSettingsRegistrySource
  PresenceSettingsAccess
  PresenceHudPositionEditorOpener

tianshu-api/src/main/java/.../client/host/
  ClientGameContextProvider

tianshu-adapter/neoforge-1.21.1/src/main/java/.../neoforge/adapter/
  NeoForgePresencePlatform
  NeoForgePresenceScreenClassifier
  NeoForgePresenceTextProvider

tianshu-adapter/neoforge-1.21.1/src/main/java/.../neoforge/config/
  ClientConfigPresenceHudSettings

tianshu-adapter/neoforge-1.21.1/src/main/java/.../neoforge/event/
  NeoForgePresenceHooks
  NeoForgePresenceAdvancementTracker

```

规划新增（伙伴标记，尚未实现）：

```text
tianshu-client/src/main/java/.../client/presence/context/
  PresenceInteractionContextWatcher      §5.2 的摘要变化检测与推送
tianshu-client/src/main/java/.../client/presence/owner/
  PresenceOwnerPreviewStore              §5.4.2 的最新预览快照
tianshu-adapter/neoforge-1.21.1/src/main/java/.../neoforge/ui/hud/
  PresencePartnerIconRenderer            §5.4.3 的伙伴图标、倒计时环与旋转淡入淡出
```

## 9. 当前完成度

已完成：

- 模块骨架
- HUD 状态展示
- Presence topic
- Presence capability
- IA / AX 接入 Presence 查询（IR 已退出这条链路）
- 旧的同步上下文桥已移除
- 世界事件 topic 已纳入 Presence 一期/二期范围（聊天作为事件类型并入）
- HUD 绘制位于 `tianshu-adapter` 的 `neoforge/ui/hud`
- 映迹核心只输出 `PresenceHudDisplay`
- 世界退出会清空旧状态和排队查询，重新进入后建立新世界会话
- 上下文只在查询被回答时采集；同一 tick 的请求共享一次平台抓取；交互上下文摘要另走变化推送，不参与查询快照（§5.2）
- 产品状态机只呈现显式活动；错误码、加载细节和调试信息不进入产品状态，运行时状况只表达能力可用性，并在恢复时撤回
- 设置页只控制 HUD 总开关和状态文本，不过滤真实产品活动来源
- 内测调试页已可查看 ASR / IA / AX / LLM / TTS / Presence 最新模块状态

待实现（伙伴标记）：

- §5.2 的交互上下文摘要推送
- §5.4 的预中标人/中标人状态机，以及 `DIALOGUE.OWNER_PREVIEW` 载荷迁到 `tianshu-api`
- §5.4.3 的伙伴图标、倒计时环与旋转淡入淡出

待继续观察：

- 快照字段组是否还需要再收紧
- UI 是否需要进一步拆分
- 状态机 shader 的视觉形态、位置、尺寸和动画参数
- 伙伴标记实机可读性：准星扫过多个可 claim 实体时，图标切换是否刺眼

## 10. 设计原则

1. 映迹核心位于 `tianshu-client`，NeoForge 只提供 Minecraft 状态读取和 HUD 绘制适配。
2. 玩家可见状态要克制，优先轻量、短 TTL、可降级。
3. IA 只使用映迹提供的快照；IR 与辅星按需自采，不向映迹要第二次。
4. payload 只承载 DTO，不携带 Minecraft 活对象。
5. 业务 topic 由业务模块拥有，映迹只订阅或发布自己拥有的 topic。
6. 动态详细字段按请求捕获，没人请求不读取，不进行固定保鲜扫描；唯一例外是 §5.2 的预约摘要——它按变化推送，且不保温查询快照。
7. 协议处理线程不直接读取 Minecraft 活对象。
8. 采集和文本映射依赖 client 端口；NeoForge / Minecraft 版本敏感实现留在 adapter、event 和 UI 包。
9. HUD 绘制属于 GUI 层；映迹核心只做显示状态控制。
10. 设置页只控制显示策略，不影响采集、协议订阅和状态生成。
11. shader / 动画参数属于具体 HUD 元素，不进入 Presence 协议和采集模型。
12. 内测调试视图只读观察现有状态缓存，不新增协议能力、不记录历史流水。

---

## 修订：聊天已并入世界事件

上文的「聊天消息独立 payload / topic」设计已被取代，据此实现的代码为：

- 删除 PresenceChatMessagePayload、PresenceChatMessageSink 与 PRESENCE.CHAT_MESSAGE topic；
- 聊天改为 PresenceWorldEventPayload 的 **EVENT_CHAT_MESSAGE（chat_message）事件类型**，
  值与其它世界事件同形：VALUE_SENDER_ID / VALUE_SENDER_NAME / VALUE_TEXT；
- 发布走世界事件 sink，消费方只按事件类型分支——消费侧（辅星）把 chat_message 送进近期对话，
  其它事件送进记忆。

理由：聊天本来就是"世界里发生了一件事"，独立的载荷让每个消费方都要在两套形状之间切换。

---

## 修订：伙伴标记拆成预中标人与中标人

先前的"空闲期每 500ms 刷新持有者"版本作废：

- 预中标人由上下文变化驱动（§5.2 的摘要），随时可变，没有租期、没有倒计时；
- 中标人由每轮仲裁驱动，按注册档位开租期，倒计时环走的是它；
- 随之删除：500ms 轮询、打分等待、开口时的上下文补抓，以及"聆听开始"和"仲裁出结果"两次预览发布——后者本来也不该另发一次，中标会改写预中标状态，走的是同一条"变了才发"；
- 租期到期只排一次到期任务，且只在当前没有预中标人显示时才需要排。