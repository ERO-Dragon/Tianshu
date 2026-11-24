# Dubhe 待办清单

> 2026-11-23：改造路线图 40/40 完成、余项清零；本清单保留仍未定的产品项。

> 两条轨道：**你要定的** 与 **我来做的**。定完一条就挪到 §三 并写进《Dubhe_改造方案》。
> 事件分类工具：[tools/event-matrix.html](../../../tools/event-matrix.html)。

## 一、你要定的

### 事件表

| # | 事项 | 手上有什么 |
|---|---|---|
| 1 | **事件在九宫格里的定位**（每行放哪个三态） | **已落表**：仓库表 `dubhe_event_tiers.json` 为 schemaVersion 5、**42 条** kind，每条按可达行各声明一个 `states`。`tools/event-matrix.html` 已按仓库表重建为同 42 条，并新增"不做"标记（已接通 28 / 可接 10 / 不做 4），可用它复核或导出 |
| 2 | 待定 6 条是否接线 | **已结案并两端核对**：`fall_damage`、`rare_item` 已接线；其余 4 条明确不做，理由见下表 |
| 3 | `chat_message` 落在哪一格 | 只能落"说话类"或"丢弃"，不能落历史类（否则同一句话出现两遍） |

### 产品层

| # | 事项 | 我的建议 |
|---|---|---|
| 1 | **记忆系统精细化**（可见层、来源粒度、删除语义、**跨存档**） | 专项设计，本次不收敛 |

### 工程决策

无待定。

## 二、我的待办

### 立刻可做（等你放行）

- [x] **P1 改名（包层 / 模块 / 类名已完，id 与文档待做）**：① 包层 `tianshu.function.` 已去掉；② 模块与目录 `tianshu-auxilium`→`tianshu-dubhe` 已完成；③ 类前缀 `AX*`/`Auxilium*`/`Ax*` → `Dubhe*`（307 个文件改名）已完成。**剩**：④ module id / 配置名 / 存储子树 / 语言键 / 能力 id；⑤ 文档文件名与正文。三步均通过全模块测试与五条架构门禁。

### 之后按阶段

- [x] **P2 入口层（完成）**：包骨架 `dialogue` / `agent` / `shared` 落位（main + test 镜像搬迁）；`protocol` 与 `settings` 留顶层；记忆包暂留原位。门禁：执行层不碰 `DubheChatOutput`/`DubheSpeechSink`、`general_dubhe.*.json` 的 system 文本不点技能名。段标签改名 `<dubhe_system>` → `<system_prompt>`。
- [ ] **P3 simple 调度（角色层）**
- [ ] **测试隔离债**：`IaModuleProtocolFlowTest` 在并行/顺序变化下偶发失败（单跑与重跑均通过），根因是跨用例静态注册残留；后续把每个用例改成自洽注册。：工具集恒为 `{delegate}`；`pending_work` 尾部块；承诺-兑现人设；触发源收敛；办事失败词表。
- [ ] **P4 代理层 + 技能（执行层）**：技能注册表（开放集、可见性、自主权限）、代理循环（选择/重试/暂停恢复/任务内压缩）、办事报告、`skillsUsed`；simple 走内部派单路径。
- [ ] **P5 事件层**：矩阵五档查表（G14）、三态改名（G15）、每行 `states` 已完成；**舟楫侧 A 组与第二批接线已完成**（词汇 `Tianshu-ferry df0193d5`，采集器 `Tianshu-adapter 52bc64e7` 与 `5f0e9d20`，双端各一条）；剩 H1 / H2 断言与"字面量值"那一条（`weather_change` 的 `rain`/`clear`、`respawn` 的 `minecraft:overworld`）。
- [ ] **P6 记忆**：`memory` 技能、压缩归属、播报过期、称呼延迟生效。
- [ ] **P7 技能精修**：`agentActingForPlayer(moduleId, operator, tier)` + 档位设置页（`P0-2` 清零）；首个会动手的技能；外交技能（G28）。
- [ ] **P8 对外接口**：`SEARCH` 一个下游技能；配额；第三方技能向内注册；接入指南。

### 文档与文案

- [ ] **首启文案**：系统消息一行（按 profile 一次）+ 首次越界解释（资源键）+ 设置页 description 改写。
- [ ] `Dubhe_事件系统` §4 整节重写（矩阵、五档、三态改名），与 P2b 同批。
- [ ] `Dubhe_文档索引` 在 P1a 改名后同步文件名。

## 三、已完成

| 事项 | 结果 |
|---|---|
| 三层：角色 → 代理 → 技能 | 方案 §1–§3，G1–G50 |
| 内部/对外切分 | G10：执行层不对外；对外只发布 `SEARCH` 一个可读技能，`relay` 是外交技能内 reference，`AGENT_TASK` 取消 |
| 改名方案 | 方案 §5，五步 + 10 个接线点 |
| 事件表模型 | G14 五档、G15 三态改名、§4.6 矩阵与映射、H1 高频不开口、H2 不插话 |
| 事件缺口盘点 | §4.5：18 条无生产者，分 A/B/C 处置 |
| 多人与称呼 | G16 只听本机玩家、G17 称呼=唤醒词且延迟到压缩后生效 |
| 人设与 UI | G18 语气只做一档、G19 不做新 UI（走映迹 HUD 契约） |
| 触发源 | G20 只有玩家输入与事件；不做"还在查"这类主动插话 |
| 授权细分 | G24 保守档内按域开关（背包/容器/方块/机器/合成/实体），口头可设 |
| 识别与注入 | G25 低置信度先确认；G26 不可信文本包裹 + 只认 delegate 标签 |
| 失败措辞 | 只报游戏内原因，API 原因只进诊断 |
| 多件事 | 不限制：一个委托内多步由 complex 规划；带实体的活转派给实体模组 |
| 称呼 | G17 修订：默认"你"，仅设置页文字可改，不支持语音改 |
| 重登与打断 | 重登不延续委托（丢弃并记日志）；朗读被打断即放弃、不重说 |
| 措辞 | 回执与完成都由模型生成，不固定文案；不失约靠票据终态 + 播报强制 |
| 内部回调与跨模块投递 | G27：内部技能完成后回投终态；跨模块只做投递（不限时长、反馈可选） |
| 外交技能 | G28：投递 + 按需查进度 + 接收回投；不轮询；进度 `RUNNING`/`DONE`/`UNKNOWN`，`UNKNOWN` 如实说 |
| 报告字段 | `facts` / `blocked` 不限条数（播报时压到一至三句）；失败原因只报游戏内原因 |
| 首启说明 | G21 系统消息 + 首次越界解释 + 设置页；系统消息按 profile 一次、不进 TTS |
| 事件表迁移方式 | 直接换表，不留旧格式兼容（换算恒等） |
| 子包名 | G8：`dialogue` / `agent` / `shared` / `protocol` |
| 配额口径 | G23：玩家侧不设；外部并发 1 + 排队 4 |
| 第三方技能 | G22：开放注册，三前提 |
| 契约版本化 | `schemaVersion` + 新能力 id；字段只增不改 |
| 九宫格工具 | `tools/event-matrix.html`，**42 条与仓库表逐条对齐**；已接通 28 / 可接 10 / 不做 4，支持链路高亮 |
| 文档计数修正 | 事件表 41/44 → 40，删 `respawn` 行 |
### 事件接线结论（`fall_damage` / `rare_item` 等 6 条）

数据源盘点后的结论：**接线 2 条，其余 4 条明确不做**。判定依据是"现有钩子上能不能顺手拿到"，而不是"想不想要"。

| 事件 | 结论 | 依据 |
|---|---|---|
| `tianshu:fall_damage` | **已接线** | 伤害钩子本来就在（NeoForge `LivingDamageEvent.Post`、Fabric `AFTER_DAMAGE`），采集器按 `DamageSource` 判定摔伤后发独立事件类型 `fall_damage`；天枢映射到既有 kind，九宫格与文案 `你摔伤了：%s` 早已存在 |
| `tianshu:rare_item` | **已接线** | 拾取钩子本来就在（NeoForge `ItemEntityPickupEvent.Post`、Fabric 背包增量采样），按**原版稀有度 RARE/EPIC** 判定，命中则发 `rare_item` 而不是普通 `pickup`，避免同一件物品走两条策略行 |
| `tianshu:durability_low` | **暂时不做** | 原版没有耐久变化事件，只能定时扫主/副手并记住"已提示过"，会引入新的轮询与阈值口径。等有人真的抱怨"快坏了也不提醒"再做，届时一并定阈值与扫描范围 |
| `tianshu:recipe_unlock` | **不做** | 没有事件可挂，唯一路径是改字节码（mixin 进 `ServerPlayer#awardRecipes`）；为一个提示语动字节码不值得 |
| `tianshu:structure_discovery` | **不做** | 没有事件，只能每几秒查一次结构并持久化"是否来过"；且"发现"的定义本身不清（每存档一次？每种结构一次？） |
| `tianshu:player_nearby_first` | **不做** | 需要按玩家×实体类型维护"第一次靠近"的持久状态并定时扫描，复杂度与收益不匹配 |

**新增的原生事件候选**（都在已有钩子上，成本与上面两条同级，随时可接）：`sleep`（入睡）、`respawn`（重生，已有 `death` 但是两件事）、`item_break`（物品损坏，`PlayerDestroyItemEvent` / Fabric 无对应，需采样）、`enchant`（附魔，NeoForge 有事件、Fabric 无）、`weather_change`（天气变化，服务端可直接比对 `isRaining`）、`eat_food`（进食，两端的 `UseItem` 完成事件）。
### 第二批接线（`sleep` / `respawn` / `weather_change` / `eat_food`）

四条全部接线，采集形态按"两端都能一致"选：

| 事件 | 采集方式 | 说明 |
|---|---|---|
| `tianshu:sleep` | NeoForge 走 tick 采样 `isSleeping` 翻转；Fabric 同 | 入睡是一次状态翻转，两端都没有稳定回调，采样与读标志同价 |
| `tianshu:respawn` | NeoForge `PlayerRespawnEvent`、Fabric `ServerPlayerEvents.AFTER_RESPAWN` | 与 `death` 是两件事：死是事实，重生是继续游戏的那一刻 |
| `tianshu:weather_change` | 两端 tick 比对 `isRaining`/`isThundering` | 世界级状态，按世界比对、发给该世界内的玩家 |
| `tianshu:eat_food` | 两端 tick 采样 `isUsingItem` + 食物组件 | 进食没有两端通用的"吃完"事件，结束使用 + 食物组件即事实 |

**遗留一项**：`weather_change` 的详情是 `rain` / `thunder` / `clear` 这类**枚举词**，当时管线把值当字面量渲染，中文句子里会出现英文词。**已解决**：见下文"接线后核对"里那条平台决策的落地——现在这些值走 `DubheEventText.Arg.Key`，由语言表出词。

### 接线后核对（2026-11-24，两端逐条看代码）

舟楫侧已落地：词汇 `Tianshu-ferry df0193d5`（`WorldEventTypes` 新增 `sleep` / `respawn` / `weather_change` / `eat_food` / `fall_damage` / `rare_item`），采集器 `Tianshu-adapter 52bc64e7`（NeoForge `GameFactCollector` + Fabric `FabricGameFactCollector`，两端形态一致）。天枢侧映射（`DubhePresenceEventMapper`）、表行（`dubhe_event_tiers.json`）与文案键三条都已就位，**没有断链**。

核对结论：

| 项 | 结果 |
|---|---|
| `fall_damage` | 两端在（`LivingDamageEvent.Post` / `AFTER_DAMAGE`，按 `getMsgId()=="fall"` 判摔伤） |
| `rare_item` | 两端在（N 端拾取钩子按 `Rarity` RARE/EPIC；F 端背包增量采样时顺带读稀有度） |
| `durability_low` / `recipe_unlock` / `structure_discovery` / `player_nearby_first` | 确认**仍无生产者**（全仓 grep 0 处），与"不做"结论一致 |
| `sleep` / `respawn` / `weather_change` / `eat_food` | 两端都在；`sleep`/`eat_food` 采样状态翻转，`weather_change` 按世界比对，`respawn` 走加载器事件 |

**新发现并已修的三个缺口**（舟楫侧，本次直接改掉；另有一条留给平台决策）：

1. ~~**`eat_food` 报成 Air**~~：采集点 `send(player, EVENT_EAT_FOOD, itemValues(player.getUseItem().copy()))` 发生在 `using` 由真变假的那一 tick；原版 `stopUsingItem()` 在吃完时已把 `useItem` 清空，读到的是空栈 → `minecraft:air` / `Air`。**已修**：进食期间记下食物栈，翻转时用它（两端同法，与 `sleepingOld` 同一种记账）。
2. ~~**Fabric 的 `rare_item` 缺 `itemName`**~~：只发 `itemId` + `count`，而天枢规则按 `(itemName, itemId)` 优先取名，详情退化成 `minecraft:diamond`。**已修**：稀有度与名字都从手上那格栈读，补 `VALUE_ITEM_NAME`。
3. ~~**玩家离开后按玩家记的状态不清**~~：`sleepingOld` / `eatingOld` / 载具 / 背包增量 / 放置待定等按玩家记账的 Map 只清了待发包，重登会把上一会话的"判断"带进新会话。**已修**：DISCONNECT 时一并清。

**留给平台决策的一条 → 已落地（2026-11-24）**：`respawn` / `dimension_enter` / `weather_change` 的值不再当字面量拼进句子。`DubheEventText` 的参数新增第三种形状 `Arg.Key`（语言键 + 原始 token 兜底），`DubheEventValues` 把 token 换成键（`weather.rain`、`dimension.minecraft.overworld`），键在客户端语言表里解析；没有条目的模组维度退回原始 id，不留空。语言片段从"字面量 / 嵌套模板 / 语言引用"三种里选一种，落点仍是渲染那一处。旧的那条"字面量值"遗留项随之关闭。