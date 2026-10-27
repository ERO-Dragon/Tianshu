# Presence 产品活动协议使用文档

## 快速摘要

- `PRESENCE.ACTIVITY` 用于公开玩家能够感知的当前状态：主状态（思考、回复、加载、错误、重连）和两个并行标记（聆听、后台任务）走同一条通道。
- 主状态按 `RESPONDING > THINKING > ERROR > RECONNECTING > LOADING > IDLE` 选择；`LISTENING` 和 `PROCESSING_TASK`（后台任务）是**并行标记**，可以和任意主状态同时存在，不参与排序。
- 主状态的所有者声明在 `PresenceActivityType` 上（`publisherModuleId`）：`THINKING` / `RESPONDING` 仅 `module.ax`，`LISTENING` 由 `module.asr` 开始；`ERROR`、`RECONNECTING`、`LOADING`、`PROCESSING_TASK` 任何模块可发布。映迹不硬编码任何模块名。
- 每个 `STARTED` 都必须在条件结束、任务终态或模块停止时发送对应 `ENDED`；TTL 只是发布方异常退出时的兜底。`LISTENING` 是回合级活动，由解析这一轮的模块（仲裁方）结束。错误/重连恢复后必须显式撤回，不能等 TTL。
- 退出世界会清空全部活动；重新进入世界后不会继承旧世界的 HUD 状态。
- 上下文查询在请求被回答的那一刻采集：没有定时扫描，也不靠输入事件保温；仲裁用的交互上下文摘要另走变化推送（`PRESENCE.INTERACTION_CONTEXT`），两者不共享缓存。

Presence 活动快照不是“每 tick 快照”。活动聚合只在协议活动事件、世界会话边界或自然时间 TTL 过期后的下一次快照读取时发生；活动未变化且未到期时，读取返回同一个不可变快照对象。渲染帧只消费这个快照，不重新扫描活动表或重新判断产品状态。

## 1. 稳定契约

| 项目 | 值 |
| --- | --- |
| Topic | `ProtocolTopics.PRESENCE_ACTIVITY` (`PRESENCE.ACTIVITY`) |
| PayloadType | `PayloadType.PRESENCE_ACTIVITY` |
| Payload | `PresenceActivityPayload` |
| PacketType | `EVENT` |
| DeliveryPolicy | `WAIT_IN_QUEUE` |

`PresenceActivityPayload` 字段：

| 字段 | 语义 |
| --- | --- |
| `activityId` | 发布来源内稳定的活动身份。一次活动从开始到结束必须保持不变。 |
| `activityType` | `LOADING`、`PROCESSING_TASK`、`THINKING`、`RESPONDING`、`LISTENING`、`RECONNECTING` 或 `ERROR`。 |
| `action` | `STARTED` 或 `ENDED`。 |
| `occurredAtMillis` | 事件发生时间。旧于当前世界会话开始时间的事件会被忽略。 |
| `ttlMillis` | `STARTED` 的失效兜底时间，必须大于零；`ENDED` 固定为零。 |

来源身份取自信封 `EnvelopeHeader.sourceId()`，不放进 payload。映迹按 `(sourceId, activityId, activityType)` 精确记录和结束活动，因此同一个模块可以同时公开多个任务；结束一个任务不会清除其他任务。唯一的例外是回合级活动（`LISTENING`）：它按 `(activityId, activityType)` 结束，开始仍只允许所有者，结束可以由解析这一轮的模块发出。

## 2. 产品状态语义

| 类型 | 产品语义 | 发布边界 |
| --- | --- | --- |
| `LOADING` | 模型或服务正在真实加载、预热，暂时还不能提供对应能力。 | 任何模块可发布。未配置、未安装或已经失败不属于加载中。平台在加载期间不派发模块按键（按键门读的是同一个产品状态），因此"正在加载"和"按键不可用"永远一致。 |
| `THINKING` | 辅星已收到 IA 投递，正在获取上下文、检索记忆、组装 Prompt 或生成首段可见回复。 | 仅 `module.ax`。起点是 IA 决定这一轮交给辅星，不是按下说话键。 |
| `RESPONDING` | 辅星已经产生首段可见输出，正在继续展示或播报本轮回复。 | 仅 `module.ax`。 |
| `ERROR` | 某条能力已确定不可用，需要玩家处理（配置、凭据、模型文件）。 | 任何模块可发布。进入前先结束自己正在跑的阶段活动，否则会被更高优先级的对话状态盖住。 |
| `RECONNECTING` | 某条能力通道启动失败后正在按退避重连，通道恢复会自动结束。 | 任何模块可发布。恢复或停止时必须发 `ENDED`。 |
| `LISTENING` | 玩家这一轮语音正在处理：按下说话键开始，直到仲裁给出结果。 | 由 `module.asr` 开始；由解析这一轮的模块（思衡）结束。它不触发或打断 AX。 |
| `PROCESSING_TASK` | 后台任务正在执行，包括内部维护工作。 | 任何模块可发布。它是**并行标记**，不参与主状态排序，表示"现在还在忙"。 |

这一层只表达"这条能力现在能不能用、是不是在跑"；错误码、堆栈、排队长度、模型内部阶段和下载进度仍然不进入产品状态，继续通过模块状态、设置页、诊断和日志观察。纯性能细节（例如向量模型退回 CPU 但检索仍可用）同样不属于产品状态。

## 3. 发布规则

活动开始时发布：

```java
public TianshuEnvelope publishTaskStarted(String taskId) {
    return publishTopic(
            ProtocolTopics.PRESENCE_ACTIVITY,
            PayloadType.PRESENCE_ACTIVITY,
            PresenceActivityPayload.started(
                    "example.task." + taskId,
                    PresenceActivityType.PROCESSING_TASK,
                    120_000L
            )
    );
}
```

任务进入成功、失败或取消终态时都必须发布匹配的结束事件：

```java
public TianshuEnvelope publishTaskEnded(String taskId) {
    return publishTopic(
            ProtocolTopics.PRESENCE_ACTIVITY,
            PayloadType.PRESENCE_ACTIVITY,
            PresenceActivityPayload.ended(
                    "example.task." + taskId,
                    PresenceActivityType.PROCESSING_TASK
            )
    );
}
```

发布方法应位于模块自己的 `AbstractProtocolAdapter` 子类中。调用方应在任务真正开始后发送 `STARTED`，并在统一的终态或 `finally` 清理路径发送 `ENDED`；不能依赖 TTL 代替正常结束。活动 ID 应表达稳定身份，不应包含显示文本、随机本地化内容或会泄露玩家输入的正文。

运行时状况（`RECONNECTING` / `ERROR` / `DEGRADED`）同样遵守一对一规则：条件成立时发 `STARTED`，条件解除（重连成功、重新加载成功、模块停止、世界退出）时发 `ENDED`。TTL 只是进程崩溃时的兜底——靠 TTL 过期来“结束”一条已经恢复的通知，会让 HUD 继续报告一个不存在的故障。

## 4. 并发与展示

映迹允许多个活动并存，主状态固定按以下顺序选择：

```text
RESPONDING > THINKING > ERROR > RECONNECTING > LOADING > IDLE
```

同一优先级存在多个活动时，最近开始或刷新的活动成为主来源。`LISTENING` 与 `PROCESSING_TASK` 单独计算：它们可以和任意主状态同时为真，HUD 在主状态旁边画一个标记（聆听波纹 / 后台工作小点），不创建组合枚举；标记消失后恢复主状态。

排序按玩家注意力分层：前台对话（`RESPONDING` / `THINKING`）永远在上；没有对话在跑时，需要玩家处理的失败（`ERROR`）压过自恢复的重连和加载；最后是待机。失败不打断正在进行的对话——一个模块出错时先结束自己的阶段活动，错误才会露出来；正在播报的回复不会被别的模块的故障盖掉。

后台任务（`PROCESSING_TASK`）不进这条排序：它只是"还在忙"的标记，无论主状态是什么都能同时显示。这也是它和加载的区别——加载会阻止能力使用，后台任务不会。

## 5. 世界和生命周期边界

- 映迹只在活动世界会话中接收产品活动。
- 退出世界立即清空活动表，不等待各发布模块逐个补发结束事件。
- 重新进入世界会建立新的会话起点，时间早于该起点的迟到事件会被忽略。
- 模块停止、任务取消和客户端关闭仍应主动结束自己已经发布的活动，减少 TTL 等待和无效状态。
- 发布与聚合不得执行模型、文件、网络或 Minecraft 活对象读取；HUD 只读取已经聚合好的不可变快照，具体绘制由宿主在渲染帧中完成。

### 5.1 调度与时间边界

| 来源 | 负责内容 | 是否负责 HUD 动画 |
| --- | --- | --- |
| 游戏 tick | 客户端生命周期工作、有限的 Presence 上下文请求处理（每 tick 最多 8 个查询、一次平台采集） | 否 |
| Presence 协议事件 | 更新活动表并标记状态快照失效 | 否 |
| 自然时间 | `occurredAtMillis` 的世界会话过滤和活动 TTL 过期判断 | 否 |
| 渲染帧 | 读取缓存的展示快照、采样视觉参数、执行 shader 或 Java fallback 绘制 | 是 |
| 单调渲染时钟 | 使用 `System.nanoTime()` 计算动画经过的时间，不受 20 TPS 限制 | 是 |

TTL 使用墙上时钟是为了让跨模块协议事件可以比较；动画使用单调时钟是为了让暂停、卡顿和系统时间调整不会造成视觉跳变。自然时间过期不启动后台轮询，而是在下一次状态快照读取时按需清理。

## 6. 内置模块约定

| 模块 | 活动 |
| --- | --- |
| ASR | `asr.model.load`、`asr.recognition.<sessionId>`、`asr.speech.<sessionId>`（按下说话键开始，由思衡在仲裁出结果时结束） |
| AX | `ax.chat.<sessionId>.<turnId/requestId>`、`ax.memory.maintenance.<worldSafeId>` |
| LLM | `llm.model.load`（云端通道启动期间）、`llm.runtime.reconnecting`、`llm.runtime.error`；三者都在就绪、恢复或停止时撤回。`llm.task.<taskId>` 的能力已实现但当前没有发布方，先按未接线看待 |
| TTS | `tts.model.load`、`tts.request.<requestId>`；非 AX 播放和纯合成任务共用请求活动，`module.ax` 不重复覆盖 AX 自己的回复状态 |

`MODULE.STATUS`、`LLM.STATUS`、`TTS.PLAYBACK` 和 `TTS.REQUEST_STATUS` 仍有各自诊断或业务用途，但映迹不会从这些 topic 推断产品 HUD 状态。
