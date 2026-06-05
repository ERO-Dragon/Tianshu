# LLM 模块架构文档

## 1. 模块定位

LLM 模块是天枢 common 内的模型能力层，提供三个核心功能：

1. **LLM 请求**：接收协议运行时中的 `LLM_REQUEST` 能力请求，解析 payload，调用 libs 推理，返回结果
2. **RAG 缓存**：管理向量缓存，支持增量索引和检索，加速 RAG 场景
3. **缓存管理**：通过 `LLM_CACHE_MANAGE` 能力暴露缓存删除和查询，供外部模块管理记忆生命周期

额外的 agent 编排功能（记忆管理、profile 注入等）由调用方自行实现，LLM 模块不参与。

LLM 模块不负责：

- IA 仲裁与使用授权（由 ia 模块独立处理）
- NPC / Agent 业务身份判断
- UI / TTS 分发
- 记忆策略和长期记忆生命周期
- 其他模块的 RAG 知识库文件管理

核心边界：

| 模块 | 职责 |
|---|---|
| `function.llm` | 协议能力注册、请求解析、RAG 缓存、libs 调用、结果回传、缓存管理 |
| `function.ia` | 仲裁、session store、交互授权（独立于 LLM 模块） |
| `function.auxilium` / 其他调用方 | 业务 prompt 组装、通过 `LLM_REQUEST` 能力发起请求、通过 `LLM_CACHE_MANAGE` 管理缓存 |
| JavaLlamaServer (libs) | 本地推理、embed、chat/task lane |

---

## 2. 总体链路

```text
调用方
  -> requestCapability(LLM_REQUEST)
  -> ProtocolRuntime
  -> LlmModule.handleLLMRequest
  -> LlmProtocolAdapter.handleLLMRequest
  -> LLMService.chat / chatStream / submitTask / submitTaskStream
  -> JavaLlamaServer (libs)
  -> respondTo(LLM_PROMPT_RESULT / LLM_PROMPT_STREAM_CHUNK)
```

```text
调用方
  -> requestCapability(LLM_CACHE_MANAGE)
  -> ProtocolRuntime
  -> LlmModule.handleLLMCacheManage
  -> LlmProtocolAdapter.handleLLMCacheManage
  -> LLMService.evictCache / hasCache
  -> respondTo(LLM_CACHE_MANAGE_RESULT)
```

---

## 3. 主要组件

| 组件 | 职责 |
|---|---|
| `LlmModule` | 模块生命周期管理，注册 `LLM_REQUEST` + `LLM_CACHE_MANAGE` 能力，初始化 `LLMService` |
| `LlmProtocolAdapter` | 封装协议收发：能力注册、请求分发、流式/同步响应、缓存管理响应 |
| `LLMService` | 核心业务服务：请求预处理、RAG 编排、libs 调用、缓存管理 |
| `LlmEngineProvider` | 创建和管理 `JavaLlamaServer` 实例，异步启动/停止 |
| `PersistentRagCacheManager` | 持久化 RAG 向量缓存，存储到 `config/Tianshu/module/llm/cache/` |
| `DefaultRagCacheManager` | 内存 RAG 向量缓存（测试/轻量场景） |
| `VectorStore` | 共享向量存储，支持索引、检索、删除 |
| `LlmModuleService` | 模块运行状态管理（STOPPED/STARTING/RUNNING/FAILED） |
| `LlmModelService` | 模型下载、删除、查询管理 |

### 3.1 组件关系

```text
LlmModule
  ├── LlmProtocolAdapter ── 协议层
  │     ├── registerLLMRequestCapability
  │     └── registerLLMCacheManageCapability
  ├── LLMService ── 业务层
  │     ├── LibsEmbeddingAdapter (内部类)
  │     ├── LLMResult (内部 record)
  │     └── RagCacheManager (PersistentRagCacheManager / DefaultRagCacheManager)
  │           └── VectorStore
  ├── LlmEngineProvider ── 引擎层
  │     └── JavaLlamaServer (libs)
  ├── LlmModuleService ── 状态层
  └── LlmModelService ── 模型管理层
```

---

## 4. 请求处理流程

### 4.1 同步 Chat 请求

```text
LLMPromptRequestPayload (stream=false, lane=CHAT)
  -> LlmProtocolAdapter.handleLLMRequest
  -> toLLMRequest(payload)
  -> LLMService.chat(request)
     -> prepareRequest: 按 chunks 顺序处理，message 直接展开，rag 检索后以 system message 插入
     -> buildLibsMessages: 转换为 ChatMessage 列表
     -> JavaLlamaServer.chat(messages, sampler, maxTokens)
     -> 返回 LLMResult(text, ragHits)
  -> respondTo(LLM_PROMPT_RESULT, completed(text, ragHits))
```

### 4.2 流式 Chat 请求

```text
LLMPromptRequestPayload (stream=true, lane=CHAT)
  -> LlmProtocolAdapter.handleLLMRequest
  -> toLLMRequest(payload)
  -> LLMService.chatStream(request, onToken, ragHits)
     -> prepareRequest: 按 chunks 顺序处理
     -> JavaLlamaServer.chatStream(messages, sampler, onToken)
        -> 每个 token: respondTo(LLM_PROMPT_STREAM_CHUNK, chunk)
     -> 结束: respondTo(LLM_PROMPT_STREAM_CHUNK, end)
  -> respondTo(LLM_PROMPT_RESULT, completed(text, ragHits))
```

### 4.3 同步 Task 请求

```text
LLMPromptRequestPayload (stream=false, lane=TASK)
  -> LLMService.submitTask(request)
  -> JavaLlamaServer.task(messages, sampler, maxTokens, priority, preemptible)
  -> CompletableFuture<String>
  -> respondTo(LLM_PROMPT_RESULT, completed(text))
```

### 4.4 流式 Task 请求

```text
LLMPromptRequestPayload (stream=true, lane=TASK)
  -> LLMService.submitTaskStream(request, onToken, ragHits)
  -> JavaLlamaServer.taskStream(messages, sampler, maxTokens, priority, preemptible, onToken)
  -> 每个 token: respondTo(LLM_PROMPT_STREAM_CHUNK, chunk)
  -> 结束: respondTo(LLM_PROMPT_STREAM_CHUNK, end)
  -> respondTo(LLM_PROMPT_RESULT, completed(text, ragHits))
```

### 4.5 Chunks 混排处理

`prepareRequest` 按 chunks 列表顺序处理，支持 message 和 rag 块任意混排：

```text
chunks: [message, rag, message, rag, ...]
  -> 遍历 chunks:
     ├── message chunk: 直接展开为 MessageItem 列表
     └── rag chunk: 检索 → 以 system message 插入到当前位置
  -> 最终有序消息列表: [system, user, system(rag), user, system(rag), ...]
```

RAG 检索结果以 **system role** 插入到对应 rag chunk 的位置，保持与 chunks 编排顺序一致。

### 4.6 RAG 编排

```text
LLMRequest 中的 rag chunk
  -> 提取最后一条 user message 作为检索 query
  -> 判断 useCache
     ├── useCache=true:  ragCache.index + ragCache.search (LLM 层缓存向量)
     └── useCache=false: JavaLlamaServer.search (libs 直接检索)
  -> 收集 ragHits（每个 rag chunk 返回一个 RagHitPayload: uid + List<HitEntry>）
  -> buildRagPrompt: 拼接检索结果
     ├── 有 prompt: prompt\n1. 片段1\n2. 片段2
     └── 无 prompt: 1. 片段1\n2. 片段2
  -> 以 system message 插入到 chunks 顺序对应位置
  -> 组装最终 messages 发送推理
```

RAG 检索结果同时做两件事：
1. 以 system role 注入 prompt 增强 LLM 上下文
2. 通过 `ragHits` 字段返回给调用方，供调用方做后续处理

---

## 5. 协议能力

LLM 模块注册两个协议能力：

### 5.1 LLM_REQUEST

```text
ProtocolCapabilities.LLM_REQUEST = "LLM.REQUEST"
PayloadType.LLM_PROMPT_REQUEST
PayloadType.LLM_PROMPT_RESULT
PayloadType.LLM_PROMPT_STREAM_CHUNK
```

| 方向 | PayloadType | 说明 |
|---|---|---|
| 调用方 → LLM | `LLM_PROMPT_REQUEST` | 请求体，包含 chunks |
| LLM → 调用方 | `LLM_PROMPT_STREAM_CHUNK` | 流式 token（仅 stream=true） |
| LLM → 调用方 | `LLM_PROMPT_RESULT` | 最终结果（completed/failed），含 ragHits |

### 5.2 LLM_CACHE_MANAGE

```text
ProtocolCapabilities.LLM_CACHE_MANAGE = "LLM.CACHE_MANAGE"
PayloadType.LLM_CACHE_MANAGE
PayloadType.LLM_CACHE_MANAGE_RESULT
```

| 方向 | PayloadType | 说明 |
|---|---|---|
| 调用方 → LLM | `LLM_CACHE_MANAGE` | 缓存管理请求（EVICT_ALL / EVICT_CONTENT / QUERY） |
| LLM → 调用方 | `LLM_CACHE_MANAGE_RESULT` | 操作结果（success / exists / errorMessage） |

---

## 6. 缓存机制

### 6.1 缓存定位

LLM 模块的缓存是**向量缓存**，用于加速 RAG 检索。它只存储经过 embed 后的向量数据，不存储其他模块的 RAG 知识库文件。

其他模块的 RAG 知识库文件（静态知识、长期记忆等）由各模块自行管理，LLM 不参与。

### 6.2 缓存路径

持久化缓存存储在 `config/Tianshu/module/llm/cache/`，由 `LlmModule` 通过 `config.getLlmBasePath().resolve("cache")` 传入 `PersistentRagCacheManager`。

### 6.3 缓存结构

每个 uid 对应一个 `.bin` 文件和一个全局 `manifest.txt`：

```text
cache/
  manifest.txt          ← 所有已索引 uid 列表
  <uid_sanitized>.bin   ← 向量数据（文本 + float[] 交替存储）
```

### 6.4 向量存储

`VectorStore` 是共享的向量存储实现，支持：

- `addAll(texts, vectors)` — 批量添加
- `search(queryVector, topK, threshold)` — 余弦相似度检索
- `remove(content)` — 按内容删除
- `size()` / `isEmpty()` — 状态查询

### 6.5 缓存检索流程

当调用方发送带 `useCache=true` 的 rag chunk 时：

```text
1. 调用方发送 rag chunk (uid="agent_001", prompt="以下是该NPC的相关记忆：", ragContent=["记忆1","记忆2"], useCache=true)
2. LLMService.processRagChunk()
   ├── ragCache.index("agent_001", ["记忆1","记忆2"])  ← embed 后存向量到缓存
   └── ragCache.search("agent_001", queryText, 4, 0.7) ← 用用户消息检索缓存向量
3. 返回 List<RagSearchResult>（命中的文本片段 + 相似度分数）
4. 收集 ragHits（每个 rag chunk 返回一个 RagHitPayload: uid + List<HitEntry>）
5. buildRagPrompt() ← 拼接检索结果：
   "以下是该NPC的相关记忆：\n1. 记忆1\n2. 记忆2"
6. 以 system message 插入到 chunks 顺序对应位置
7. JavaLlamaServer.chat(messages) ← 用增强后的 messages 推理
8. 返回 LLMResult(text, ragHits) ← LLM 回复 + RAG 命中记录
```

**关键点**：
- RAG 检索结果以 **system role** 注入，保持与 chunks 编排顺序一致
- 有 `prompt` 时：`prompt\n1. 片段1\n2. 片段2`
- 无 `prompt` 时：`1. 片段1\n2. 片段2`（直接序号片段）
- 同时通过 `ragHits` 返回命中记录，供调用方做后续处理（如记忆管理）
- 调用方最终拿到的 `text` 是 LLM 的回复，`ragHits` 是每个 rag chunk 的命中详情

如果调用方已经索引过内容（同一 uid 之前已调用），可以传空 `ragContent`，LLM 会直接用缓存的向量检索，不再重复索引。

### 6.6 无缓存检索

当 `useCache=false` 时，不经过 LLM 层缓存，直接调用 `JavaLlamaServer.search()` 检索。每次请求都需要传入完整的 `ragContent`。

### 6.7 缓存管理

外部模块通过 `LLM_CACHE_MANAGE` 能力管理缓存生命周期：

| 操作 | action | 说明 |
|---|---|---|
| 删除指定 uid 的全部缓存 | `EVICT_ALL` | 清除该 uid 下所有向量数据 |
| 删除指定 uid 的指定内容 | `EVICT_CONTENT` | 按 content 文本精确匹配删除 |
| 查询缓存是否存在 | `QUERY` | 检查指定 uid 是否有缓存数据 |

典型场景：记忆管理模块在删除某条记忆后，通过 `EVICT_CONTENT` 同步删除 LLM 缓存中的对应向量。

### 6.8 Embedding 适配

`LibsEmbeddingAdapter` 封装 `JavaLlamaServer` 的 embed 方法：

- `embed(text)` — 单文本向量化
- `embed(texts)` — 批量文本向量化
- `getEmbeddingDimension()` — 返回缓存维度（首次 embed 后自动缓存，未缓存时返回 -1）

维度信息由 lib 侧从模型获取后主动设置，LLM 层不做 probe 探测。

---

## 7. 模块生命周期

```text
register()
  ├── 创建 LlmModuleService、LlmModelService
  ├── 注册 LlmModuleService / LlmModelService 到 ServiceRegistry
  ├── 注册 LLM_REQUEST 能力
  └── 注册 LLM_CACHE_MANAGE 能力

prepare()
  ├── 标记 capabilities INSTALLED
  ├── 检查 AI 服务可用性
  ├── 构建 LLMService
  ├── 注册 LLMService 到 ServiceRegistry
  └── 异步启动 JavaLlamaServer
      ├── 成功 → markReady + 模块就绪
      └── 失败 → markFailed

start()
  └── LlmModuleService.load()

stop()
  ├── LLMService.shutdown()
  └── LlmModuleService.unload()

destroy()
  ├── stop()
  ├── LlmEngineProvider.stop()
  └── 移除 capabilities
```

---

## 8. 运行时能力

LLM 模块注册两个运行时能力：

```text
LlmRuntimeCapabilities.LLM_REQUEST = "capability.llm.request"
LlmRuntimeCapabilities.LLM_CACHE_MANAGE = "capability.llm.cache_manage"
```

能力状态流转：

```text
INSTALLED → READY (AI 服务启动成功)
INSTALLED → FAILED (AI 服务不可用或启动失败)
```

---

## 9. 失败原则

| 场景 | 行为 |
|---|---|
| LLM 服务未初始化 | 返回 `LLM_SERVICE_NOT_READY` |
| 请求解析失败 | 返回 `LLM_REQUEST_FAILED` |
| 推理调用失败 | 返回 `LLM_INFERENCE_FAILED` |
| AI 服务不可用 | 能力标记 FAILED，模块标记 FAILED |
| RAG 缓存读写失败 | 记录错误日志，不中断主流程 |
| 缓存管理操作失败 | 返回 `LLMCacheManageResultPayload.failed` |

---

## 10. 设计决策

| 决策项 | 结论 |
|---|---|
| LLM 层职责 | 请求处理 + RAG 缓存 + 缓存管理，不参与 agent 编排 |
| 协议接入方式 | Capability（LLM_REQUEST + LLM_CACHE_MANAGE），不注册 Topic |
| 流式响应方式 | 通过 `respondTo` 发送 STREAM_CHUNK，不需要 Topic |
| 缓存归属 | LLM 层负责向量缓存，libs 只提供 embed |
| RAG 知识库 | 由各调用方模块自行管理，LLM 不参与 |
| Chunks 混排 | 支持 message 和 rag 块任意顺序混排，按顺序处理 |
| RAG 注入角色 | 以 system role 注入，保持与 chunks 编排顺序一致 |
| RAG 注入格式 | 有 prompt 时 `prompt\n1. 片段`，无 prompt 时直接 `1. 片段` |
| RAG 命中返回 | 每个 rag chunk 返回一个 RagHitPayload(uid + List<HitEntry>) |
| 缓存生命周期 | 外部模块通过 LLM_CACHE_MANAGE 管理删除，LLM 不主动清理 |
| embed 维度 | 由 lib 侧从模型获取，LLM 层缓存首次调用结果 |
| IA 授权 | 不在 LLM 模块内处理，由 ia 模块独立管理 |
| 缓存路径 | `config/Tianshu/module/llm/cache/` |
| libs API | chat / chatStream / task / taskStream |
