# 天枢·枢机协议中心 v0.5：通用架构契约

## 1. 核心定位：能力路由与生命周期基础设施

枢机是天枢系统的底层基础设施，不是业务模块。

它不理解雷达、换装、翻译、MR、合成图谱等具体业务，不产生业务数据，不消费业务数据，也不直接执行任何 Minecraft 动作。它的职责是管理统一信封的路由、仲裁资源冲突、托管信封生命周期、处理取消传播、执行超时清理，并提供死信与观测能力。

### 1.1 五项基本原则

1. **枢机不理解业务，但理解协议。**  
   枢机不知道业务含义，但理解 `Capability`、`Priority`、`ThreadPolicy`、`DeliveryPolicy`、`PacketType`、`PayloadType` 等基础设施语义。

2. **枢机不执行动作，但托管动作请求的生命周期。**  
   枢机不修改背包、不渲染 UI、不调用 LLM SDK、不直接播放 TTS。它只管理请求从创建、排队、分发、运行到完成、失败、取消、过期的状态流转。

3. **枢机不拆载荷，但校验载荷类型。**  
   Payload 对枢机是类型化黑盒。枢机不读取 Payload 内部业务字段，但会依据 Header 中的 `PayloadType` 与注册表声明进行安全分发、日志记录和死信判定。

4. **枢机不关心模块内部，但强制模块注册能力。**  
   模块必须通过 `ModuleDescriptor` 与 `CapabilityDescriptor` 声明自己能处理的能力、Payload 类型、线程策略、Broker 类型和默认生命周期策略。未注册能力不可通信。

5. **枢机不编排业务流程，但保证信封链路可追踪、可取消、可过期、可降级。**  
   ASR → IR → LLM 修复 → 二次 IR 等业务流程不能硬编码在枢机本体中，必须抽离到独立流程模块或由模块自身通过信封派生完成。

## 2. 统一信封契约

所有跨模块交互必须封装为 `TianshuEnvelope`。

信封分为：

- **Header**：枢机可读可写的协议层信息。
- **Payload**：类型化黑盒业务载荷。

### 2.1 信头规范

```java
class EnvelopeHeader {
    String envelopeId;
    String traceId;
    String parentId;
    String sourceId;

    TargetMode targetMode;
    String target;
    DeliveryPolicy deliveryPolicy;

    PacketType packetType;
    PayloadType payloadType;
    AckPolicy ackPolicy;

    Priority priority;
    ThreadPolicy threadPolicy;

    long createdAt;
    long deadline;
    long expireAt;

    CancellationScope cancellationScope;
    FailurePolicy failurePolicy;
}
```

### 2.2 字段语义

| 字段 | 含义 |
|---|---|
| `envelopeId` | 全局唯一信封 ID，用于精准取消、防重复消费和日志追踪。 |
| `traceId` | 链路追踪 ID。同一波业务链路中的请求、响应、流式块共享同一个 `traceId`。 |
| `parentId` | 父信封 ID，用于表达派生关系。 |
| `sourceId` | 发信模块或输入适配器 ID，用于溯源、防回环、权限审计与日志问责。 |
| `targetMode` | 投递模式：`DIRECT`、`CAPABILITY`、`TOPIC`。 |
| `target` | 根据投递模式填写 ModuleID、CapabilityID 或 TopicID。 |
| `deliveryPolicy` | 投递策略：`FIRE_AND_FORGET`、`WAIT_IN_QUEUE`、`LATEST_ONLY`、`COALESCE`。 |
| `packetType` | 信件语义：`EVENT`、`COMMAND`、`REQUEST`、`RESPONSE`、`STREAM_START`、`STREAM_CHUNK`、`STREAM_END`、`CANCEL`、`STATUS`、`ERROR`、`HEARTBEAT`、`PROGRESS`。 |
| `payloadType` | Payload 类型枚举。属于 Header，不属于 Payload 内部。 |
| `ackPolicy` | 确认策略：`NONE`、`EXPECT_SUCCESS_OR_FAILURE`。 |
| `priority` | 优先级：`CRITICAL`、`HIGH`、`NORMAL`、`LOW`、`BACKGROUND`。 |
| `threadPolicy` | Handler 执行线程约束：`MUST_MAIN`、`ASYNC_WORKER`、`IO_BLOCKING`、`ANY`。 |
| `createdAt` | 创建时间戳。 |
| `deadline` | 期望完成时间。超过后不一定销毁，由 Broker 和 FailurePolicy 决定降级、提示、取消或继续等待。 |
| `expireAt` | 绝对销毁时间。超过后必须进入 `EXPIRED` 并清理。 |
| `cancellationScope` | 主动取消或抢占时的影响范围：`SELF_ONLY`、`CHILDREN`、`TRACE`、`RESOURCE`。 |
| `failurePolicy` | 失败、过期或异常后的处理策略：`PROPAGATE_CANCEL`、`FALLBACK`、`IGNORE`、`RETRY`、`REPORT_ONLY`。 |

### 2.3 Header 默认值与构建器

普通模块不应手动填写完整 Header，而应通过 `EnvelopeBuilder` 或枢机 API 构建信封。

模块只需提供核心意图：

- `sourceId`
- `targetMode`
- `target`
- `packetType`
- `payloadType`
- `priority`
- `payload`

其余字段由枢机根据注册表和默认策略补齐：

- `envelopeId`
- `traceId`
- `parentId`
- `createdAt`
- `deadline`
- `expireAt`
- `deliveryPolicy`
- `threadPolicy`
- `cancellationScope`
- `failurePolicy`

### 2.4 载荷规范

所有 Payload 必须实现 `ITianshuPayload`。

Payload 规则：

1. **类型安全**：模块声明处理具体 Payload 类，而不是只处理泛化接口。
2. **绝对不可变**：Payload 必须是不可变对象，字段不可变，无 Setter。推荐使用 Java `record` 或等价不可变结构。
3. **禁止活对象**：Payload 内禁止包含 `Entity`、`ItemStack`、`Level`、`Player`、`Screen`、`PoseStack` 等 Minecraft 原生活对象。
4. **必须快照化**：跨线程数据必须在构建信封前拷贝为不可变快照。

示例：

```java
record PositionSnapshot(double x, double y, double z) {}

record EntitySnapshot(
    String id,
    String type,
    PositionSnapshot position,
    double health
) implements ITianshuPayload {}
```

禁止示例：

```java
record BadPayload(Entity entity, ItemStack stack) implements ITianshuPayload {}
```

## 3. 能力注册表

没有注册表，Capability 路由没有意义。

模块启动时必须向枢机注册 `ModuleDescriptor`。

```java
class ModuleDescriptor {
    String moduleId;
    List<CapabilityDescriptor> capabilities;

    ThreadPolicy defaultThreadPolicy;
    CancellationScope defaultCancellationScope;
    FailurePolicy defaultFailurePolicy;
    DeliveryPolicy defaultDeliveryPolicy;

    boolean cancellable;
    boolean supportsStreaming;

    int maxConcurrency;
    int queueCapacity;
}
```

```java
class CapabilityDescriptor {
    String capabilityId;
    PayloadType supportedPayloadType;
    Class<? extends ITianshuPayload> payloadClass;

    BrokerType requiredBrokerType;

    Set<PacketType> acceptedPacketTypes;
    Priority minAcceptedPriority;
}
```

枢机只向已注册且 `PayloadType` 严格匹配的 Handler 投递信封。否则信封进入死信队列。

### 3.1 注册原则

- 一个业务模块可以注册多个 Capability。
- 一个 Capability 可以由一个或多个 Handler 实现。
- 多个 Handler 同时注册同一 Capability 时，由对应 Broker 根据策略选择、排队、拒绝、合并或打断。
- 业务模块禁止依赖其他业务模块实现类，但可以依赖 `tianshu-common` 中的协议接口、Payload 定义、Snapshot 定义与 Capability 常量。

## 4. 投递模型

### 4.1 DIRECT

精确投递给指定 ModuleID。

DIRECT 应慎用。普通业务模块禁止使用 DIRECT 硬编码目标模块 ID。DIRECT 仅允许用于：

- RESPONSE 返回；
- CANCEL / STATUS 等生命周期控制；
- 枢机内部管理信封；
- 明确授权的模块私有通道。

### 4.2 CAPABILITY

投递给注册了某个 Capability 的模块。

推荐普通业务模块优先使用 CAPABILITY，例如：

- `TTS_SPEAK`
- `TTS_ALERT`
- `LLM_REPAIR`
- `LLM_CHAT`
- `IR_PARSE`
- `UI_TOAST`
- `SERVER_ACTION`

### 4.3 TOPIC

发布主题事件，所有订阅该 Topic 的模块都会收到。

TOPIC 必须注册，且高频 TOPIC 必须绑定限流策略：

- `LATEST_ONLY`
- `COALESCE`
- `StormGuard`

模块不得发布未注册 Topic，不得无条件收到某 Topic 后继续发布同一 Topic，避免回环风暴。

## 5. 生命周期与精准熔断

信封在枢机内部经历状态机：

```text
CREATED
ACCEPTED
QUEUED
DISPATCHED
RUNNING
COMPLETED
CANCELLED
EXPIRED
FAILED
REJECTED
DEAD_LETTERED
```

推荐主流程：

```text
CREATED -> ACCEPTED -> QUEUED -> DISPATCHED -> RUNNING -> COMPLETED
```

终态包括：

- `COMPLETED`
- `CANCELLED`
- `EXPIRED`
- `FAILED`
- `REJECTED`
- `DEAD_LETTERED`

### 5.1 精准熔断

v0.3 中“一挂全杀”的策略废除。

当信封被主动取消或被资源抢占时，枢机读取 `CancellationScope`：

| Scope | 行为 |
|---|---|
| `SELF_ONLY` | 只取消当前信封。 |
| `CHILDREN` | 取消当前信封及其子链。 |
| `TRACE` | 取消整个 Trace。 |
| `RESOURCE` | 取消同一资源上低优先级任务。 |

当信封失败、过期或异常时，枢机读取 `FailurePolicy`：

| Policy | 行为 |
|---|---|
| `PROPAGATE_CANCEL` | 按 `CancellationScope` 传播取消。 |
| `FALLBACK` | 允许模块派生降级信封，继承原 `traceId`，设置新 `parentId`。 |
| `IGNORE` | 只清理当前信封，不影响 Trace。 |
| `RETRY` | 在 Broker 允许的条件下重试。 |
| `REPORT_ONLY` | 上报错误状态，不取消链路。 |

失败不自动读取 `CancellationScope`，除非 `FailurePolicy = PROPAGATE_CANCEL`。

### 5.2 取消传播责任划分

模块职责：

- 收到针对自身 `envelopeId` 的 `CANCEL` 信封时，停止本地业务。
- 返回 `STATUS(CANCELLED)`。
- 不遍历子任务，不直接取消其他模块。

枢机职责：

- 维护 `Trace -> List<Envelope>` 索引。
- 维护 `Parent -> List<Child>` 索引。
- 根据 `CancellationScope` 执行递归取消。
- 清理终态信封。
- 生成必要 STATUS、ERROR 或 DeadLetter 记录。

### 5.3 Trace 清理策略

为防止内存泄漏，枢机必须定期清理 Trace 索引。

清理条件：

- Trace 下所有信封进入终态；
- Trace 超过全局最大存活时间；
- Trace 被显式取消；
- Debug 保留窗口之外的历史 Trace。

枢机只保留最近 N 条 Trace 的轻量日志快照。

## 6. Broker 调度与资源仲裁

枢机内部按 Capability 挂载不同 Broker。Broker 是资源控制器，不只是队列。

Broker 职责：

1. 接收通过基础校验的信封。
2. 决定排队、拒绝、合并、替换或打断。
3. 决定何时投递给 Handler。
4. 跟踪 RUNNING 任务。
5. 接收完成、失败、取消状态并释放资源。
6. 触发必要的取消信封或死信记录。

### 6.1 Broker 类型

| Broker | 适合能力 |
|---|---|
| `ExclusiveInterruptBroker` | TTS、音频播放等独占资源。 |
| `ParallelLimitBroker` | LLM、RAG、网络 IO 等有限并发资源。 |
| `LatestOnlyBroker` | Hover、准星、Tick 状态等高频最新态。 |
| `BoundedQueueBroker` | UI Toast、普通反馈、短队列输出。 |
| `StatelessFastPathBroker` | IR、本地规则解析等无状态快速能力。 |
| `MainThreadBroker` | 客户端 UI 更新、主线程快照读取。 |
| `ServerPacketBroker` | 服务端授权动作、C2S 意图包、高危真实状态变更。 |

### 6.2 TTS 仲裁示例

`TTS_ALERT` 使用 `ExclusiveInterruptBroker`。

- `CRITICAL` 请求到来时，向当前 RUNNING 的低优先级 TTS 信封下发 `CANCEL`。
- 当前 TTS 进入 `CANCELLED` 后，新信封插队执行。
- 普通 `NORMAL` 播报可以短队列排队。
- `LOW` 与 `BACKGROUND` 播报在队列拥堵时可丢弃或合并。

### 6.3 LLM 仲裁示例

`LLM_REPAIR` / `LLM_CHAT` 使用 `ParallelLimitBroker`。

- 限制最大并发。
- `COMMAND_REPAIR` 优先于普通聊天。
- 高优先级任务可插队。
- 低优先级背景分析在拥堵时可被拒绝或延后。
- 必须支持 CANCEL 与超时清理。

### 6.4 高频事件仲裁示例

准星、Hover、Tick 状态使用 `LatestOnlyBroker`。

- 队列永远只保留最新状态。
- 同一 SourceId 的高频重复信封会被合并。
- 超过 StormGuard 阈值时进入拒绝或降级。

## 7. 线程安全与 Minecraft 边界红线

### 7.1 ThreadPolicy 语义

`ThreadPolicy` 声明目标 Handler 的执行线程约束：

| Policy | 含义 |
|---|---|
| `MUST_MAIN` | 必须在 Minecraft 客户端主线程执行。 |
| `ASYNC_WORKER` | 可在异步工作线程执行。 |
| `IO_BLOCKING` | 适用于网络、文件、模型调用等阻塞 IO。 |
| `ANY` | 当前线程可执行，目标 Handler 自行保证安全。 |

### 7.2 入站快照化

任何从 Minecraft 主线程产生、但后续进入异步线程的信封，必须在构建时完成数据快照化。

禁止传递：

- `Entity`
- `ItemStack`
- `Level`
- `Player`
- `BlockState` 活对象
- `Screen`
- `PoseStack`

允许传递：

- `EntitySnapshot`
- `ItemSnapshot`
- `BlockSnapshot`
- `InventorySnapshot`
- `PositionSnapshot`

### 7.3 出站主线程回流

声明 `MUST_MAIN` 的信封，枢机会将 Handler 调用压入主线程队列，通过 `Minecraft.getInstance().execute()` 执行。

但 `MUST_MAIN` 只代表允许在客户端主线程操作 UI 或读取本地状态，不代表允许直接修改服务端真实状态。

涉及真实状态变更必须进入 `ServerPacketBroker`。

## 8. ServerPacketBroker 安全边界

`ServerPacketBroker` 是高危能力边界。

适用场景：

- 换装；
- 清理背包；
- 丢弃物品；
- 领取任务奖励；
- 发送聊天；
- 任何涉及真实服务端状态的动作。

规则：

1. 只有注册为 `SERVER_ACTION` 的 Capability 才能进入。
2. Payload 必须表达 Intent，而不是绕过服务端校验的具体作弊动作。
3. 必须检查当前服务器授权状态。
4. 未授权时返回 `STATUS(PERMISSION_DENIED)`，不得发包。
5. 原版服务器绝不发送未知自定义包。
6. 所有 C2S 请求必须可审计、可降级、可拒绝。

## 9. 状态与基础设施 Payload

以下 Payload 属于枢机基础设施载荷，不属于具体业务 Payload。

```java
record StatusPayload(
    String targetEnvelopeId,
    EnvelopeStatus status,
    String reasonCode,
    String message
) implements ITianshuPayload {}
```

```java
record ErrorPayload(
    String targetEnvelopeId,
    String errorCode,
    String message,
    boolean retryable
) implements ITianshuPayload {}
```

```java
record HeartbeatPayload(
    String targetEnvelopeId,
    long timestamp,
    String stage
) implements ITianshuPayload {}
```

```java
record ProgressPayload(
    String targetEnvelopeId,
    double progress,
    String stage
) implements ITianshuPayload {}
```

```java
record CancelPayload(
    String targetEnvelopeId,
    String reasonCode,
    String message
) implements ITianshuPayload {}
```

## 10. 风暴防护

### 10.1 回环检测

枢机记录 Trace 派生深度。超过阈值时拒绝投递并生成死信记录。

但派生深度不是唯一判断标准。StormGuard 还应检查：

- 同一 SourceId 单位时间投递量；
- 同一 Topic 回环发布次数；
- 同一 PayloadType 连续重复次数；
- 同一 Trace 单位时间派生数量；
- STREAM_CHUNK 分片频率。

### 10.2 高频事件规则

高频 Topic 必须绑定：

- `LATEST_ONLY`
- `COALESCE`
- `StormGuard`

Tick、Hover、准星类事件不得无控制地向枢机投递。

## 11. 死信队列

触犯以下规则的信封进入死信：

- Target 找不到对应注册 Handler；
- Capability 未注册；
- PayloadType 与 Handler 声明不匹配；
- 投递时已超过 `expireAt`；
- Broker 拒绝且无法降级；
- 模块处理抛出未捕获异常；
- 重复 `envelopeId`；
- 回环或风暴防护触发。

DeadLetterPolicy：

- `LOG_ONLY`
- `NOTIFY_SOURCE`
- `RAISE_ERROR_ENVELOPE`

死信不得静默吞掉。

死信日志默认不得输出完整 Payload，只记录：

- `envelopeId`
- `traceId`
- `sourceId`
- `target`
- `targetMode`
- `payloadType`
- `packetType`
- 错误码
- 简短原因

完整 Payload 内容只能在显式 Debug 模式下脱敏输出。

## 12. 观测体系

枢机必须暴露内部快照接口供 Debug 使用。

至少包括：

- 当前各 Broker 队列长度；
- 当前 RUNNING 信封列表；
- 指定 TraceId 的信封树；
- 信封状态流转日志；
- 最近 N 条死信记录；
- StormGuard 拒绝统计；
- 当前注册的 Module / Capability / Topic 列表。

未来可扩展为 Debug Overlay 或开发者命令。

## 13. 模块接入宪法

1. **不知彼此**  
   业务模块禁止 import 其他业务模块实现类。允许依赖 `tianshu-common` 中的协议接口、Payload、Snapshot 与 Capability 常量。

2. **只走枢机**  
   跨模块通信的唯一合法途径是向枢机投递信封。

3. **慎用直投**  
   普通业务模块禁止使用 `TargetMode.DIRECT` 硬编码目标模块 ID，必须优先使用 `CAPABILITY` 或 `TOPIC`。

4. **不抢夺资源**  
   禁止绕过枢机直接调用底层 TTS 引擎、LLM SDK、服务端动作接口或其他模块能力。

5. **防挂起保底**  
   耗时任务必须设置合理 `deadline` / `expireAt`，或在处理期间定期发送 `HEARTBEAT` / `PROGRESS`。否则枢机会将其标记为 `EXPIRED` 或 `FAILED`，并生成死信记录。

6. **不携带活对象**  
   所有跨线程、跨模块载荷必须是不可变快照。

## 14. 推荐落地阶段

### 阶段一：最小可用枢机

实现：

- `TianshuEnvelope`
- `EnvelopeHeader`
- `ITianshuPayload`
- `ModuleRegistry`
- `CapabilityRegistry`
- 基础 DeadLetter 日志
- `StatelessFastPathBroker`
- `BoundedQueueBroker`
- `ExclusiveInterruptBroker`

先接入：

- 【听澜】ASR
- 【识意】IR
- 【思衡】LLM
- 【回响】TTS

目标：拆掉当前 ASR → LLM → TTS 的硬链路。

### 阶段二：生命周期与取消

实现：

- RUNNING 状态追踪
- CANCEL
- `CancellationScope`
- `FailurePolicy`
- `deadline` / `expireAt`
- HEARTBEAT
- Trace / Parent 索引树

目标：支持 LLM 流式取消、TTS 打断、高危警报抢占普通反馈。

### 阶段三：高级防护与观测

实现：

- `StormGuard`
- `DeadLetterPolicy`
- Broker 队列快照
- Trace Debug 日志
- `TopicRegistry`
- `LatestOnlyBroker`
- `ParallelLimitBroker`
- `ServerPacketBroker`

再接入：

- 【玄哨】战术雷达
- 【余烬】熔断预警
- 【天镜】MR
- 【双鉴】伴生卡片
- 【行策】任务系统
- 【通语】聊天室
- 【净囊】垃圾清理