# LLM 协议接口设计

## 1. 概述

外部模块不直接调用 `LLMService`。AX、IA 或其他模块需要 LLM 能力时，只通过协议中心和 LLM 模块通信。

`LLMRequest` 是 `LlmProtocolAdapter` 转入 LLM 模块内部后使用的实现对象，不属于外部模块协议接口。外部模块只需要构造本文件中的 protocol payload。

LLM 模块对外提供三个协议能力：

| 能力 | Payload | 说明 |
|------|---------|------|
| `ProtocolCapabilities.LLM_REQUEST` | `LLMPromptRequestPayload` | 发起聊天、流式聊天、后台 TASK |
| `ProtocolCapabilities.LLM_CACHE_MANAGE` | `LLMCacheManagePayload` | 管理、查询持久 RAG 条目；提供无副作用内联 RAG 召回 |
| `ProtocolCapabilities.LLM_PRIMITIVE_QUERY` | `LLMPrimitiveQueryPayload` | token 计数、embedding 向量化、运行态快照 |

响应类型：

| 请求能力 | 响应 Payload | 说明 |
|----------|--------------|------|
| `ProtocolCapabilities.LLM_REQUEST` | `LLMPromptResultPayload` | 最终结果，包含状态、文本、RAG hits、usage |
| `ProtocolCapabilities.LLM_REQUEST` + `stream=true` | `LLMPromptStreamChunkPayload` | 流式 token 分片；结束包包含 terminal 状态和 usage，随后还有最终 `LLMPromptResultPayload` |
| `ProtocolCapabilities.LLM_CACHE_MANAGE` | `LLMCacheManageResultPayload` | cache 管理结果 |
| `ProtocolCapabilities.LLM_PRIMITIVE_QUERY` | `LLMPrimitiveResultPayload` | 原语查询结果，按请求类型返回 token、vector 或状态快照 |

---

## 2. `ProtocolCapabilities.LLM_REQUEST`

外部模块向 `ProtocolCapabilities.LLM_REQUEST` 提交 `LLMPromptRequestPayload`。

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "ax.chat",
    0,
    null,
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
| `temperature` | `null` | 采样温度覆盖值；`null` 表示沿用当前模型目录 JSON 的 COT 开 / 关默认值。 |
| `topK` / `topP` / `minP` | `null` | 采样截断覆盖值；调用方只传哪个字段就只覆盖哪个字段。 |
| `penaltyRepeat` / `penaltyFreq` / `penaltyPresent` / `penaltyLastN` | `null` | repetition / frequency / presence 等惩罚参数覆盖值；未传字段继续沿用 JSON 默认或底层默认。 |
| `stream` | `false` | 是否流式返回。`true` 时会先收到若干 `LLM_PROMPT_STREAM_CHUNK`，最后收到 `LLM_PROMPT_RESULT`。 |
| `thinking` | `false` | 是否允许模型生成 thinking 内容。 |
| `captureThinkingContent` | `false` | 是否把底层识别出的 COT/thinking 内容写入结构化 `thinkingContent` 通道；正文 `text` 始终只包含正式回答。 |
| `lane` | `"CHAT"` | 执行通道：`CHAT` 或 `TASK`。非法值按 `CHAT` 处理。 |
| `taskPriority` | `0` | TASK 优先级，范围 `0..1000`；仅 `lane=TASK` 时参与调度。 |
| `taskPreemptible` | `false` | 当前 TASK 是否允许被更高优先级 TASK 抢占；仅 `lane=TASK` 时有效。 |
| `chunks` | 空列表 | 请求内容，按数组顺序处理。支持 `message` 和 `rag` 两类 chunk。 |
| `dialogueSessionId` | 空字符串 | CHAT 请求的对话会话 ID；用于 IA 授权。 |
| `requesterModuleId` | 空字符串 | 请求方模块 ID，必须与协议 envelope 的 source module 一致。 |
| `requesterParticipantId` | 空字符串 | 请求方参与者 ID；用于 IA 授权。 |
| `dialogueTurnId` | 空字符串 | 对话轮次 ID，用于追踪一次对话回合。 |
| `inferencePolicy` | 跟随全局 | 请求级推理策略覆盖项。 |
| `toolsJson` | 空字符串 | OpenAI-style function tools JSON；LLM 模块只透传给底层模板，不解析输出、不执行工具。 |

CHAT 请求需要带对话授权上下文：`dialogueSessionId`、`requesterModuleId`、`requesterParticipantId`。LLM 模块会通过 IA 授权能力确认该调用方是否允许使用 CHAT LLM。

TASK 请求不走 CHAT 的 IA 授权，但会进入 LLM 模块的 TASK admission 队列。

采样参数的优先级是：底层 `SamplerConfig.defaults()` -> 当前模型 JSON 中按 `thinking` 选择的 `sampling.standard` / `sampling.thinking` -> 本次请求中非 null 的采样覆盖字段。覆盖是逐字段的，不会因为调用方传了一个采样参数就替换整套采样配置。

`thinking` 和 `captureThinkingContent` 是两个不同边界：
- `thinking=false`：请求模型不要生成 thinking，LLM 模块会传给底层 sampler。
- `thinking=true + captureThinkingContent=false`：允许模型生成 thinking；底层仍会从正文中剥离 COT，但不向上保存 thinking 内容。
- `thinking=true + captureThinkingContent=true`：协议响应通过 `thinkingContent` 返回结构化 COT，正文 `text` 仍不混入 thinking。

### 2.2 ChunkPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `type` | `"message"` | chunk 类型：`message` 或 `rag`。 |
| `messageContent` | 空列表 | `type=message` 时使用，按顺序加入消息上下文。 |
| `ragContent` | 空列表 | `type=rag` 时使用。非空时可被增量索引进 RAG cache。 |
| `uid` | 空字符串 | RAG 库标识；LLM 只按 uid 分桶，不理解世界、全局、模块、聚类等上层语义。 |
| `prompt` | 空字符串 | RAG 命中内容注入模型前的提示前缀。 |
| `useCache` | `true` | `true` 使用 LLM 模块持久 RAG cache；`false` 仅对本次 `ragContent` 走 libs 内联检索。 |
| `includeRagHits` | `true` | 是否在最终 `LLMPromptResultPayload` 中返回命中的 RAG 内容。 |
| `memoryRagTokenBudget` | `1000` | 本 chunk 注入模型的 RAG 文本预算。 |

`message` chunk 示例：

```java
LLMPromptRequestPayload.ChunkPayload.message(List.of(
    LLMPromptRequestPayload.MessageItemPayload.system("你是一个 Minecraft 助手"),
    LLMPromptRequestPayload.MessageItemPayload.user("我的钻石镐在哪里？")
));
```

RAG chunk 示例：

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

### 2.3 MessageItemPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `role` | `"user"` | 消息角色：`system`、`user`、`assistant`。 |
| `content` | 空字符串 | 消息正文。 |

消息编排规则：
- 底层只支持一个 `system` role。LLM 模块会合并开头连续的 `system` 消息，并作为唯一首条 `system` 发送。
- 对话开始后出现调用方提供的 `system` 消息会被拒绝，错误码语义为 `LLM_UNSUPPORTED_SYSTEM_POSITION`。
- RAG chunk 生成的检索提示属于 LLM 内部编排，会并入首条 `system`，不会在中间额外插入 `system` 消息。

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
| `thinkingContent` | 空字符串 | 当请求设置 `captureThinkingContent=true` 时返回结构化 thinking/COT 内容；不会混入 `text`。 |
| `errorCode` | `null` | 失败原因码。 |
| `errorMessage` | `null` | 失败原因描述。 |
| `ragHits` | 空列表 | 本次请求所有 RAG chunk 的命中结果；`COMPLETED`、`CANCELLED`、`FAILED` 都可能携带。 |
| `usage` | 空 usage | 本次实际 token 统计：`promptTokens`、`completionTokens`、`thinkingTokens`、`outputTokens`、`totalTokens`。 |

`usage.promptTokens` 来自底层真实 chat template prompt token 计数。`usage.completionTokens` 只统计正式正文 token；`thinkingTokens` 统计被识别到结构化 thinking 通道的 token；`outputTokens = completionTokens + thinkingTokens`。

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
| `thinkingContent` | 空字符串 | 当前分片的 thinking/COT 文本；普通正文分片为空。 |
| `finished` | `false` | `false` 表示普通 token 分片；`true` 表示流式输出结束。 |
| `index` | 调用方传入值 | 分片序号，调用方可按序拼接。 |
| `ragHits` | 空列表 | 流式请求的 RAG metadata。LLM 模块会在首个 token 前发送一个 `text=""` 且 `ragHits` 非空的 metadata chunk。 |
| `finishType` | 空字符串 | 仅 `finished=true` 时有效：`COMPLETED`、`CANCELLED`、`FAILED`。 |
| `usage` | 空 usage | 仅结束包携带本次实际 token 统计。 |
| `errorMessage` | `null` | 仅 `finishType=FAILED` 时携带底层错误信息。 |

流式请求使用结构化双通道：正文 token 放在 `text`；thinking token 放在 `thinkingContent`。结束包会携带本次汇总后的 `thinkingContent`，最终 `LLMPromptResultPayload` 也会带同一语义的汇总结果。

### 2.7 RagHitPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `uid` | 空字符串 | 命中的 RAG 库 uid。 |
| `hits` | 空列表 | 命中条目列表。 |

`hits` 中每个条目包含 `entryId`、`content`、`score`。多个 RAG chunk 命中不同库时，会按请求处理顺序返回多个 `RagHitPayload`；调用方按 `uid` 识别对应 RAG 库即可。

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
LLMCacheManagePayload.upsertEntry(
    "ax.memory.cluster.cluster-001",
    "event-001",
    "玩家把钻石镐放在末影箱",
    eventVector
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
        LLMCacheManagePayload.searchUid("ax.memory.cluster.cluster-001", "玩家问钻石镐在哪里", 4, 0.7f)
).build();

protocolRuntime.submit(envelope);
```

### 4.1 LLMCacheManagePayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `action` | `"QUERY_UID"` | 操作类型：`UPSERT_ENTRY`、`PATCH_ENTRY`、`DELETE_ENTRY`、`CLEAR_UID`、`QUERY_UID`、`REGISTER_LIBRARY`、`UNREGISTER_LIBRARY`、`SEARCH_UID`、`SEARCH_MODID`、`SEARCH_TAGS`、`SEARCH_INLINE_CONTENTS`。 |
| `uid` | 空字符串 | RAG 库标识。一个 uid 表示一个库/簇；调用方自行把世界、模块、层级等作用域编码进 uid。 |
| `modid` | 空字符串 | RAG 库所属 modid；用于 `REGISTER_LIBRARY` 和 `SEARCH_MODID`。同一个 modid 可以注册多个 uid。 |
| `visibility` | `"SHARED"` | RAG 库可见性：`SHARED` 可被 `modid/tag` 聚合检索发现；`PRIVATE` 不参与共享发现。 |
| `tags` | 空列表 | RAG 库标签；用于 `REGISTER_LIBRARY` 和 `SEARCH_TAGS`，例如 `main`、`addon`。 |
| `entryId` | 空字符串 | 条目 id，由调用方拥有；LLM 只存储和返回该 id。 |
| `content` | 空字符串 | 条目文本，可为空。为空时仍可依赖调用方传入的向量参与检索。 |
| `contents` | 空列表 | `SEARCH_INLINE_CONTENTS` 的请求内联候选文本列表；只参与本次检索，不写入持久 RAG cache。 |
| `vector` | 空向量 | 条目向量，可为空。为空且 `content` 非空时，LLM 会用当前 embedding 服务生成向量。 |
| `updateContent` | `false` | `PATCH_ENTRY` 时是否更新文本。 |
| `updateVector` | `false` | `PATCH_ENTRY` 时是否更新向量。 |
| `queryText` | 空字符串 | `SEARCH_UID`、`SEARCH_MODID`、`SEARCH_TAGS`、`SEARCH_INLINE_CONTENTS` 时的查询文本。 |
| `topK` | `4` | 每个 uid 返回条数。 |
| `threshold` | `0.7` | 命中阈值。 |

快捷构造：

```java
LLMCacheManagePayload.registerLibrary("ax.memory.cluster.cluster-001", "tianshu", "PRIVATE", List.of("main"));
LLMCacheManagePayload.upsertEntry("ax.memory.cluster.cluster-001", "event-001", "玩家把钻石镐放在末影箱", eventVector);
LLMCacheManagePayload.patchEntry("ax.memory.cluster.cluster-001", "event-001", "更新后的事实文本", null, true, false);
LLMCacheManagePayload.deleteEntry("ax.memory.cluster.cluster-001", "event-001");
LLMCacheManagePayload.clearUid("ax.memory.cluster.cluster-001");
LLMCacheManagePayload.queryUid("ax.memory.cluster.cluster-001");
LLMCacheManagePayload.searchUid("ax.memory.cluster.cluster-001", "玩家问钻石镐在哪里", 4, 0.7f);
LLMCacheManagePayload.searchModid("botania", "魔力池怎么做", 4, 0.7f);
LLMCacheManagePayload.searchTags(List.of("main", "addon"), "钻石镐在哪里", 4, 0.7f);
LLMCacheManagePayload.searchInlineContents("module.example.inline", "钻石镐在哪里", candidateTexts, 4, 0.7f);
```

### 4.2 LLMCacheManageResultPayload 字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `action` | 空字符串 | 结果动作：`UPSERT_ENTRY`、`PATCH_ENTRY`、`DELETE_ENTRY`、`CLEAR_UID`、`QUERY_UID`、`REGISTER_LIBRARY`、`UNREGISTER_LIBRARY`、`SEARCH_UID`、`SEARCH_MODID`、`SEARCH_TAGS`、`SEARCH_INLINE_CONTENTS`、`FAILED`。 |
| `uid` | 空字符串 | 对应 RAG 库标识。 |
| `entryId` | 空字符串 | 对应条目 id；仅条目级操作返回。 |
| `success` | 调用方传入值 | 操作是否成功。 |
| `exists` | 调用方传入值 | `QUERY_UID` 时表示 uid 是否存在；检索时表示是否有命中。 |
| `hits` | 空列表 | 检索返回的命中组，按 uid 分组。每组包含 `uid`、`entries`、`score`；`entries` 元素为 `entryId`、`content`、`score`。 |
| `libraries` | 空列表 | 涉及的库元信息列表；元素为 `uid`、`modid`、`visibility`、`tags`。 |
| `errorMessage` | `null` | 失败描述。 |

### 4.3 RAG 库注册与检索语义

LLM 只理解“库”和“条目”：
- `uid` 是一个 RAG 库，可以对应一个簇、一个模组知识库或任意调用方定义的集合。
- `entryId` 是该 uid 内的条目 id，用于写入、patch、delete，以及检索命中后原样返回。
- `modid` 和 `tags` 只用于共享库发现；LLM 不校验 modid 真实性，依赖模组开发者自觉。
- 调用方只传自然语言 `queryText`，不传关键词。LLM 内部用统一 analyzer 对 `queryText` 和条目 `content` 做 term 化，再执行 BM25 + embedding hybrid retrieval。

共享检索规则：
- `SEARCH_UID`：只检索指定 uid。
- `SEARCH_MODID`：检索该 modid 下所有 `SHARED` 的 uid；同一个 modid 可以有多个 uid。
- `SEARCH_TAGS`：检索所有 `SHARED` 且命中任意 tag 的 uid。
- `SEARCH_INLINE_CONTENTS`：只检索请求携带的 `contents`，不注册库、不写入 cache、不触发生成。返回的 `entryId` 是输入列表中的稳定下标字符串。

BM25 与 embedding 边界：
- BM25 是 LLM RAG 内部的词法召回/排序组件，不要求上层传关键词。
- 默认 analyzer 支持英文/数字词与 CJK ngram，可匹配文本中实际出现的专有名词；同义词、别名、错字修复仍应由 IR/AX 等上游在形成 `queryText` 前完成。
- 持久 RAG 只落盘条目 `entryId`、`content`、`vector`；BM25 倒排索引属于可重建运行态索引，会在加载 cache 或写入变更时由 `content` 重建，不单独持久化。
- 持久 RAG 在 `upsert` / `patch` / `delete` 时维护 BM25 倒排索引；查询时 BM25 与 query embedding 并行执行，默认按 embedding 70% / BM25 30% 融合，并按可用信号重新归一。
- `SEARCH_MODID` / `SEARCH_TAGS` 命中多个 uid 时，同一次请求只生成一次 query embedding，并复用到各 uid 检索。
- 如果 query embedding 暂不可用，检索会退化为 BM25-only；如果 BM25 无命中，仍可使用 vector-only。
- 只有向量、没有文本的条目不会进入 BM25，只参与 vector-only 检索；同时有文本和向量的条目参与 hybrid retrieval；只有文本、没有向量的条目会在写入时生成向量，生成失败时仍可参与 BM25-only。

AX 记忆检索示例：
- 已知某个二级簇时：`uid = ax.memory.cluster.<clusterId>`，`entryId = eventId`，`content = E.factText`。
- LLM 返回命中的 `entryId` 后，AX 用 eventId 回到自己的事件存储取完整结构。

### 4.4 RAG cache 存储

持久 RAG cache 默认位于：

```text
config/Tianshu/module/llm/ragCache/entries/
```

目录下按 RAG `uid` 分文件保存：

```text
manifest.txt
<safeUid>.bin
```

二进制文件内部带 namespace 校验。namespace 绑定当前 LLM 模型和 embedding 模型组合；模型组合变化后，旧 cache 会被忽略并等待重新索引。

持久化写入策略：
- `UPSERT_ENTRY`、`PATCH_ENTRY`、`DELETE_ENTRY` 会先更新内存中的 RAG 条目、向量索引和 BM25 索引，检索立即可见。
- 磁盘写入按 `uid` 标记 dirty，并做约 1 秒 debounce 合并 flush；同一 uid 的高频写入会合并为一次整库文件重写。
- `CLEAR_UID` 会立即移除内存条目并删除对应磁盘文件。
- LLM 服务 `shutdown` 会 flush 所有 dirty uid，降低 write-behind 的数据丢失窗口。

---

## 5. `ProtocolCapabilities.LLM_PRIMITIVE_QUERY`

外部模块向 `ProtocolCapabilities.LLM_PRIMITIVE_QUERY` 提交 `LLMPrimitiveQueryPayload`。

### 5.1 支持的 queryType

| queryType | 说明 |
|-----------|------|
| `TOKEN_COUNT` | 对文本或消息列表做真实 token 计数；不接受 `rag` chunk |
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
| `chunks` | 保留字段；`TOKEN_COUNT` 不接受 `rag` chunk，避免触发检索、索引或 cache 修改 |
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

`EMBED` 的每条结果包含 `text`、`dimension`、可选 `vector`、`embeddingModelName` 和 `embeddingNamespace`。`STATUS` 快照也包含 embedding 模型身份字段，用于 AX 校验持久化向量是否仍属于同一 embedding 空间。

`STATUS` 快照还会返回 LLM 运行能力与 ctx 预算事实：

| 字段 | 说明 |
|------|------|
| `supportsThinking` | 当前已加载模型 / chat template 是否支持 thinking 控制。 |
| `supportsMtp` / `supportsEmbeddedMtp` / `externalMtpAvailable` | 当前运行环境的 MTP 能力来源与可用性。 |
| `contextSize` | 当前已规划/加载的 ctx；优先来自底层 plan，不再只代表配置期望值。 |
| `contextTokenBudget` | 当前已加载模型实例的最终安全输入 token 预算；已扣除底层 prompt margin，不是 CHAT / TASK lane 预算。 |

上层做 prompt 预算时只消费 `contextTokenBudget`。LLM 模块不规划调用方的 prompt 分区，也不把底层 dryrun 的训练 ctx、显存 ctx 等诊断事实暴露成上层可消费协议；这些事实只属于 LLM 内部加载与安全预算判断。

`STATUS` 返回的快照应尽量保守：未知值可以返回 `-1` 或空字符串，不要硬猜。

---

## 6. 请求示例

### 6.1 世界记忆聊天

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

### 6.2 后台记忆压缩

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
