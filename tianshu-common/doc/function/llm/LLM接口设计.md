# LLM 协议接口设计

## 1. 概述

外部模块不直接调用 `LLMService`。AX、IA 或其他模块需要 LLM 能力时，只通过协议中心和 LLM 模块通信。

`LLMRequest` 是 `LlmProtocolAdapter` 转入 LLM 模块内部后使用的实现对象，不属于外部模块协议接口。外部模块只需要构造本文件中的 protocol payload。

LLM 模块对外提供两个协议能力：

| 能力 | Payload | 说明 |
|------|---------|------|
| `ProtocolCapabilities.LLM_REQUEST` | `LLMPromptRequestPayload` | 发起聊天、流式聊天、后台 TASK |
| `ProtocolCapabilities.LLM_CACHE_MANAGE` | `LLMCacheManagePayload` | 添加、查询、删除 RAG cache |
| `ProtocolCapabilities.LLM_PRIMITIVE_QUERY` | `LLMPrimitiveQueryPayload` | token 计数、embedding 向量化、运行态快照 |

响应类型：

| 请求能力 | 响应 Payload | 说明 |
|----------|--------------|------|
| `ProtocolCapabilities.LLM_REQUEST` | `LLMPromptResultPayload` | 最终结果，包含状态、文本、RAG hits、usage |
| `ProtocolCapabilities.LLM_REQUEST` + `stream=true` | `LLMPromptStreamChunkPayload` | 流式 token 分片；结束包包含 terminal 状态和 usage，随后还有最终 `LLMPromptResultPayload` |
| `ProtocolCapabilities.LLM_CACHE_MANAGE` | `LLMCacheManageResultPayload` | cache 管理结果 |
| `ProtocolCapabilities.LLM_PRIMITIVE_QUERY` | `LLMPrimitiveResultPayload` | 原语查询结果，按请求类型返回 token、vector 或状态快照 |

--- 

## 5. `ProtocolCapabilities.LLM_PRIMITIVE_QUERY`

外部模块向 `ProtocolCapabilities.LLM_PRIMITIVE_QUERY` 提交 `LLMPrimitiveQueryPayload`。

### 5.1 支持的 queryType

| queryType | 说明 |
|-----------|------|
| `TOKEN_COUNT` | 对文本、消息列表或请求块做真实 token 计数 |
| `EMBED` | 对文本数组做 embedding |
| `STATUS` | 查询 LLM 运行态、模型信息和队列信息 |

### 5.2 LLMPrimitiveQueryPayload 主要字段

| 字段 | 说明 |
|------|------|
| `requestId` | 请求标识 |
| `queryType` | 原语类型：`TOKEN_COUNT` / `EMBED` / `STATUS` |
| `text` | 参与 token 计数的单段文本 |
| `texts` | `EMBED` 使用的文本列表 |
| `messages` | `TOKEN_COUNT` 使用的消息列表 |
| `chunks` | `TOKEN_COUNT` 使用的请求块 |
| `includeVector` | `EMBED` 是否回传向量本体 |
| `includeEmbeddingDetails` | `EMBED` 是否回传更完整的 embedding 细节 |
| `includeRuntimeDetails` | `STATUS` 是否回传模型名 / profile 等运行态细节 |

### 5.3 LLMPrimitiveResultPayload 主要字段

| 字段 | 说明 |
|------|------|
| `requestId` | 对应请求 ID |
| `queryType` | 对应查询类型 |
| `status` | `COMPLETED` 或 `FAILED` |
| `tokenCount` | `TOKEN_COUNT` 的结果 |
| `embedResults` | `EMBED` 的结果列表 |
| `runtimeSnapshot` | `STATUS` 的结果快照 |
| `errorCode` / `errorMessage` | 失败信息 |

`STATUS` 返回的快照应尽量保守：未知值可以返回 `-1` 或空字符串，不要硬猜。

## 2. `ProtocolCapabilities.LLM_REQUEST`

外部模块向 `ProtocolCapabilities.LLM_REQUEST` 提交 `LLMPromptRequestPayload`。

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "ax.chat",
    0,
    0.7f,
    false,
    false,
    false,
    "CHAT",
    0,
    false,
    List.of(
        LLMPromptRequestPayload.ChunkPayload.message(List.of(
            LLMPromptRequestPayload.MessageItemPayload.system("你是铁匠NPC"),
            LLMPromptRequestPayload.MessageItemPayload.user("我需要一把剑")
        )),
        LLMPromptRequestPayload.ChunkPayload.rag(
            "ax_world_memory",
            "相关记忆：",
            List.of("玩家手持铁锭"),
            true,
            true,
            1000
        )
    ),
    dialogueSessionId,
    "module.ax",
    requesterParticipantId,
    dialogueTurnId,
    LLMPromptRequestPayload.InferencePolicyPayload.followGlobal()
);
```

协议提交时使用：

| 项 | 值 |
|----|----|
| capability | `ProtocolCapabilities.LLM_REQUEST` |
| payloadType | `PayloadType.LLM_PROMPT_REQUEST` |
| payload | `LLMPromptRequestPayload` |

提交示例：

```java
TianshuEnvelope envelope = EnvelopeBuilder.requestCapability(
        "module.ax",
        ProtocolCapabilities.LLM_REQUEST,
        PayloadType.LLM_PROMPT_REQUEST,
        payload
).build();

protocolRuntime.submit(envelope);
```

### 2.1 LLMPromptRequestPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `requestId` | `"llm.request"` | 调用方生成的请求标识，用于响应关联和日志定位。 |
| `maxTokens` | `0` | 最大生成 token 数；`0` 表示不额外限制。 |
| `temperature` | `0.7` | 采样温度，合法范围 `0.0..2.0`；非法值会回落到 `0.7`。 |
| `stream` | `false` | 是否流式返回。`true` 时会先收到若干 `LLM_PROMPT_STREAM_CHUNK`，最后收到 `LLM_PROMPT_RESULT`。 |
| `thinking` | `false` | 是否允许模型生成 thinking 内容。 |
| `includeThinkingContent` | `false` | 是否把 `<think>...</think>` 内容暴露给调用方；不影响模型是否生成 thinking。 |
| `lane` | `"CHAT"` | 执行通道：`CHAT` 或 `TASK`。非法值按 `CHAT` 处理。 |
| `taskPriority` | `0` | TASK 优先级，范围 `0..1000`；仅 `lane=TASK` 时参与调度。 |
| `taskPreemptible` | `false` | 当前 TASK 是否允许被更高优先级 TASK 抢占；仅 `lane=TASK` 时有效。 |
| `chunks` | 空列表 | 请求内容，按数组顺序处理。支持 `message` 和 `rag` 两类 chunk。 |
| `dialogueSessionId` | 空字符串 | CHAT 请求的对话会话 ID；用于 IA 授权。 |
| `requesterModuleId` | 空字符串 | 请求方模块 ID，必须与协议 envelope 的 source module 一致。 |
| `requesterParticipantId` | 空字符串 | 请求方参与者 ID；用于 IA 授权。 |
| `dialogueTurnId` | 空字符串 | 对话轮次 ID，用于追踪一次对话回合。 |
| `inferencePolicy` | 跟随全局 | 请求级推理策略覆盖项。 |

CHAT 请求需要带对话授权上下文：`dialogueSessionId`、`requesterModuleId`、`requesterParticipantId`。LLM 模块会通过 IA 授权能力确认该调用方是否允许使用 CHAT LLM。

TASK 请求不走 CHAT 的 IA 授权，但会进入 LLM 模块的 TASK admission 队列。

`thinking` 和 `includeThinkingContent` 是两个不同边界：
- `thinking=false`：请求模型不要生成 thinking，LLM 模块会传给底层 sampler。
- `thinking=true + includeThinkingContent=false`：允许模型生成 thinking，但协议响应会隐藏规范化后的 `<think>...</think>` 内容。
- `thinking=true + includeThinkingContent=true`：协议响应保留 `<think>...</think>` 内容，通常只用于调试、评估或专门展示推理过程的工具。

### 2.2 ChunkPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `type` | `"message"` | chunk 类型：`message` 或 `rag`。 |
| `messageContent` | 空列表 | `type=message` 时使用，按顺序加入消息上下文。 |
| `ragContent` | 空列表 | `type=rag` 时使用。非空时可被增量索引进 RAG cache。 |
| `uid` | 空字符串 | RAG 库标识；同一 cache scope 下按 uid 分文件保存。 |
| `prompt` | 空字符串 | RAG 命中内容注入模型前的提示前缀。 |
| `useCache` | `true` | `true` 使用 LLM 模块持久 RAG cache；`false` 仅对本次 `ragContent` 走 libs 临时检索。 |
| `includeRagHits` | `true` | 是否在最终 `LLMPromptResultPayload` 中返回命中的 RAG 内容。 |
| `memoryRagTokenBudget` | `1000` | 本 chunk 注入模型的 RAG 文本预算。 |
| `globalRagCache` | `false` | `false` 使用当前世界 cache；`true` 使用全局 cache。 |

`message` chunk 示例：

```java
LLMPromptRequestPayload.ChunkPayload.message(List.of(
    LLMPromptRequestPayload.MessageItemPayload.system("你是一个 Minecraft 助手"),
    LLMPromptRequestPayload.MessageItemPayload.user("我的钻石镐在哪里？")
));
```

当前世界 RAG chunk 示例：

```java
LLMPromptRequestPayload.ChunkPayload.rag(
    "ax_world_memory",
    "相关记忆：",
    List.of("玩家把钻石镐放在末影箱"),
    true,
    true,
    1000
);
```

全局 RAG chunk 示例：

```java
LLMPromptRequestPayload.ChunkPayload.globalRag(
    "ax_global_lore",
    "通用知识：",
    List.of(),
    true,
    true,
    1000
);
```

### 2.3 MessageItemPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `role` | `"user"` | 消息角色：`system`、`user`、`assistant`。 |
| `content` | 空字符串 | 消息正文。 |

### 2.4 InferencePolicyPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `frameGuardEnabled` | `null` | 是否启用保帧率调度；`null` 表示跟随全局配置。 |
| `targetFps` | `null` | 保帧率目标 FPS；非空时会限制在 `15..240`。 |
| `mtpEnabled` | `null` | 是否尝试启用 MTP；`null` 表示跟随全局配置。 |

规则：
- 未设置的字段跟随全局配置。
- 请求中设置了某一项，只覆盖这一项。
- MTP 仅在当前模型支持时启用，不支持时安全降级。
- 保帧率策略只在 LLM 与渲染共享 GPU 且运行时采样可用时生效。

### 2.5 LLMPromptResultPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `requestId` | 空字符串 | 对应请求 payload 的 `requestId`。 |
| `status` | `"FAILED"` | 结果状态：`COMPLETED`、`CANCELLED`、`FAILED`。 |
| `text` | 空字符串 | 最终可见文本；失败或取消时可能是 partial text。 |
| `errorCode` | `null` | 失败原因码。 |
| `errorMessage` | `null` | 失败原因描述。 |
| `ragHits` | 空列表 | 本次请求所有 RAG chunk 的命中结果；`COMPLETED`、`CANCELLED`、`FAILED` 都可能携带。 |
| `usage` | 空 usage | 本次实际 token 统计：`promptTokens`、`completionTokens`、`totalTokens`。 |

`usage.promptTokens` 来自底层真实 chat template prompt token 计数。`usage.completionTokens` 只统计归一化后对上层可见的回答 token，不包含 COT。

常见错误码：
- `LLM_SERVICE_NOT_READY`：LLM 服务尚未就绪。
- `DIALOGUE_AUTH_CONTEXT_MISSING`：CHAT 请求缺少 IA 授权上下文。
- `DIALOGUE_AUTH_REQUESTER_MISMATCH`：payload 里的 requester module 与 envelope source 不一致。
- `DIALOGUE_AUTH_UNAVAILABLE` / `DIALOGUE_AUTH_DENIED`：IA 授权能力不可用或拒绝。
- `LLM_TASK_QUEUE_FULL`：TASK admission 队列已满且请求未被接收。
- `LLM_INFERENCE_FAILED`：底层推理失败。
- `LLM_REQUEST_FAILED`：请求转换或处理过程失败。

### 2.6 LLMPromptStreamChunkPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `requestId` | 空字符串 | 请求标识。 |
| `text` | 空字符串 | 当前分片文本。 |
| `finished` | `false` | `false` 表示普通 token 分片；`true` 表示流式输出结束。 |
| `index` | 调用方传入值 | 分片序号，调用方可按序拼接。 |
| `ragHits` | 空列表 | 流式请求的 RAG metadata。LLM 模块会在首个 token 前发送一个 `text=""` 且 `ragHits` 非空的 metadata chunk。 |
| `finishType` | 空字符串 | 仅 `finished=true` 时有效：`COMPLETED`、`CANCELLED`、`FAILED`。 |
| `usage` | 空 usage | 仅结束包携带本次实际 token 统计。 |
| `errorMessage` | `null` | 仅 `finishType=FAILED` 时携带底层错误信息。 |

当 `includeThinkingContent=false` 时，stream chunk 和最终 result 都会在协议出口隐藏 `<think>...</think>` 内容；只包含 hidden thinking 的分片不会发送。

### 2.7 RagHitPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `uid` | 空字符串 | 命中的 RAG 库 uid。 |
| `globalRagCache` | `false` | 该命中来自当前世界 cache 还是全局 cache。 |
| `hits` | 空列表 | 命中条目列表。 |

`uid` 是 RAG 库的唯一标识。多个 RAG chunk 命中不同库时，会按请求处理顺序返回多个 `RagHitPayload`；调用方按 `uid` 识别对应 RAG 库即可。

---

## 3. CHAT / TASK 行为

| lane | 用途 | 调度行为 |
|------|------|----------|
| `CHAT` | 对话即时响应 | 不进入 TASK admission 队列；需要 IA 授权上下文。 |
| `TASK` | 后台任务、记忆压缩、长文本整理 | 进入 TASK admission 队列，按优先级调度。 |

TASK 调度规则：
- 默认只有一个 active TASK，其余进入等待队列。
- `taskPriority` 越高越优先。
- 如果当前 active TASK 的 `taskPreemptible=true`，更高优先级 TASK 可进入底层抢占流程。
- 协议层只在底层任务完成后发送最终 `LLMPromptResultPayload`。

流式请求：
- `stream=false`：只返回最终 `LLMPromptResultPayload`。
- `stream=true`：如果本次请求产生 RAG hits，会先返回一个 `text=""`、`ragHits` 非空的 metadata chunk；随后返回 token chunk，再返回 `finished=true` 的 terminal stream end，最后返回最终 `LLMPromptResultPayload`。
- 协议 envelope 只在 stream end 和最终 result 都发出后完成。
- CHAT / TASK 被取消、线程中断或终止型抢占时，流式请求会收到 `finishType=CANCELLED` 的 stream end 和 `status=CANCELLED` 的最终 result；`text` 为已经对外发送过的可见 partial text，`ragHits` 仍保留已计算出的命中结果。

---

## 4. `ProtocolCapabilities.LLM_CACHE_MANAGE`

外部模块向 `ProtocolCapabilities.LLM_CACHE_MANAGE` 提交 `LLMCacheManagePayload`。

```java
LLMCacheManagePayload.indexGlobal(
    "ax_global_lore",
    List.of("通用世界观文本")
);

LLMCacheManagePayload.index(
    "ax_world_memory",
    List.of("当前世界记忆")
);
```

协议提交时使用：

| 项 | 值 |
|----|----|
| capability | `ProtocolCapabilities.LLM_CACHE_MANAGE` |
| payloadType | `PayloadType.LLM_CACHE_MANAGE` |
| payload | `LLMCacheManagePayload` |

提交示例：

```java
TianshuEnvelope envelope = EnvelopeBuilder.requestCapability(
        "module.ax",
        ProtocolCapabilities.LLM_CACHE_MANAGE,
        PayloadType.LLM_CACHE_MANAGE,
        LLMCacheManagePayload.queryGlobal("ax_global_lore")
).build();

protocolRuntime.submit(envelope);
```

### 4.1 LLMCacheManagePayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `action` | `"QUERY"` | 操作类型：`INDEX`、`QUERY`、`EVICT_ALL`、`EVICT_CONTENT`。 |
| `uid` | 空字符串 | RAG 库标识。 |
| `contents` | 空列表 | `INDEX` 时表示要加入 cache 的文本；`EVICT_CONTENT` 时表示要删除的具体文本。 |
| `globalRagCache` | `false` | `false` 操作当前世界 cache；`true` 操作全局 cache。 |

快捷构造：

```java
LLMCacheManagePayload.index("ax_world_memory", texts);
LLMCacheManagePayload.indexGlobal("ax_global_lore", texts);
LLMCacheManagePayload.query("ax_world_memory");
LLMCacheManagePayload.queryGlobal("ax_global_lore");
LLMCacheManagePayload.evictAll("ax_world_memory");
LLMCacheManagePayload.evictAllGlobal("ax_global_lore");
LLMCacheManagePayload.evictContent("ax_world_memory", texts);
LLMCacheManagePayload.evictGlobalContent("ax_global_lore", texts);
```

### 4.2 LLMCacheManageResultPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `action` | 空字符串 | 结果动作：`INDEX`、`QUERY`、`EVICT`、`FAILED`。 |
| `uid` | 空字符串 | 对应 RAG 库标识。 |
| `success` | 调用方传入值 | 操作是否成功。 |
| `exists` | 调用方传入值 | `QUERY` 时表示 cache 是否存在；`INDEX` 成功时为 `true`；失败时为 `false`。 |
| `errorMessage` | `null` | 失败描述。 |

### 4.3 RAG cache 存储 scope

持久 RAG cache 默认位于：

```text
config/Tianshu/module/llm/ragCache/<world>/
```

全局 RAG cache 位于：

```text
config/Tianshu/module/llm/ragCache/global/
```

每个 cache 目录下按 RAG `uid` 分文件保存：

```text
manifest.txt
<safeUid>.bin
```

二进制文件内部带 namespace 校验。namespace 绑定当前 LLM 模型和 embedding 模型组合；模型组合变化后，旧 cache 会被忽略并等待重新索引。

---

## 5. 请求示例

### 5.1 世界记忆聊天

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "ax.chat.memory",
    0,
    0.7f,
    true,
    false,
    false,
    "CHAT",
    0,
    false,
    List.of(
        LLMPromptRequestPayload.ChunkPayload.message(List.of(
            LLMPromptRequestPayload.MessageItemPayload.system("你是一个 Minecraft 助手"),
            LLMPromptRequestPayload.MessageItemPayload.user("我的钻石镐在哪里？")
        )),
        LLMPromptRequestPayload.ChunkPayload.rag(
            "ax_world_memory",
            "相关记忆：",
            List.of(),
            true,
            true,
            1000
        )
    ),
    dialogueSessionId,
    "module.ax",
    requesterParticipantId,
    dialogueTurnId,
    LLMPromptRequestPayload.InferencePolicyPayload.followGlobal()
);
```

### 5.2 后台记忆压缩

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "ax.memory.compress",
    0,
    0.7f,
    false,
    false,
    false,
    "TASK",
    10,
    false,
    List.of(LLMPromptRequestPayload.ChunkPayload.message(List.of(
        LLMPromptRequestPayload.MessageItemPayload.system("你是记忆压缩器"),
        LLMPromptRequestPayload.MessageItemPayload.user(longText)
    ))),
    "",
    "module.ax",
    "",
    "",
    LLMPromptRequestPayload.InferencePolicyPayload.followGlobal()
);
```
