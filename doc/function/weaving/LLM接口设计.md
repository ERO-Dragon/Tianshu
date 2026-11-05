# LLM 协议接口设计

> 织言是**云端推理传输 + 向量/RAG 数据面**。它不组装提示词、不管理上下文、不编排工具；这些由调用方（辅星或其它模组）自己负责。本文件描述当前实现，历史本地引擎语义不再保留。

## 1. 能力一览

| 能力 | Payload | 说明 |
|---|---|---|
| `LLM.REQUEST` | `LLMPromptRequestPayload` | 一次推理：消息列表 + 采样 + 流式开关 |
| `LLM.CACHE_MANAGE` | `LLMCacheManagePayload` | RAG 库注册与条目的增删改查、检索 |
| `LLM.PRIMITIVE_QUERY` | `LLMPrimitiveQueryPayload` | token 估算、embedding、运行态快照 |

响应类型：

| 请求 | 响应 |
|---|---|
| `LLM.REQUEST` | `LLMPromptResultPayload`（终态结果） |
| `LLM.REQUEST` + `stream=true` | `LLMPromptStreamChunkPayload`（分片 + 结束帧），随后仍有终态结果 |
| `LLM.CACHE_MANAGE` | `LLMCacheManageResultPayload` |
| `LLM.PRIMITIVE_QUERY` | `LLMPrimitiveResultPayload` |

模块 id 为 `module.llm`，能力名由本模块在装配期注册。

## 2. `LLM.REQUEST`

### 2.1 请求字段

| 字段 | 默认 | 说明 |
|---|---|---|
| `requestId` | `"llm.request"` | 调用方生成的请求标识，用于响应关联与日志 |
| `messages` | 空 | 要发送的消息，**按顺序原样发送**；角色只接受 `system` / `user` / `assistant`，未知角色降级为 `user` |
| `stream` | `false` | `true` 时先返回分片与结束帧，再返回终态结果；两种模式内部都走同一条 HTTP 流 |
| `captureThinkingContent` | `false` | 要求把供应商的 reasoning 文本放到独立通道；正文永不混入 |
| `maxTokens` | `0` | 最大生成 token；`0` 表示不额外限制 |
| `reasoningTier` | `null` | 该模型推理档位的序号；`null` = 端点默认。见 2.2 |
| `sampling` | 空 | 采样覆盖项，见 2.3 |

空消息列表会在发出请求前被拒绝，错误码 `LLM_EMPTY_MESSAGES`。

### 2.2 推理档位

调用方只使用**序号**：每个模型有一条从 0 开始的有序档位表，`0` 是模型支持的非思考档，其余序号依次是提供商官方的档位名。表长与名称由提供商 profile 的数据决定，织言不写死 `low/medium/high` 这类字面量。

- 请求：`reasoningTier`；`null` 表示“用这个模型的默认档”，不是“什么都不发”——端点自己的默认往往是高档，所以默认档必须显式编码出去；
- 能力查询：`LLM.PRIMITIVE_QUERY`（`queryType=STATUS`）返回 `reasoningTierCount`、`reasoningIncludesNoThinking`、`reasoningDefaultTier`、`reasoningLevels`（index = tier，非思考档在 `levels` 中没有条目）；
- 越界：请求超出当前梯子时夹到最近档，模型切换后旧序号不会让请求失败；
- 无控制：`reasoningTierCount=0` 的模型（例如没有档位数据的自定义提供商）完全不发送推理字段。

提供商的“线协议形状”仍是代码，档位名称与顺序是数据：

| wire | 线上字段 | 编码 |
|---|---|---|
| `DEEPSEEK_THINKING` | `thinking.type` + `reasoning_effort` | tier 0 → `disabled`；其余 → `enabled` + 该 tier 的官方名称 |
| `OPENAI_EFFORT` | `reasoning_effort` | 该 tier 的官方名称；没有非思考槽时第一项就是最低档 |
| `NONE` | 无 | 不发送 |

DeepSeek 内置档位表：`[非思考, low, high, max]`，**默认 tier 1（low）**。如果官方以后增加 `medium`，只需在数据里插入一项，序号自然变化；不做名称之间的别名折叠。

织言**不提供“模型预设”**：模型迭代很快，把某几个模型绑进预设表只会很快过期。内置提供商的梯子是提供商 profile 的数据；没有档位数据的提供商就是“无控制”，调用方按 `reasoningTierCount=0` 处理。

`captureThinkingContent` 与档位正交：前者决定是否把 reasoning 文本回传，后者决定生成多深。DeepSeek 思考模式不接受 `temperature` / `frequency_penalty` / `presence_penalty`，这些字段仍会发送但被端点忽略；`top_p` 的行为随模型而变。

### 2.3 `SamplingPayload`

| 字段 | 说明 |
|---|---|
| `temperature` | `0..2`，越界丢弃 |
| `topP` | `0..1`，越界丢弃 |
| `frequencyPenalty` | `-2..2`，越界丢弃 |
| `presencePenalty` | `-2..2`，越界丢弃 |

`null` 表示跟随模型目录中该模型的默认采样；请求里给出的值优先。协议层只发送这四个字段，其它采样参数不属于 OpenAI 兼容完成接口。

### 2.4 流式语义

- 正文分片：`text` 非空，`finished=false`。
- thinking 分片：`thinkingContent` 非空，`text` 为空（仅当请求要求捕获）。
- 结束帧：`finished=true`，带 `finishType`（`COMPLETED` / `CANCELLED` / `FAILED`）、累计 thinking 文本与 usage；发生失败时带 `errorMessage`。
- 结束帧之后仍会收到 `LLMPromptResultPayload`；`index` 严格递增，调用方应按 `index` 拼接——分片经协议运行时派发，可能由不同工作线程送达，按到达顺序拼接会打乱正文。
- 取消或中断时：结束帧 `CANCELLED`，终态结果同样为 `CANCELLED`，正文是本轮已经发出的部分。

### 2.5 终态结果

| 字段 | 说明 |
|---|---|
| `requestId` | 对应请求 |
| `status` | `COMPLETED` / `CANCELLED` / `FAILED` |
| `text` | 可见正文 |
| `thinkingContent` | 结构化 reasoning；不混入 `text` |
| `errorCode` / `errorMessage` | 失败原因 |
| `usage` | `promptTokens` / `completionTokens` / `thinkingTokens` / `outputTokens` / `totalTokens` |

`usage` 来自供应商返回的真实用量；`outputTokens = completionTokens + thinkingTokens`。未报告时为零值，不猜测。

### 2.6 错误码

| 码 | 含义 |
|---|---|
| `LLM_SERVICE_NOT_READY` | 云端通道尚未就绪 |
| `LLM_EMPTY_REQUEST` | 请求对象为空 |
| `LLM_EMPTY_MESSAGES` | 消息列表为空 |
| `LLM_INFERENCE_FAILED` | 供应商失败、非 2xx、超时或流中断 |
| `LLM_REQUEST_FAILED` | 请求转换或执行过程失败（兜底码） |
| `LLM_REQUEST_CANCELLED` | 调用方取消 |
| `LLM_REQUEST_INTERRUPTED` | 执行线程中断 |

前三个是**发出前**就被拒绝的请求，一律带自己的码。取消与中断按类型判定：`CancellationException` → `LLM_REQUEST_CANCELLED`，线程中断 → `LLM_REQUEST_INTERRUPTED`，不靠比较错误文本。

## 3. `LLM.CACHE_MANAGE`

只做 RAG 数据面，不做提示词注入：调用方检索到内容后自己拼进 `messages`。

| 操作 | 说明 |
|---|---|
| `UPSERT_ENTRY` / `PATCH_ENTRY` / `DELETE_ENTRY` / `CLEAR_UID` | 条目写入、局部更新、删除、清空 |
| `REGISTER_LIBRARY` / `UNREGISTER_LIBRARY` | 库登记与注销；`uid` 是库，`modid` / `tags` / `visibility` 只用于共享发现 |
| `QUERY_UID` | 查询库是否存在 |
| `SEARCH_UID` | 只检索一个库 |
| `SEARCH_MODID` / `SEARCH_TAGS` | 检索共享库，命中多个库时一次查询只生成一次 query 向量 |
| `SEARCH_INLINE_CONTENTS` | 只检索请求携带的文本，不写库、不触发持久化 |

检索为 BM25 + 向量的混合检索：embedding 不可用时退化为纯词法；只有向量没有文本的条目只参与向量检索。命中条目返回 `entryId`、`content`、`score`。

### 3.1 存储

持久 RAG 位于 `config/Tianshu/module/llm/ragCache/entries/`，按 `uid` 分文件；命名空间由向量模型决定，模型变化后旧向量被视为不可用并等待重建。写入先更新内存，磁盘按约 1 秒 debounce 合并；`CLEAR_UID` 立即删除；模块停止时 flush。

## 4. `LLM.PRIMITIVE_QUERY`

### 4.1 `TOKEN_COUNT`

发送前的 prompt 大小**估算**。云端没有“只数不生成”的接口，所以这里用本地估算器，并在每次真实请求后用供应商返回的 `prompt_tokens` 校准。它不用于计费：计费与账本使用 2.4 的真实 usage。

输入为 `text` 或 `messages`；两者可同时给出，按顺序拼接。

### 4.2 `EMBED`

对 `texts` 调用本地向量引擎。引擎未就绪时返回 `LLM_EMBEDDING_UNAVAILABLE`；执行失败返回 `LLM_EMBED_FAILED`。每条结果包含 `text`、`dimension`、可选 `vector`、`embeddingModelName`、`embeddingNamespace`。

### 4.3 `STATUS`

```java
record LLMRuntimeSnapshotPayload(
    boolean ready,
    boolean embeddingAvailable,
    int embeddingDimension,
    int contextSize,            // 模型声明的上下文容量
    int contextTokenBudget,     // 容量 − 输出保留 − 头部余量
    String embeddingModelName,
    String embeddingNamespace,
    int reasoningTierCount,
    boolean reasoningIncludesNoThinking,
    int reasoningDefaultTier,
    List<String> reasoningLevels, // index = tier；非思考档没有条目
    String failureMessage,
    long sampledAtMillis
)
```

调用方做 prompt 预算时消费 `contextTokenBudget`；选择推理档位时消费 `reasoningTierCount` / `reasoningIncludesNoThinking` / `reasoningDefaultTier`，并只把序号放进请求。

## 5. 运行时

- 云端通道只校验配置（地址、密钥、模型）；启动不发送探测请求，网络断开不影响模块就绪。
- 上下文容量来自所选模型条目声明的 `contextWindowTokens`（tier 值，例如 100K / 200K / 500K）。预算公式为
  `容量 − 512 输出保留 − headroom`，headroom 为 prompt 窗口的 6.25%，钳位 4096..32768 并向上取整到 1024。
- 向量引擎是本机唯一加载的模型：进世界后延迟数秒在模型加载 lane 上启动；GPU 失败自动回落 CPU（`DEGRADED`），两级都失败才 `UNAVAILABLE`。
- 向量引擎起不来**不会**让云端通道失败：聊天不依赖检索，此时只有 RAG / `EMBED` 报不可用。
- 原生模型加载经过进程级串行闸门，与听澜 / 回响的语音模型互斥。
- 向映迹报告 `llm.model.load`（加载）、`llm.runtime.reconnecting` / `llm.runtime.error`（氛围状态）；映迹缺席时完全旁路。请求本身不产生映迹活动，那属于调用方的一轮对话。
- 取消或线程中断会中止底层 HTTP 交换，而不是让读者线程继续读到超时；中断标志会被恢复，交给上层决定。

## 6. 边界

织言**不做**：

- 提示词组装、system 合并、RAG 文本注入、编号与上下文裁剪；
- 记忆、人格、对话所有权；
- 工具定义、工具调用与结果回填；
- 任务调度、优先级、抢占或本地队列。

这些属于调用方。织言只负责把调用方给的消息发出去、把流收回来，并提供向量与检索的数据面。

## 7. 用量与余额

- 每个请求结束后按 envelope 的 `sourceId` 记入本地账本：请求数、输入 / 输出 token、缓存命中断言（供应商报告才有）。
- 本地计价按配置的每百万 token 单价估算，仅供对账，**不是账单**。
- 剩余余额来自供应商账户接口；未接入或失败时显示“未知”，绝不以 0 代替。
