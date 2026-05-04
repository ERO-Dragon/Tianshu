# 天枢协议适配器使用说明：每个模块家里的写信人

这份文档放在协议中心文档旁边，是给各个模块接入协议中心时看的。

先记住一个比喻：

- **协议中心**：整个天枢的邮局。它负责收信、查地址、排队、分拣、投递、打回死信、记录信件状态。
- **模块**：住在不同房子里的人。比如卡片模块、TTS 模块、LLM 模块、雷达模块。
- **适配器**：每个模块家里雇的写信人、收信秘书、跑腿管家。它不是邮局，它只是帮自己家模块把话写成邮局认识的标准信件，再交给邮局。
- **信封**：`TianshuEnvelope`。所有跨模块交流都必须装进信封。
- **信头**：信封上的收件方式、收件目标、寄件人、优先级、过期时间等。协议中心会读这些。
- **信件内容**：Payload。协议中心只检查内容类型对不对，不拆开理解业务意思。

适配器的目标不是决定业务流程，也不是替你想“这个模块该调用谁”。适配器只保证：模块要发信时，信能被稳定地写成标准格式；模块要收信时，能稳定地向协议中心登记自己的收信方式。

## 1. 为什么需要适配器

不用适配器时，每个模块都要自己手写信封：填来源、目标、信件类型、内容类型、优先级、线程策略、超时、取消范围、失败策略。字段很多，容易漏，也容易写错。

适配器的作用就是把这些重复动作收起来。

模块里通常只需要关心三件事：

1. 我要寄给哪个能力或主题。
2. 我要寄什么类型的内容。
3. 我要寄的具体内容是什么。

其他常规字段由适配器自动补齐。

## 2. 适配器在哪里

核心适配器基类在：

`tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/adapter/AbstractProtocolAdapter.java`

默认参数在：

`tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/adapter/AdapterDefaults.java`

已经有一个真实模块示例：

`tianshu-common/src/main/java/com/rheinmetal/tianshu/function/GeminiCard/GeminiCardProtocolAdapter.java`

## 3. 一个模块怎么拥有自己的写信人

每个模块可以写一个自己的适配器类，继承 `AbstractProtocolAdapter`。

示意：

```java
public final class MyModuleProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "client.my_module";
    public static final String SOURCE_ID = "client.my_module";

    public MyModuleProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }
}
```

这里可以理解为：

- `MODULE_ID`：这户人家的登记名字。
- `SOURCE_ID`：这户人家寄信时写在信封上的寄件人名字。
- `runtime`：协议中心这个邮局本体。
- `AdapterDefaults.standard()`：默认寄信习惯，比如普通优先级、异步处理、允许排队、默认过期时间。

一般情况下，`MODULE_ID` 和 `SOURCE_ID` 可以先写成一样。以后如果一个模块里面有多个输入来源，再拆细。

## 4. 寄信方式一：寄给某个能力

这是最推荐的方式。

你不要关心“哪一个模块”会处理这封信，只写“我要找会做某种事的人”。协议中心会查登记表，然后把信投给能处理这个能力的模块。

比如：我要让能播报语音的模块说一句话。

```java
public TianshuEnvelope speak(String text) {
    return commandCapability(
        ProtocolCapabilities.TTS_SPEAK,
        PayloadType.TTS_TEXT,
        new TextPayload(text)
    );
}
```

这就像你对写信人说：

“帮我写封信，寄给会 TTS_SPEAK 的人，内容是一段文字。”

你不需要知道最后是哪个 TTS 类处理，也不需要直接 import TTS 模块实现类。

### commandCapability 和 requestCapability 的区别

`commandCapability(...)` 更像“请你做这件事”。

例如：播放语音、显示界面、发一个动作意图。

`requestCapability(...)` 更像“请你处理后给我一个结果或状态”。

例如：解析文本、向 LLM 请求回答、查询某种分析结果。

现在很多业务细节还没定，你可以先用更直接的 `commandCapability(...)` 或底层的 `submitToCapability(...)` 硬发信，不需要提前把模块调用链想死。

## 5. 寄信方式二：发到主题

主题像小区公告栏。你把信贴到一个主题上，所有订阅这个主题的人都可能收到。

比如：卡片模块发布“鼠标悬停的物品稳定了”。

```java
public TianshuEnvelope publishHoverStable(GeminiCardHoverPayload payload) {
    return publishTopic(
        ProtocolTopics.ITEM_HOVER_STABLE,
        PayloadType.SNAPSHOT,
        payload,
        AdapterDefaults.highFrequencyFact()
    );
}
```

这就像写信人把消息贴到公告栏：

“ITEM_HOVER_STABLE 这个主题有新消息。”

注意：主题必须先在协议中心注册。未注册主题会被协议中心打入死信。

高频主题，比如 hover、tick、准星状态，应该使用 `AdapterDefaults.highFrequencyFact()`，这样默认更短寿、更低优先级、更适合最新状态。

## 6. 寄信方式三：直投

适配器现在支持 `submitDirect(...)`。

直投像直接写某户人家的门牌号。它很硬、很直接，但不推荐普通业务长期依赖。

适合场景：

- 响应上一封信。
- 取消某一封正在处理的信。
- 临时硬发信，业务结构还没完全想好时先跑通链路。
- 确定是私有通道，不希望走公开能力或主题。

示意：

```java
public TianshuEnvelope sendDirect(String routeId, TextPayload payload) {
    return submitDirect(
        routeId,
        PacketType.COMMAND,
        PayloadType.TEXT,
        payload
    );
}
```

如果以后业务关系清楚了，建议逐步把直投改成能力投递或主题投递。

## 7. 收信：登记自己能处理什么

模块如果想收某种能力的信，需要登记能力。

比如：我这个模块能处理 `GEMINI_CARD_SHOW`。

```java
public void registerShowCapability(EnvelopeHandler handler) {
    registerCapability(
        ProtocolCapabilities.GEMINI_CARD_SHOW,
        PayloadType.NONE,
        EmptyPayload.class,
        BrokerType.MAIN_THREAD,
        EnumSet.of(PacketType.COMMAND),
        Priority.LOW,
        CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
        handler,
        AdapterDefaults.mainThreadUi()
    );
}
```

可以理解为这户人家告诉邮局：

“以后如果有寄给 GEMINI_CARD_SHOW 这个能力的信，内容类型是 NONE，信件类型是 COMMAND，最低 LOW 优先级，我可以收。并且我需要在主线程处理。”

处理函数大概长这样：

```java
adapter.registerShowCapability((envelope, context) -> {
    // 这里写模块自己的处理逻辑
});
```

注意：模块只处理自己家里的事。不要在这里直接 import 别的业务模块实现类。

## 8. 收主题：订阅公告栏

如果模块想收到某个主题的消息，需要订阅主题。

已有示例：

```java
public void subscribeAnalysisReady(EnvelopeHandler handler) {
    subscribeTopic(
        ProtocolTopics.ITEM_ANALYSIS_READY,
        PayloadType.CUSTOM,
        GeminiCardAnalysisResultPayload.class,
        BrokerType.MAIN_THREAD,
        EnumSet.of(PacketType.EVENT),
        Priority.LOW,
        CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
        handler,
        AdapterDefaults.mainThreadUi()
    );
}
```

这就像这户人家告诉邮局：

“ITEM_ANALYSIS_READY 这个公告栏有新信时，如果内容类型和我登记的一样，就给我送一份。”

## 9. 回复、取消、父子信件

适配器也支持一些链路相关的发信方式。

### 9.1 基于上一封信派生新信

很多方法都有带 `parent` 的版本：

```java
commandCapability(parent, capabilityId, payloadType, payload);
publishTopic(parent, topicId, payloadType, payload);
```

这表示：新信是从上一封信派生出来的。

好处是协议中心能知道它们属于同一条链路，方便追踪、取消和清理。

### 9.2 回复上一封信

```java
respondTo(parent, PayloadType.TEXT, new TextPayload("处理完成"));
```

这像收信人写回信。适配器会自动把回信寄回上一封信的来源。

### 9.3 取消某封信

```java
cancelEnvelope(targetEnvelope, "USER_CANCELLED", "用户取消了操作");
```

这像给邮局发一封取消通知，让协议中心按规则取消目标信件。

## 10. 默认寄信习惯 AdapterDefaults

`AdapterDefaults` 是适配器的默认寄信习惯。

常用的有三个：

### 10.1 standard

```java
AdapterDefaults.standard()
```

适合普通异步任务。

特点：

- 普通优先级。
- 可以排队。
- 异步工作线程。
- 默认 30 秒期望完成，60 秒绝对过期。

### 10.2 mainThreadUi

```java
AdapterDefaults.mainThreadUi()
```

适合客户端 UI 或必须回到 Minecraft 主线程的工作。

特点：

- 主线程执行。
- 时间更短。
- 并发为 1。

如果你的处理逻辑需要操作 UI，或读取只能在客户端主线程安全读取的东西，就用这个。

### 10.3 highFrequencyFact

```java
AdapterDefaults.highFrequencyFact()
```

适合 hover、准星、tick 这类高频状态。

特点：

- 低优先级。
- 最新状态优先。
- 生命周期很短。

高频消息不要用普通长队列，否则容易把邮局塞满。

## 11. 自定义默认值

可以在现有默认值上改一点点。

例如：提高优先级。

```java
AdapterDefaults urgent = AdapterDefaults.standard()
    .withPriority(Priority.HIGH);
```

例如：改成主线程。

```java
AdapterDefaults ui = AdapterDefaults.standard()
    .withThreadPolicy(ThreadPolicy.MUST_MAIN)
    .withConcurrency(1, 32);
```

例如：缩短过期时间。

```java
AdapterDefaults shortLife = AdapterDefaults.standard()
    .withTiming(2_000L, 5_000L);
```

适配器会保证 `expireMs` 不早于 `deadlineMs`，避免信封刚写好就因为时间关系非法。

## 12. Payload 内容必须注意什么

Payload 就是信件内容。

规则很重要：

1. 内容对象要实现 `ITianshuPayload`。
2. 内容尽量用 Java `record`。
3. 不要把 Minecraft 活对象塞进去。

不要放这些：

- `Entity`
- `ItemStack`
- `Level`
- `Player`
- `Screen`
- `PoseStack`

要先做快照，再放进信件内容。

比如不要寄“活的物品对象”，要寄“这个物品当时长什么样的快照”。

协议中心不理解业务内容，但它会检查 Payload 类型和登记信息是否匹配。如果不匹配，会进入死信。

## 13. 写一个模块适配器的推荐套路

推荐每个模块写一个小适配器，不要到处手写信封。

结构像这样：

```java
public final class MyModuleProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "client.my_module";
    public static final String SOURCE_ID = "client.my_module";

    public MyModuleProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }

    public TianshuEnvelope sendTextToTts(String text) {
        return commandCapability(
            ProtocolCapabilities.TTS_SPEAK,
            PayloadType.TTS_TEXT,
            new TextPayload(text)
        );
    }

    public TianshuEnvelope publishSomething(MyPayload payload) {
        return publishTopic(
            ProtocolTopics.DEBUG_TRACE,
            PayloadType.CUSTOM,
            payload
        );
    }

    public void registerSomething(EnvelopeHandler handler) {
        registerCapability(
            "MY_MODULE.SOMETHING",
            PayloadType.CUSTOM,
            MyPayload.class,
            BrokerType.STATELESS_FAST_PATH,
            EnumSet.of(PacketType.COMMAND, PacketType.REQUEST),
            Priority.LOW,
            handler
        );
    }
}
```

模块其他地方只调用这些清晰的小方法，不要到处散落 `EnvelopeBuilder`。

## 14. 什么时候用哪种发信方法

简单判断：

| 你想做什么 | 推荐方法 |
|---|---|
| 找会做某件事的模块 | `commandCapability` / `requestCapability` |
| 发布一个大家都可能关心的状态 | `publishTopic` |
| 发高频最新状态 | `publishTopic` + `AdapterDefaults.highFrequencyFact()` |
| 回复上一封信 | `respondTo` |
| 取消上一封信或某个任务 | `cancelEnvelope` |
| 业务关系还没想好，先硬发到指定路线 | `submitDirect` |
| 特殊信件类型，比如 STREAM_CHUNK、STATUS、ERROR | `submitToCapability` / `submitToTopic` / `submitDirect` |

## 15. 模块开发人员需要知道的 API 和可改参数

这一节只列模块开发时常用、必要的东西。协议中心内部怎么排队、怎么记录死信、怎么清理链路，一般不用管。

### 15.1 发信 API 速查

| API | 什么时候用 | 你主要要填什么 |
|---|---|---|
| `commandCapability(...)` | 让“会某种能力的人”做事 | 能力名、内容类型、内容 |
| `requestCapability(...)` | 向“会某种能力的人”请求处理，通常期待成功或失败状态 | 能力名、内容类型、内容 |
| `publishTopic(...)` | 把消息发到某个主题，让订阅者都能收到 | 主题名、内容类型、内容 |
| `submitToCapability(...)` | 需要自己指定信件种类时用，比如流式片段、状态、错误 | 能力名、信件种类、内容类型、内容 |
| `submitToTopic(...)` | 需要给主题发特殊信件种类时用 | 主题名、信件种类、内容类型、内容 |
| `submitDirect(...)` | 临时硬发信或私有路线 | 直投路线、信件种类、内容类型、内容 |
| `respondTo(...)` | 收到一封信后回信 | 原信封、回复内容类型、回复内容 |
| `cancelEnvelope(...)` | 请求取消某封信 | 目标信封、原因码、说明文字 |

模块里最常用的是前三个：`commandCapability`、`requestCapability`、`publishTopic`。

如果业务还没想清楚，可以先用 `submitDirect` 或 `submitToCapability` 硬发信。后面业务关系稳定后，再慢慢改成能力或主题。

### 15.2 收信登记 API 速查

| API | 什么时候用 | 你主要要填什么 |
|---|---|---|
| `registerCapability(...)` | 我这个模块能处理某种能力 | 能力名、内容类型、内容类、处理线程、能收哪些信件、最低优先级、处理函数 |
| `subscribeTopic(...)` | 我这个模块想收到某个主题 | 主题名、内容类型、内容类、处理线程、能收哪些信件、最低优先级、处理函数 |
| `registerDirectRoute(...)` | 我这个模块愿意收某条直投路线 | 路线名、内容类型、内容类、处理线程、能收哪些信件、最低优先级、处理函数 |

普通模块优先用 `registerCapability` 和 `subscribeTopic`。`registerDirectRoute` 适合临时硬接入或私有通道。

### 15.3 发信时最常改的参数

发信时一般不是直接改信封，而是传一个 `AdapterDefaults`。

```java
AdapterDefaults urgent = AdapterDefaults.standard()
    .withPriority(Priority.HIGH)
    .withTiming(5_000L, 10_000L);
```

常用可改项：

| 可改项 | 怎么改 | 意义 | 常见选择 |
|---|---|---|---|
| 优先级 | `withPriority(...)` | 邮局先处理谁 | `CRITICAL` 紧急打断，`HIGH` 高优先，`NORMAL` 普通，`LOW` 低优先，`BACKGROUND` 后台 |
| 执行线程 | `withThreadPolicy(...)` | 收信处理应在哪类线程执行 | `MUST_MAIN` 主线程 UI，`ASYNC_WORKER` 普通异步，`IO_BLOCKING` 网络/文件/模型调用，`ANY` 交给收信方自己保证 |
| 投递习惯 | `withDeliveryPolicy(...)` | 这封信适合怎么排队 | `WAIT_IN_QUEUE` 排队，`FIRE_AND_FORGET` 发出就不等，`LATEST_ONLY` 只要最新状态，`COALESCE` 可合并同类消息 |
| 超时时间 | `withTiming(deadlineMs, expireMs)` | 期望多久完成、最晚多久销毁 | 普通信件用默认；UI/高频状态用短时间；网络/LLM 可适当长一点 |
| 取消范围 | `withCancellationScope(...)` | 取消时影响多大 | `SELF_ONLY` 只取消自己，`CHILDREN` 连子信一起取消，`TRACE` 整条链路取消，`RESOURCE` 同资源抢占时用 |
| 失败策略 | `withFailurePolicy(...)` | 失败后怎么处理 | `REPORT_ONLY` 只上报，`PROPAGATE_CANCEL` 失败后传播取消，`IGNORE` 忽略，`RETRY` 尝试重试，`FALLBACK` 允许降级 |
| 并发和队列 | `withConcurrency(max, queue)` | 这个模块登记收信时允许同时处理多少、队列多长 | UI 通常 `1, 32`；普通异步可以更大；高频状态要小 |

对模块开发来说，最常用的是：

- 普通发信：不传，使用默认。
- UI 或主线程：用 `AdapterDefaults.mainThreadUi()`。
- 高频状态：用 `AdapterDefaults.highFrequencyFact()`。
- 紧急消息：`.withPriority(Priority.CRITICAL)` 或 `.withPriority(Priority.HIGH)`。
- 短生命周期消息：`.withTiming(...)`。

### 15.4 登记收信时最需要填对的参数

以这个登记为例：

```java
registerCapability(
    ProtocolCapabilities.GEMINI_CARD_SHOW,
    PayloadType.NONE,
    EmptyPayload.class,
    BrokerType.MAIN_THREAD,
    EnumSet.of(PacketType.COMMAND),
    Priority.LOW,
    handler
);
```

这些参数的意思：

| 参数 | 意义 | 怎么选 |
|---|---|---|
| 能力名或主题名 | 告诉邮局“我收哪类信” | 用 `ProtocolCapabilities` 或 `ProtocolTopics` 里的常量；还没定时可以先写临时字符串 |
| `PayloadType` | 信件内容的大类型 | 必须和发信方一致，不一致会被拒绝 |
| `payloadClass` | 信件内容的 Java 类 | 必须和实际 Payload 对象匹配 |
| `BrokerType` | 这类信适合哪种处理方式 | UI 用 `MAIN_THREAD`，普通快速逻辑用 `STATELESS_FAST_PATH`，普通排队用 `BOUNDED_QUEUE`，独占打断用 `EXCLUSIVE_INTERRUPT`，有限并发用 `PARALLEL_LIMIT`，高频最新状态用 `LATEST_ONLY` |
| `acceptedPacketTypes` | 我能收哪些信件种类 | 常见是 `COMMAND`、`REQUEST`、`EVENT`；不确定时不要乱收全部，先只填自己会处理的 |
| `minPriority` | 低于这个优先级的信不收 | 普通用 `LOW` 或 `NORMAL`；重要能力可提高 |
| `CompletionPolicy` | 处理完成怎么算结束 | 普通同步处理用 `AUTO_COMPLETE_ON_RETURN`；异步或流式任务用 `MANUAL_COMPLETE` 或 `STREAMING_MANUAL_COMPLETE` |
| `handler` | 真正处理信的函数 | 只处理自己模块内部逻辑，不直接调用别的业务模块实现类 |

### 15.5 常用取值怎么理解

#### PacketType：信件种类

| 取值 | 模块开发时怎么理解 |
|---|---|
| `EVENT` | 事件通知，比如某个状态发生了 |
| `COMMAND` | 让对方做一件事 |
| `REQUEST` | 请求对方处理，通常希望有结果或状态 |
| `RESPONSE` | 回信 |
| `STREAM_START` / `STREAM_CHUNK` / `STREAM_END` | 流式内容开始、片段、结束 |
| `CANCEL` | 取消通知 |
| `STATUS` | 状态通知 |
| `ERROR` | 错误通知 |
| `HEARTBEAT` | 我还活着，还在处理 |
| `PROGRESS` | 进度更新 |

#### PayloadType：内容大类

常用的有：

| 取值 | 适合内容 |
|---|---|
| `NONE` | 没有内容，只是一个动作信号 |
| `TEXT` | 普通文字 |
| `TTS_TEXT` | 要给 TTS 读的文字 |
| `LLM_PROMPT` | 给 LLM 的提示词 |
| `LLM_TEXT_CHUNK` | LLM 流式文本片段 |
| `SNAPSHOT` | 从 Minecraft 活对象拷出来的快照 |
| `STATUS` / `ERROR` / `CANCEL` / `HEARTBEAT` / `PROGRESS` | 协议状态类内容 |
| `CUSTOM` | 临时或模块自定义内容 |

如果还没想好业务类型，可以先用 `CUSTOM`，但发信方和收信方必须一致。

#### Priority：优先级

| 取值 | 什么时候用 |
|---|---|
| `CRITICAL` | 高危警报、必须抢占普通任务 |
| `HIGH` | 用户明确触发、比较重要 |
| `NORMAL` | 默认普通任务 |
| `LOW` | 不急的反馈或状态 |
| `BACKGROUND` | 后台分析、可延后 |

#### ThreadPolicy：线程要求

| 取值 | 什么时候用 |
|---|---|
| `MUST_MAIN` | UI、客户端主线程安全读取 |
| `ASYNC_WORKER` | 普通后台处理 |
| `IO_BLOCKING` | 网络、文件、模型调用等阻塞操作 |
| `ANY` | 很确定收信方自己能保证安全时才用 |

#### BrokerType：收信处理方式

| 取值 | 模块开发时怎么选 |
|---|---|
| `MAIN_THREAD` | UI 或必须主线程处理 |
| `STATELESS_FAST_PATH` | 很快、无状态、本地规则处理 |
| `BOUNDED_QUEUE` | 普通短队列处理 |
| `EXCLUSIVE_INTERRUPT` | TTS、音频这类一次只允许一个，且高优先级可打断 |
| `PARALLEL_LIMIT` | LLM、网络请求这类允许有限并发 |
| `LATEST_ONLY` | hover、准星、tick 这种只关心最新状态 |
| `SERVER_PACKET` | 涉及服务端真实状态变化的高危动作 |

### 15.6 一个最小可用写法

如果你只是想让模块先能发信，可以先这样：

```java
public TianshuEnvelope sendSomething(MyPayload payload) {
    return submitToCapability(
        "MY_TEMP_CAPABILITY",
        PacketType.COMMAND,
        PayloadType.CUSTOM,
        payload
    );
}
```

如果你只是想让模块先能收信，可以先这样：

```java
public void registerSomething(EnvelopeHandler handler) {
    registerCapability(
        "MY_TEMP_CAPABILITY",
        PayloadType.CUSTOM,
        MyPayload.class,
        BrokerType.STATELESS_FAST_PATH,
        EnumSet.of(PacketType.COMMAND),
        Priority.LOW,
        handler
    );
}
```

这就是最小的“写信人 + 收信登记”。业务细节以后可以慢慢换成正式能力名、正式 PayloadType、正式 Broker。

## 16. 常见错误

### 16.1 PayloadType 写错

你发的是 `PayloadType.TEXT`，但收信人登记的是 `PayloadType.TTS_TEXT`，协议中心会拒绝投递。

### 16.2 Payload 类写错

你登记的是 `TextPayload.class`，但实际寄的是别的 Payload，也会被拒绝。

### 16.3 线程策略和 Broker 不配

如果登记的是 `BrokerType.MAIN_THREAD`，寄信默认也要适合主线程，通常用 `AdapterDefaults.mainThreadUi()`。

### 16.4 高频消息不加控制

hover、tick、准星状态不要用普通默认值疯狂发送。应该用 `AdapterDefaults.highFrequencyFact()`。

### 16.5 到处直投

直投可以用来硬跑通，但不要把长期业务都写成固定门牌号。后面业务稳定后，优先改成能力或主题。

## 17. 这次适配器已经补强的地方

这次适配器侧已经做了这些稳定性和功能性补强：

1. 模块登记支持重复使用同一个模块 ID，不会因为同一模块先订阅主题、再登记能力而崩掉。
2. 适配器支持登记直投路线。
3. 适配器支持直接硬发到直投路线。
4. 适配器支持更通用的 `submitToCapability` 和 `submitToTopic`，可以发 `COMMAND`、`REQUEST`、`STATUS`、`STREAM_CHUNK` 等不同信件类型。
5. 适配器支持 `respondTo`，用于回信。
6. 适配器支持 `cancelEnvelope`，用于发取消通知。
7. `AdapterDefaults` 增加了更多改默认值的方法，例如优先级、线程、取消范围、失败策略、并发、队列、是否支持流式。
8. `AdapterDefaults` 会保证绝对过期时间不早于期望完成时间，减少非法信封。
9. 发信入口会检查空的 builder、handler、parent、targetEnvelope，避免空指针问题藏到更深的地方。

## 18. 当前验证情况

IDE 对这几个文件没有报错：

- `AbstractProtocolAdapter.java`
- `AdapterDefaults.java`
- `ModuleRegistry.java`

尝试运行项目自带 Gradle Wrapper 时，环境报错：

`gradle-wrapper.jar 中没有主清单属性`

这说明当前 wrapper jar 没能正常启动，不是 Java 源码编译错误。系统里也没有全局 `gradle` 命令，所以完整 Gradle 编译没有在当前环境完成。

后续如果修复 Gradle Wrapper，建议运行：

```powershell
.\gradlew.bat :tianshu-common:compileJava
```

如果要检查整个 NeoForge 模组，再运行项目已有的完整 build 任务。
