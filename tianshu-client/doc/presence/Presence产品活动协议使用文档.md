# Presence 产品活动协议使用文档

## 快速摘要

- `PRESENCE.ACTIVITY` 用于公开玩家能够感知的当前活动，不是模块健康、错误或调试流水线。
- 主状态按 `RESPONDING > THINKING > PROCESSING_TASK > LOADING > IDLE` 选择；`LISTENING` 独立叠加，不改变主状态。
- 外部模块可以发布 `LOADING` 和 `PROCESSING_TASK`；`THINKING`、`RESPONDING` 只接受 `module.ax`，`LISTENING` 只接受 `module.asr`。
- 每个 `STARTED` 都必须使用同一来源、活动 ID 和活动类型发送对应 `ENDED`。TTL 只用于发布方异常退出时的最终兜底。
- 退出世界会清空全部活动；重新进入世界后不会继承旧世界的 HUD 状态。

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
| `activityType` | `LOADING`、`PROCESSING_TASK`、`THINKING`、`RESPONDING` 或 `LISTENING`。 |
| `action` | `STARTED` 或 `ENDED`。 |
| `occurredAtMillis` | 事件发生时间。旧于当前世界会话开始时间的事件会被忽略。 |
| `ttlMillis` | `STARTED` 的失效兜底时间，必须大于零；`ENDED` 固定为零。 |

来源身份取自信封 `EnvelopeHeader.sourceId()`，不放进 payload。映迹按 `(sourceId, activityId, activityType)` 精确记录和结束活动，因此同一个模块可以同时公开多个任务；结束一个任务不会清除其他任务。

## 2. 产品状态语义

| 类型 | 产品语义 | 发布边界 |
| --- | --- | --- |
| `LOADING` | 模型或服务正在真实加载、预热，暂时还不能提供对应能力。 | 任何模块可发布。未配置、未安装或已经失败不属于加载中。 |
| `PROCESSING_TASK` | 正在执行玩家或外部模块可感知的任务，也包括真实运行中的内部维护工作。 | 任何模块可发布。排队但尚未开始的任务不应伪报运行。 |
| `THINKING` | 辅星已收到 IA 投递，正在获取上下文、检索记忆、组装 Prompt 或生成首段可见回复。 | 仅 `module.ax`。 |
| `RESPONDING` | 辅星已经产生首段可见输出，正在继续展示或播报本轮回复。 | 仅 `module.ax`。 |
| `LISTENING` | ASR 在处理后音频中检测到真实说话活动。 | 仅 `module.asr`。它不触发或打断 AX。 |

模块错误、模型缺失、下载失败、队列长度和底层推理阶段不发布为产品活动。这些信息继续通过模块状态、设置页、诊断和日志观察。

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

## 4. 并发与展示

映迹允许多个活动并存，主状态固定按以下顺序选择：

```text
RESPONDING > THINKING > PROCESSING_TASK > LOADING > IDLE
```

同一优先级存在多个活动时，最近开始或刷新的活动成为主来源。`LISTENING` 单独计算：它可以与任意主状态同时为真，HUD 或未来 shader 可以临时突出聆听反馈；聆听结束后恢复仍然有效的主状态，不创建组合枚举。

## 5. 世界和生命周期边界

- 映迹只在活动世界会话中接收产品活动。
- 退出世界立即清空活动表，不等待各发布模块逐个补发结束事件。
- 重新进入世界会建立新的会话起点，时间早于该起点的迟到事件会被忽略。
- 模块停止、任务取消和客户端关闭仍应主动结束自己已经发布的活动，减少 TTL 等待和无效状态。
- 发布与聚合不得执行模型、文件、网络或 Minecraft 活对象读取；HUD 只读取已经聚合好的不可变快照，具体绘制由宿主在渲染帧中完成。

### 5.1 调度与时间边界

| 来源 | 负责内容 | 是否负责 HUD 动画 |
| --- | --- | --- |
| 游戏 tick | 客户端生命周期工作、有限的 Presence 上下文请求处理、语音按键持续状态采样 | 否 |
| Presence 协议事件 | 更新活动表并标记状态快照失效 | 否 |
| 自然时间 | `occurredAtMillis` 的世界会话过滤和活动 TTL 过期判断 | 否 |
| 渲染帧 | 读取缓存的展示快照、采样视觉参数、执行 shader 或 Java fallback 绘制 | 是 |
| 单调渲染时钟 | 使用 `System.nanoTime()` 计算动画经过的时间，不受 20 TPS 限制 | 是 |

TTL 使用墙上时钟是为了让跨模块协议事件可以比较；动画使用单调时钟是为了让暂停、卡顿和系统时间调整不会造成视觉跳变。自然时间过期不启动后台轮询，而是在下一次状态快照读取时按需清理。

## 6. 内置模块约定

| 模块 | 活动 |
| --- | --- |
| ASR | `asr.model.load`、`asr.recognition.<sessionId>`、`asr.speech.<sessionId>` |
| AX | `ax.chat.<sessionId>.<turnId/requestId>`、`ax.memory.maintenance.<worldSafeId>` |
| LLM | `llm.model.load`、`llm.task.<taskId>`；CHAT 不发布辅星产品状态。 |
| TTS | `tts.model.load`、`tts.request.<requestId>`；非 AX 播放和纯合成任务共用请求活动，`module.ax` 不重复覆盖 AX 自己的回复状态。 |

`MODULE.STATUS`、`LLM.STATUS`、`TTS.PLAYBACK` 和 `TTS.REQUEST_STATUS` 仍有各自诊断或业务用途，但映迹不会从这些 topic 推断产品 HUD 状态。
