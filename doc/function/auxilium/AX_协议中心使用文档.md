# AX 协议中心

AX（辅星）通过协议中心参加天枢对话链路。本文说明 AX 对外暴露什么、订阅什么、调用什么，以及其他模块应当如何与它协作。以当前代码为准。

AX 是 IA 管理的 dialogue participant，不是可以绕过 IA 直接调用的公共聊天或 LLM 服务。

## 1. 身份与注册

| 项 | 值 |
|---|---|
| moduleId | `module.ax` |
| participantId | `tianshu.AX` |
| 提问型入口能力 | `AX_DIALOGUE_INPUT`（`DialogueDeliveryPayload`） |
| 上下文型入口能力 | `AX_CONTEXT_INPUT`（`DialogueContextInputPayload`） |
| 事件型入口能力 | `AX_EVENT_INPUT`（`AXWorldEventPayload`） |

注册 / 注销分别使用 `DIALOGUE_PARTICIPANT_REGISTER` / `DIALOGUE_PARTICIPANT_UNREGISTER`，并把自己的路由能力、唤醒词声明与语音触发组一并交给 IA。

**唤醒词**：AX 没有内置默认唤醒词。空唤醒词不改变"是否参加仲裁"：AX 照常注册 participant，只是不登记 wakeword、不产生 IA 的唤醒硬 claim，改由 `DEFAULT_OWNER` 档位与 fallback 分参与比较（AX 的 fallback 为 0.4，其他 participant 必须严格更高才能替代）。玩家配置非空词后才额外多一条 wakeword claim。其他模块不得冒用 `module.ax` / `tianshu.AX` 注册、注销或提交请求。

接入前提：调用方应作为天枢托管模块完成装配，在装配阶段通过模块运行时创建一个自己的协议 adapter。不要从核心管理器取完整协议运行时，也不要直接持有 AX 的编排、记忆或提示词实现。

## 2. 入站入口：提问型

| 项目 | 值 |
|---|---|
| 能力 | `AX_DIALOGUE_INPUT` |
| PayloadType | `DIALOGUE_DELIVERY` |
| Payload | `DialogueDeliveryPayload` |
| PacketType | `COMMAND` |
| Broker | `BOUNDED_QUEUE` |
| 完成策略 | `MANUAL_COMPLETE` |

这是 IA 选中 AX 为当前 owner 之后的定向投递入口，**不是**通用 prompt 接口。外部模块直接向它发送正文会绕过 participant 声明、会话 owner、轮次与时效边界。

### 2.1 载荷字段

| 字段 | 含义 |
|---|---|
| `sessionId` / `requestId` / `playerId` | 会话、请求与玩家身份（必填，空则拒绝构造） |
| `turnId` | 本轮标识 |
| `repairedText` | 修复后的玩家原话 |
| `normalizedText` | 归一化文本 |
| `objectMatches` | 命中的具名对象（玩家说出的词 + 解析到的 registry id） |
| `moduleMatches` | 命中的模块触发词（说出的词 + 该模块注册的词） |
| `displayNames` / `matchedEntityRefs` | 世界侧显示名（维度、手持、实体）与命中的实体引用 |
| `interactionHints` | 交互提示（准星目标等） |
| `contextSnapshot` | 投递时刻的上下文快照（按玩家兜底） |
| `timestampMillis` / `expireAtMillis` | 产生时刻与过期时刻 |

### 2.2 边界

AX 在处理前会检查自身就绪状态、会话与轮次、投递时效。调用方不能：自造 owner 身份或过期时间；把游戏活对象放进载荷；重放旧投递驱动新一轮回复；假定 AX 一定是 owner。

空正文或已过期投递不会产生回复。

## 3. 入站入口：上下文型

| 项目 | 值 |
|---|---|
| 能力 | `AX_CONTEXT_INPUT` |
| PayloadType | `DIALOGUE_CONTEXT_INPUT` |
| Payload | `DialogueContextInputPayload` |
| PacketType | `COMMAND` |
| Broker | `BOUNDED_QUEUE` |
| 完成策略 | `AUTO_COMPLETE_ON_RETURN` |

| 字段 | 含义 |
|---|---|
| `speakerName` | 发言人显示名，可为空 |
| `messageText` | 话语正文；空正文不写入 |
| `sourceTurnId` | 同一模块内稳定标识这段话语的可选字段 |

语义：把第三方话语写入当前作用域的近期对话上下文，**不调用模型、不产生语音、不占用会话、不经过 IA 仲裁**。

- 落点是**历史轮次**（事件角色的历史条目），正文形如 `同伴 <名字> 说：<内容>`——模型只看得到句子，所以说话人必须写进正文。
- 来源标识 = 信封 `sourceId` + `sourceTurnId`：同一模块内稳定，用于身份而不是去重。
- **AX 不对上下文型输入去重**：同一 `sourceTurnId` 重复投递会产生重复条目，调用方必须保证每段话语只在自己真正发生时投递一次。
- 写入的内容进入正常近期对话窗口，因此同样受窗口容量与后台记忆维护策略约束；它不是长期记忆入口，也不是无限制注入通道。
- 调用方不得用它伪造玩家身份或伪造 ASR/IR 事件。

面向的典型场景：NPC 对白、任务播报、环境台词——内容不需要 AX 回答，但 AX 应当知道"有人说过这句话"。

## 4. 入站入口：事件型

| 项目 | 值 |
|---|---|
| 能力 | `AX_EVENT_INPUT` |
| PayloadType | `AX_WORLD_EVENT` |
| Payload | `AXWorldEventPayload` |
| PacketType | `COMMAND` |
| Broker | `BOUNDED_QUEUE` |
| 完成策略 | `AUTO_COMPLETE_ON_RETURN` |

外部模组把"自己世界里刚发生的一件事"交给 AX：一句它自己已经渲染好的话，加一个它希望的行为。AX 不渲染、不改写措辞，只做清洗，随后走与内置事件完全相同的判定路径。

### 4.1 载荷字段

| 字段 | 含义 |
|---|---|
| `kind` | 命名空间化的事件类型（`^[a-z0-9_.-]+:[a-z0-9_./-]+$`）；内置表只占 `tianshu:`，第三方不得借用该前缀 |
| `behavior` | 希望的行为，三选一：`IMMEDIATE` / `DEFERRED` / `SILENT`；`DROP` 不接受——什么都不发生等于不投递 |
| `text` | 生产方已经渲染好的整句；AX 只做 NFC 归一、剥离尖括号、按边界截断到 120 字符 |

**频率不对外暴露**：`RARE` / `NORMAL` / `FREQUENT` 是 AX 的私有词汇，只有内置采集层知道"这个 kind 多久发生一次"，`AXEventPolicy` 再把它和名单折成下面的行为阶梯。外部只回答"这句要不要开口"，不回答"这类事多久一次"。

### 4.2 行为与落地

四条阶梯回答同一个问题：**要不要主动开口**。

| 行为 | 落地 | 玩家感知 | 模型可见 |
|---|---|---|---|
| `IMMEDIATE` | 到达即说；忙且不允许打断时排进立即队列，本轮结束后合并成一句说出 | 立刻或本轮结束后听到 | 是 |
| `DEFERRED` | 进延迟缓冲，冷却窗口结束后合成一次主动发言 | 稍后听到一句 | 是 |
| `SILENT` | 不进缓冲，直接进历史 | 不出声 | 是 |
| `DROP` | 不接受请求 | — | — |

行为是**请求**，不是决定。AX 保留两次下调：

1. **频率门**：同一 kind 短时间反复发生会被降一档（`IMMEDIATE` → `DEFERRED` → `SILENT`）。
2. **玩家名单**：内置 kind 可以被拉黑再降一档；外部 kind 目前恒为中性，玩家暂时无法把它们加进名单。

所以"要求立刻说"保证的是**不被更保守地处理**，不保证在你期望的那一刻出声。

### 4.3 边界

- **文案由生产方拥有**：AX 不查自己的语言表，所以外部模组可以自带 lang 文件；代价是 AX 不替它翻译或改写。
- **不去重**：同一次发生投递两次就是两条历史。生产方负责"每次发生只投一次"。
- **不校验来源**：任何模块都能投，`sourceId` 只记 provenance、不参与判定——与事件层"谁都能提交"的定位一致。
- **不触发回答**：不占会话、不走 IA、不请求模型。需要 AX 回答的输入走 §2 的提问型入口。
- 事件层没在运行时投递会被拒绝（`AX_NOT_READY`），不会静默吞掉。

### 4.4 投递方式

外部模组对 `tianshu-auxilium` 硬性依赖（`compileOnly` 足够，运行时由天枢提供），用
`EnvelopeBuilder.commandToCapability(自己的 moduleId, AXProtocolAdapter.EVENT_INPUT_CAPABILITY, PayloadType.AX_WORLD_EVENT, payload)`
建信封，再交给 `TianshuIntegrationApi.submit(envelope)`。

不要从服务注册表取 `AXGameEventService` 去调 `tick` / `flush` / `stop`：那三个方法是本模块自己的生命周期，不属于对外契约。

## 5. 外部文本如何到达 AX

标准玩家语音链路：

```text
ASR 发布最终文本
  → IR 修复并发布结果 topic
  → IA 订阅修复结果
  → IA 按 participant 与上下文选择 owner
  → 选中 AX 时，IA 向 AX 的提问型入口投递 DialogueDeliveryPayload
  → AX 处理本轮，并通过会话控制完成或释放
```

不要直接投递提问型入口，也不要伪造 ASR/IR topic。希望自己成为 owner 的模组应按 IA 的接入说明注册 participant，而不是把内容塞给 AX。

不需要回答、只需进入上下文的内容走上下文型入口；这条路径不经过 IR 与 IA 是有意的——IR 与 IA 服务于"玩家说了什么、这轮该谁回答"，而上下文型输入不产生回合所有权问题。

## 6. AX 调用的下游能力

下面这些是 AX 调用别人的能力，不是 AX 对外提供的服务：

| 能力 | 用途 |
|---|---|
| `LLM_REQUEST` | 当前 owner 轮次的对话请求；后台维护与委派走 TASK 车道 |
| `LLM_PRIMITIVE_QUERY` | token 计数、嵌入与运行时原语 |
| `LLM_CACHE_MANAGE` | 记忆检索（RAG 查询）与缓存管理 |
| `PRESENCE_QUERY_CONTEXT` | 获取允许的动态事实快照 |
| `TTS_SPEAK` | 播放 AX 可见回复 |
| `TTS_CONTROL` | 新一轮投递到达时停止 AX 自己的播报 |
| `DIALOGUE_SESSION_CONTROL` | 延长、释放或确认中断当前会话 |

AX 提交模型请求前会先登记流式片段与结果的处理器，再提交请求；停止、超时或模块停止时注销处理器并通过协议取消。外部模块若需要这些能力，应各自遵循 LLM / TTS / IA 的文档，不能复用 AX 的内部请求标识或缓存。

## 7. AX 订阅的事件

| 来源 | 用途 |
|---|---|
| 世界事件 topic（能映射成事件类型的） | 走**事件层**：写历史，并按档位/名单决定出不出声。当前映射（`AXPresenceEventMapper`）：`advancement_unlocked` → `tianshu:advancement`、`item_crafted` → `tianshu:item_crafted`、`player_death` → `tianshu:death`、`mob_killed` → `tianshu:mob_killed`、`fishing_catch` → `tianshu:fishing_catch`、`tamed` → `tianshu:tamed`、`dimension_changed` → `tianshu:dimension_enter`、`block_broken` → `tianshu:block_break`、`block_placed` → `tianshu:block_place`、`container_open` → `tianshu:container_open`、`mount` / `dismount` → `tianshu:mount` / `tianshu:dismount`、`item_pickup` → `tianshu:pickup`、`damage_taken` → `tianshu:damage_taken`、`effect_gained` / `effect_expired` → `tianshu:effect_gained` / `effect_expired`、`exp_level` → `tianshu:exp_level`、`friend_join` / `friend_leave` → `tianshu:friend_join` / `friend_leave`、`trade` → `tianshu:trade`、`respawn` → `tianshu:respawn`、`boss_engage` → `tianshu:boss_engage` |
| 服务端事实（网络包，服务端装了天枢才有） | 客户端收到后转成**同样的世界事件载荷**，因此与上一行同一条路径。服务端只发与本人相关的事实，客户端没装或通道版本不符时服务端不发送 |
| 世界事件 topic（其余类型） | 作为记忆附件并入当前作用域记忆，不写历史、不出声 |
| 聊天事件（`chat_message`） | 把聊天写入当前作用域的近期对话（事件角色，带说话人）——聊天是 WorldEvent 的一个类型，不是独立载荷 |

**一条事实只有一个落点**：能映射成事件类型的统一走事件层，不要再同时写进记忆附件；映射不上的才留在记忆里。这张映射表只回答"事件类型对应哪个 kind"，要不要开口仍由档位表与名单决定。

**一个载荷 = 一秒窗口内的多组出现**：映迹把同一 kind 的一秒聚合成一个载荷，每组带计数。AX 把这些组渲染成**一行带数量的事实**（`tianshu.ax.event.batch.entry` 渲染每组、`tianshu.ax.event.batch.join` 连接、最后由该 kind 的句子模板包住），并把总数写进 `AXEventPayload.count`，频率门据此降档。

这些是事件而不是 AX 请求入口。只有对应事实的所有者才应发布；其他模块不得伪造玩家身份、维度或聊天发送者。

**AX 不自己采样玩家状态**：没有确切来源就不产生事件，不做"两次读数相减"的推测。血量、饥饿这类状态由服务端组件按权威事件给出，客户端单跑时它们不出声也不记历史。

AX 不订阅 ASR 活动状态：ASR 活动由输入链路自行消费，不能直接改变 AX 的回合生命周期。

AX 会向模块状态 topic 发布 `module.ax` 的健康与诊断状态；订阅者必须按 moduleId 过滤，并通过客户端语言资源解析消息键（状态本身不携带兜底文本）。该 topic 不驱动任何产品状态。

## 8. 打断语义

新的对话轮次只来自 IA 已完成仲裁、明确选择 AX 为 owner 并实际投递的载荷。只有投递到达之后，AX 才能依据当前设置处理旧回合。

**允许打断时**（`allowInterruption` 为真）：

1. 使当前轮次的世代失效，并取消该轮登记的仍在进行的前置查询与对话请求；后台记忆维护请求不受影响。
2. 停止当前输出链路，并按来源停止 AX 自己提交的语音，不使用"停止当前"影响其他模块的播报。
3. 把已经显示或播放的部分正文保留到近期对话。
4. 通过标准会话控制释放旧轮次，随后只处理新投递。

**不允许打断时**（`allowInterruption` 为假）：新投递立即以拒绝终态释放，不排队、不产生回复；当前轮次继续完成。

ASR 活动状态不参与上述任何决策。

## 9. 活动与诊断

| 活动 id | 类型 | 含义 |
|---|---|---|
| `ax.chat.<sessionId>.<轮次标识>` | `THINKING` → `RESPONDING` | 一轮对话：上下文获取、检索、提示词装配与首段生成属于思考阶段；首次可见输出出现时原子切换到回答阶段。完成、失败、取消、打断、模块停止都会结束活动 |
| `ax.memory.maintenance.<世界标识>` | `PROCESSING_TASK` | 记忆维护等真实后台工作；只有任务被接受并真正执行后才开始，终态统一结束 |

诊断延迟阶段（仅在调试记录开启时发布，无正文）：投递 → 提示词就绪 → 模型已提交 → 首 token → 首句 → 语音已提交。每阶段最多发布一次，只用于诊断，不改变思考、回复、打断或会话释放逻辑。

## 10. 生命周期与线程

- capability / topic 处理器只做校验、状态交接与受控编排；模型、检索、存储、事实查询与维护任务都不占用游戏主线程。
- 停止时：停止维护、保存近期检查点、取消或清理在途请求、注销 participant。
- participant 未注册、模块未就绪、投递过期或 owner 已变化时，调用方不能假设 AX 会输出回复。
- 外部模块不得依赖 AX 的内部超时、提示词文件、记忆文件路径、执行车道容量或具体客户端类。

## 11. 接入决策表

AX 没有面向任意外部模块的同步"请求—回答"能力：所有入口都是投递，回答只发生在 IA 选中 owner 之后。按需求选真正的所有者：

| 需求 | 正确入口 |
|---|---|
| 让 AX 回答某句话 | 按 IA 接入说明注册 participant 竞争 owner；不要直接投递提问型入口 |
| 让第三方话语进入 AX 上下文但不回答 | 上下文型入口 + 上下文载荷 |
| 让 AX 播报一句自己世界里刚发生的事 | 事件型入口 + 世界事件载荷（§4） |
| 自己成为对话 owner | IA participant 注册 |
| 直接调用模型 | LLM 公共能力，并带本轮对话身份用于关联 |
| 播放或合成语音 | TTS 公共能力 |
| 观察 AX 健康与诊断 | 模块状态 topic，过滤 `module.ax` |
| 观察玩家可感知的 AX 活动 | 活动 topic；思考/回答类型只接受 `module.ax` |

不要新增"AX 聊天请求""AX 模型结果"或公开的记忆 topic 来绕过现有的 owner、响应关联与隐私边界。

## 12. 接入检查表

- [ ] 不直接发送提问型入口。
- [ ] 上下文注入只使用上下文型入口，不伪造 ASR/IR 事件。
- [ ] 空唤醒词时不假定 AX 已注册。
- [ ] 不冒用 `module.ax` / `tianshu.AX` 身份。
- [ ] 模型、语音、会话请求分别遵循对应模块文档。
- [ ] 不发布伪造的事实或活动事件。
- [ ] 不在处理器或游戏主线程执行阻塞任务。
- [ ] 不读取 AX 内部记忆、提示词、超时或在途请求实现。
- [ ] 上下文型投递保证"每段话语只投一次"（AX 不去重）。
- [ ] 事件型投递只用于"说一句世界事件"，`kind` 用自己的命名空间、文案自己渲染；同一次发生只投一次。
## 13. 符号对照表

接入方按符号查语义时用这张表；符号名即代码里的常量全限定名。

### 13.1 装配与运行时

| 符号 | 含义 |
|---|---|
| `TianshuModuleAssemblyContext.moduleRuntime()` | 模块装配时取得运行时访问器的唯一入口 |
| `ModuleRuntimeAccess` | 运行时访问器：提交请求、订阅、登记活动所用 |

### 13.2 AX 自身的能力（别人调用 AX）

| 符号 | 能力名 | 含义 |
|---|---|---|
| `AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY` | `AX.DIALOGUE_INPUT` | 提问型入口：让 AX 就这一轮说话 |
| `AXProtocolAdapter.CONTEXT_INPUT_CAPABILITY` | `AX.CONTEXT_INPUT` | 上下文型入口：把外部话语送进 AX 的历史 |
| `AXProtocolAdapter.EVENT_INPUT_CAPABILITY` | `AX.EVENT_INPUT` | 事件型入口：投递一句世界事件 + 一个行为 |

对应登记值来自 `ProtocolCapabilities`：

| 符号 | 值 |
|---|---|
| `ProtocolCapabilities.AX_DIALOGUE_INPUT` | `AX.DIALOGUE_INPUT` |
| `ProtocolCapabilities.AX_CONTEXT_INPUT` | `AX.CONTEXT_INPUT` |
| `ProtocolCapabilities.AX_EVENT_INPUT` | `AX.EVENT_INPUT` |

### 13.3 入站载荷

| 符号 | 用途 |
|---|---|
| `DialogueDeliveryPayload` | 提问型入口的载荷（会话/轮次/文本/唤醒词/实体/交互提示/上下文快照） |
| `DialogueContextInputPayload` | 上下文型入口的载荷（来源与文本、说话人、轮次标识） |
| `AXWorldEventPayload` | 事件型入口的载荷（命名空间化 kind、行为三选一、已渲染句子） |

### 13.4 AX 调用的下游能力

| 符号 | 值 | 用途 |
|---|---|---|
| `ProtocolCapabilities.LLM_REQUEST` | `LLM.REQUEST` | 一次对话生成 |
| `ProtocolCapabilities.LLM_PRIMITIVE_QUERY` | `LLM.PRIMITIVE_QUERY` | 原语查询（向量/计算类） |
| `ProtocolCapabilities.LLM_CACHE_MANAGE` | `LLM.CACHE_MANAGE` | 缓存管理 |
| `ProtocolCapabilities.PRESENCE_QUERY_CONTEXT` | `PRESENCE.QUERY_CONTEXT` | 向映迹索取上下文事实 |
| `ProtocolCapabilities.TTS_SPEAK` | `TTS_SPEAK` | 让回响说话 |
| `ProtocolCapabilities.TTS_CONTROL` | `TTS_CONTROL` | 播报控制（停止/状态等） |
| `ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER` | `DIALOGUE.PARTICIPANT_REGISTER` | 以参与者身份登记（唤醒词） |
| `ProtocolCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER` | `DIALOGUE.PARTICIPANT_UNREGISTER` | 注销参与者身份 |
| `ProtocolCapabilities.DIALOGUE_SESSION_CONTROL` | `DIALOGUE.SESSION_CONTROL` | 会话控制（结束当前会话等） |

### 13.5 AX 订阅与发布的主题

订阅（AX 听别人；只有下面这两项）：

| 符号 | 值/来源 | 用途 |
|---|---|---|
| `PresenceWorldEventPayload.TOPIC` | `PRESENCE.WORLD_EVENT` | 世界事件（统一渲染为 `<event>`） |
| `PresenceWorldEventPayload.EVENT_CHAT_MESSAGE` | `PresenceWorldEventPayload.eventType` | 公屏聊天：世界事件的一个类型（同样进 `<event>`，带说话人） |

发布（别人听 AX）：

| 符号 | 值/来源 | 用途 |
|---|---|---|
| `ProtocolTopics.MODULE_STATUS` | `MODULE.STATUS` | AX 自身的健康与诊断状态；订阅方按 `module.ax` 过滤 |
| `ProtocolTopics.PRESENCE_ACTIVITY` | `PRESENCE.ACTIVITY` | 思考/回答与记忆维护活动，不含正文 |