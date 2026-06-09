# LLM 层接口设计

## 1. 概述

LLM 层是主体的适配层，负责：
- 解析 LLMRequest（chunks）
- RAG 编排和缓存管理
- 调用 libs 底层能力

---

## 2. LLMRequest 请求结构

```java
public class LLMRequest {
    // 生成参数
    public Integer max_tokens;
    public Float temperature;
    public Boolean stream;
    public Boolean thinking;

    // 任务调度
    public String lane;               // "CHAT" / "TASK"
    public Integer task_priority;     // -1000 ~ 1000
    public Boolean task_preemptible;

    // 核心数据
    public List<Chunk> chunks;
}
```

`LLMRequest.thinking` 只表示是否让模型生成 thinking。跨模块协议里的 `LLMPromptRequestPayload.includeThinkingContent` 是对外响应展示开关，不属于 `LLMRequest` 内部请求结构；它由 `LlmProtocolAdapter` 在协议响应出口处理。

### 2.1 Chunk 分块

```java
public class Chunk {
    public String type;  // "message" / "rag"

    // type="message" 时
    public List<MessageItem> content;

    // type="rag" 时
    public List<String> content;       // RAG 文本数组
    public String uid;                  // 唯一标识
    public String prompt;               // 该 RAG 分块注入 prompt 前缀
    public Boolean use_cache;           // 是否使用缓存（默认 true）
    public Boolean include_rag_hits;    // 是否返回检索结果
    public Integer memory_rag_token_budget; // 记忆 RAG 预算
}
```

### 2.2 MessageItem

```java
public class MessageItem {
    public String role;    // "system" / "user" / "assistant"
    public String content;
}
```

---

## 3. API 使用方式

### 3.1 模块装配

实际运行时由 NeoForge 客户端层创建公共运行环境和世界身份 Provider，再通过 common 的模块装配器安装 LLM 模块：

```java
new ClientTianshuModuleAssembler(
    env,
    config,
    audioBridge,
    protocolRuntime,
    voiceInputGate,
    interruptionSignal,
    new NeoForgeAXWorldIdentityProvider(),
    worldStateProvider
);
```

LLM 模块内部职责：
- `LlmEngineProvider` 按配置创建并异步启动 `JavaLlamaServer`
- `LlmModule` 创建并注册 `LLMService`
- `LLMService` 负责请求编排、RAG 检索、缓存管理和 libs 调用
- 其他模块通过模块服务注册表获取 `LLMService`，或通过 `LLM_REQUEST` / `LLM_CACHE_MANAGE` 协议能力调用

`env` 是 common 层对游戏环境的抽象，包含日志、主线程执行、玩家消息、游戏目录等能力；它由 NeoForge 层注入，不是普通业务调用方需要手动设置的参数。`cacheNamespace` 和 `cacheDirectory` 也是 LLM 模块内部装配参数：namespace 绑定当前 LLM/embedding 模型组合，目录由当前世界 scope 自动决定。每个 RAG chunk 自己仍通过 `uid`、`prompt`、`use_cache`、`include_rag_hits`、`memory_rag_token_budget` 决定该分块的 RAG 行为。

### 3.2 同步聊天

```java
String reply = service.chat("你好", "你是铁匠NPC");

// 或使用 LLMRequest
LLMRequest request = LLMRequest.of(
    Chunk.message(
        MessageItem.of("system", "你是铁匠NPC"),
        MessageItem.of("user", "我需要一把剑")
    ),
    Chunk.rag("rag_dynamic", "相关上下文：", List.of("玩家手持铁锭"), true, true, 1000)
);
LLMService.LLMResult result = service.chat(request);
```

### 3.3 流式聊天

```java
service.chatStream(request, token -> {
    broadcast(token); // 实时推送
});
```

### 3.4 后台任务

```java
LLMRequest request = LLMRequest.of(
    Chunk.message(
        MessageItem.of("system", "你是记忆压缩器"),
        MessageItem.of("user", longText)
    )
);
request.setLane("TASK");
request.setTaskPriority(10);

CompletableFuture<String> future = service.submitTask(request);
```

流式后台任务必须以 `CompletableFuture` 完成为准：

```java
CompletableFuture<String> future = service.submitTaskStream(request, token -> {
    publish(token);
}, ragHits);
```

跨模块调用 `LLM_REQUEST` 时，TASK 会先进入 `LlmTaskAdmissionController`，再由 LLM 模块按优先级送入 `LLMService.submitTask*()`：

- CHAT 不进入 TASK admission 队列，仍直接走聊天通道，并由 IA 授权约束。
- TASK admission 默认只启动一个 active TASK；其余任务进入等待队列，容量来自 `ITianshuConfig.getLlmTaskAdmissionQueueSize()`。
- 如果当前 active TASK 的 `task_preemptible=true`，更高有效优先级的任务会立即送入 libs，由 libs 执行 TASK 抢占/取消/挂起语义。
- 等待队列按有效优先级降序、同优先级 FIFO；有效优先级 = `task_priority + 等待期间新 TASK 请求次数 * getLlmTaskAgingBoostPerRequest()`。
- 队列满时，高有效优先级任务可替换等待队列中的最低有效优先级任务；被抢占后未终态的旧 TASK 仍计入 in-flight 边界，避免继续 drain 等待队列导致隐形扩容。
- libs 的 `taskMaxQueueSize` 在天枢侧对应 `getLlmTaskHotSuspendSlots()`，语义是热挂起保存槽，不是外部排队容量。

协议层只有在该 future 完成后才发送 stream end 和最终 result，并完成对应 envelope。
当 libs 因 CHAT 优先调度暂停 TASK 时，future 保持未完成，协议层保持响应处理器有效；恢复后继续把后续 token 发给同一个请求方。
当 TASK 被取消、中断或终止型抢占时，协议层返回 `LLMPromptResultPayload.status=CANCELLED`，流式任务的 `text` 携带已发送的可见 partial text。

---

## 4. RAG 缓存管理

### 4.1 核心功能

```java
RagCacheManager cache = service.getRagCache();

// 增量索引
cache.index("rag_memory_001", List.of("新记忆1", "新记忆2"));

// 检索
List<RagSearchResult> results = cache.search("rag_memory_001", "我的钻石镐在哪", 4, 0.7f);

// 删除
cache.evict("rag_memory_001");                    // 删除某 uid
cache.evict("rag_memory_001", "具体记忆内容");    // 删除单条

// 查询
boolean hasCache = cache.hasCache("rag_memory_001");
CacheStats stats = cache.getStats();

// 清空
cache.clear();
```

### 4.2 RagCacheManager 接口

LLM 层基于 libs 的 `embed()` 实现缓存（存储向量），检索时用缓存的向量自己计算相似度。

```java
public interface RagCacheManager {
    // 增量索引（自动向量化并缓存）
    void index(String uid, List<String> texts);

    // 基于 uid 检索（使用缓存的向量）
    List<RagSearchResult> search(String uid, String queryText, int topK, float threshold);

    // 删除
    void evict(String uid);
    void evict(String uid, String content);

    // 查询
    boolean hasCache(String uid);
    CacheStats getStats();

    // 清空
    void clear();
}

public class CacheStats {
    public int uidCount;
    public int totalChunks;
    public long cacheSizeBytes;
}
```

持久缓存采用带版本头的二进制格式，包含 magic、version、namespace、向量维度和条目数。namespace 应绑定当前 LLM/embedding 模型组合；加载时若版本、namespace、维度或向量值非法，缓存会被忽略并等待后续重新索引。

缓存目录按世界隔离，当前实现为：

```text
config/Tianshu/module/llm/<worldId>/cache
```

`worldId` 由世界身份 Provider 自动生成：NeoForge 层可提供单机世界名或服务器地址，common 层会将稳定身份哈希成安全目录名，例如 `local_<hash>` / `server_<hash>`。这里不直接使用原始世界名，目的是避免特殊字符、重命名和服务器地址泄露导致的路径问题。

`index(uid, texts)` 是增量 upsert：同一 uid 下相同 content 不会重复堆积；已经存在且内容未变化时不会再次 embedding，也不会重写向量文件或 manifest。只有新增 content、删除 content、清空或模型 namespace 不兼容后重建索引时才会写磁盘。

---

## 5. 字段默认值

| 字段 | 默认值 |
|------|--------|
| `max_tokens` | 0 (不限制) |
| `temperature` | 0.7 |
| `stream` | false |
| `thinking` | false |
| 协议 `includeThinkingContent` | false |
| `lane` | "CHAT" |
| `task_priority` | 0 |
| `task_preemptible` | false |
| `use_cache` | true |
| `include_rag_hits` | true |
| `memory_rag_token_budget` | 1000 |

---

## 6. RAG 处理流程

```
LLMRequest
    ↓
解析 chunks
    ↓
┌─ message chunks ──→ 组装 messages
│
└─ rag chunks ──→ 判断 use_cache
                      ↓
           ┌──────────┴──────────┐
           ↓                      ↓
       use_cache=true         use_cache=false
           ↓                      ↓
    ragCache.search()      libs.search(queryText, texts, topK)
    (用缓存向量)            (无缓存，直接检索)
           ↓                      ↓
           └──────────┬──────────┘
                      ↓
                 组装 prompt（注入 RAG 结果）
                      ↓
                 libs.chat(messages) → 推理结果
```

**说明**：
- `use_cache=true`：LLM 层用缓存的向量自己计算相似度
- `use_cache=false`：直接调用 libs.search() 检索
- chunks 按请求数组顺序处理；message chunk 会按内部 message 顺序追加，rag chunk 的检索结果会在出现位置注入为 system message，因此多 chunk 的 prompt 组装顺序保持请求声明顺序
- RAG 注入会按 `memory_rag_token_budget` 做预算裁剪，返回的 `ragHits` 与实际注入给模型的内容保持一致
- `thinking=false` 会显式传给 libs 的 `SamplerConfig.enableThinking=false`，避免模型模板默认进入 thinking
- 协议层默认隐藏 libs 规范化后的 `<think>...</think>` 内容；只有 `LLMPromptRequestPayload.includeThinkingContent=true` 时才随 stream/result 暴露

---

## 7. 示例

### 7.1 请求示例

```json
{
  "max_tokens": 1024,
  "temperature": 0.7,
  "stream": true,
  "thinking": false,
  "lane": "CHAT",
  "chunks": [
    {
      "type": "message",
      "content": [
        {"role": "system", "content": "你是一个 Minecraft 助手"},
        {"role": "user", "content": "我的钻石镐在哪里？"}
      ]
    },
    {
      "type": "rag",
      "content": ["玩家手持钻石镐", "坐标 (100,64,200)"],
      "uid": "rag_dynamic_001",
      "use_cache": true,
      "include_rag_hits": true,
      "memory_rag_token_budget": 2000
    }
  ]
}
```

### 7.2 Java 使用示例

```java
LLMRequest request = LLMRequest.of(
    Chunk.message(
        MessageItem.of("system", "你是一个 Minecraft 助手"),
        MessageItem.of("user", "我的钻石镐在哪里？")
    ),
    Chunk.rag(
        "rag_dynamic_001",
        "相关上下文：",
        List.of("玩家手持钻石镐", "坐标 (100,64,200)"),
        true, true, 2000
    )
);
request.setStream(true);

service.chatStream(request, token -> publish(token));
```

---

## 8. 设计决策

| 决策项 | 结论 |
|--------|------|
| LLM 层职责 | 业务编排：chunk 解析、RAG 编排、缓存管理 |
| 缓存归属 | LLM 层负责，libs 只提供 embed |
| 有缓存场景 | 使用 embed() 获取向量，LLM 层自己计算相似度 |
| 无缓存场景 | 直接调用 libs.search() 检索 |
| 持久缓存 | 按 worldId 分目录，带版本和 namespace 校验，namespace 绑定模型组合 |
| 磁盘写入 | 相同 uid/content 已缓存时跳过 embedding 和写盘；manifest 只有 uid 集合变化时才更新 |
| TASK 生命周期 | 以 libs 返回的 CompletableFuture 完成为准，不在协议 handler 返回时提前完成 |
| TASK 接收队列 | LLM 模块通过 admission controller 管理；默认单 active，可抢占 active 允许更高有效优先级任务进入 libs |
| TASK 暂停 | 暂停不结束协议流；恢复后继续向同一个响应处理器发送 chunk |
| TASK 终止取消 | 取消、中断或终止型抢占返回 `CANCELLED`，流式结果保留 partial text |
| 文本保真 | LLM result 和 stream token 不 trim，避免破坏代码块/换行/空格 |
| Thinking 暴露 | `thinking` 控制生成；协议 `includeThinkingContent` 控制是否向调用方暴露 `<think>...</think>` |
