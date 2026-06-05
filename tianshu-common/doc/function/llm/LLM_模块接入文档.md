# LLM 模块接入文档

## 1. 接入入口

所有外部模块调用 LLM 都走协议能力：

**LLM 请求：**

```text
ProtocolCapabilities.LLM_REQUEST = "LLM.REQUEST"
PayloadType.LLM_PROMPT_REQUEST
LLMPromptRequestPayload
```

**缓存管理：**

```text
ProtocolCapabilities.LLM_CACHE_MANAGE = "LLM.CACHE_MANAGE"
PayloadType.LLM_CACHE_MANAGE
LLMCacheManagePayload
```

调用方不直接访问 `JavaLlamaServer`，也不直接 import `function.llm.*` 内部类。LLM 的流式输出和最终结果通过 `respondTo` 绑定原始请求 envelope 返回。

---

## 2. 请求结构

### 2.1 LLMPromptRequestPayload

```java
public record LLMPromptRequestPayload(
    String requestId,           // 请求唯一标识
    Integer maxTokens,          // 最大生成 token 数（0 = 不限制）
    Float temperature,          // 温度（0.0 ~ 2.0，默认 0.7）
    Boolean stream,             // 是否流式（默认 false）
    Boolean thinking,           // 是否启用思考模式（默认 false）
    String lane,                // 通道："CHAT" / "TASK"（默认 CHAT）
    Integer taskPriority,       // 任务优先级（-1000 ~ 1000，默认 0）
    Boolean taskPreemptible,    // 任务是否可抢占（默认 false）
    List<ChunkPayload> chunks   // 请求内容块（按顺序处理）
)
```

### 2.2 ChunkPayload

chunks 列表中的元素**按顺序处理**，支持 message 和 rag 块任意混排：

```java
public record ChunkPayload(
    String type,                            // "message" / "rag"

    // type="message" 时
    List<MessageItemPayload> messageContent, // 消息列表

    // type="rag" 时
    String uid,                              // 缓存唯一标识
    String prompt,                           // RAG 注入前缀（默认空，无前缀）
    List<String> ragContent,                 // RAG 文本数组
    Boolean useCache,                        // 是否使用缓存（默认 true）
    Boolean includeRagHits,                  // 是否返回检索结果（默认 true）
    Integer memoryRagTokenBudget             // 记忆 RAG 预算（默认 1000）
)
```

### 2.3 MessageItemPayload

```java
public record MessageItemPayload(
    String role,      // "system" / "user" / "assistant"
    String content    // 文本内容
)
```

---

## 3. 发起 LLM 请求

### 3.1 同步聊天

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "chat-" + turnId,
    512,                // maxTokens
    0.7f,               // temperature
    false,              // stream
    false,              // thinking
    "CHAT",             // lane
    0,                  // taskPriority
    false,              // taskPreemptible
    List.of(
        LLMPromptRequestPayload.ChunkPayload.message(List.of(
            LLMPromptRequestPayload.MessageItemPayload.system("你是一个 Minecraft 助手"),
            LLMPromptRequestPayload.MessageItemPayload.user("我的钻石镐在哪里？")
        ))
    )
);

adapter.requestCapability(
    ProtocolCapabilities.LLM_REQUEST,
    PayloadType.LLM_PROMPT_REQUEST,
    payload
);
```

### 3.2 流式聊天

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "stream-" + turnId,
    512,
    0.7f,
    true,               // stream = true
    false,
    "CHAT",
    0,
    false,
    List.of(
        LLMPromptRequestPayload.ChunkPayload.message(List.of(
            LLMPromptRequestPayload.MessageItemPayload.system("你是铁匠NPC"),
            LLMPromptRequestPayload.MessageItemPayload.user("我需要一把剑")
        ))
    )
);

adapter.requestCapability(
    parentEnvelope,
    ProtocolCapabilities.LLM_REQUEST,
    PayloadType.LLM_PROMPT_REQUEST,
    payload
);
```

流式 token 通过 `respondTo` 以 `LLMPromptStreamChunkPayload` 逐个返回，最后以 `LLMPromptResultPayload` 返回完整结果。

### 3.3 后台任务

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "summary-" + sourceId,
    400,                // maxTokens
    0.2f,               // temperature（稳定任务低温）
    false,              // stream
    false,              // thinking
    "TASK",             // lane = TASK
    10,                 // taskPriority
    true,               // taskPreemptible
    List.of(
        LLMPromptRequestPayload.ChunkPayload.message(List.of(
            LLMPromptRequestPayload.MessageItemPayload.system("把输入内容压缩为结构化摘要。"),
            LLMPromptRequestPayload.MessageItemPayload.user(rawText)
        ))
    )
);

adapter.requestCapability(
    ProtocolCapabilities.LLM_REQUEST,
    PayloadType.LLM_PROMPT_REQUEST,
    payload
);
```

### 3.4 流式后台任务

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "task-stream-" + sourceId,
    400,
    0.2f,
    true,               // stream = true
    false,
    "TASK",             // lane = TASK
    10,
    true,
    List.of(
        LLMPromptRequestPayload.ChunkPayload.message(List.of(
            LLMPromptRequestPayload.MessageItemPayload.system("把输入内容压缩为结构化摘要。"),
            LLMPromptRequestPayload.MessageItemPayload.user(rawText)
        ))
    )
);
```

---

## 4. Message 参数说明

### 4.1 role

| role | 用途 |
|---|---|
| `system` | 规则、身份、输出格式、限制条件 |
| `user` | 当前输入、任务材料、玩家话语、待处理文本 |
| `assistant` | 少量历史示例或上下文中的模型已说内容 |

### 4.2 content

`content` 是实际文本内容。null 会归一化为空字符串。

外部模组可以传多个 `system` 消息。LLM 模块不会合并或丢弃多条 system，按列表顺序转发给 JavaLlamaServer。

建议：

- 不要把无限历史塞进 messages
- 不要把 secret、token、路径敏感信息塞进 messages
- system 放稳定规则，user 放本轮输入和材料
- 如果需要结构化输出，在 system 里明确格式
- 如果调用方已经有上下文裁剪逻辑，应先裁剪再发 LLM

### 4.3 推荐消息结构

对话场景：

```text
system: 当前 agent 的行为规则、语气、禁止事项、输出限制
user: 当前玩家输入 + 必要场景上下文
assistant: 可选，少量上一轮回复
```

后台任务：

```text
system: 任务说明和输出格式
user: 待摘要/待分析/待转换内容
```

---

## 5. Chunks 混排

chunks 列表支持 message 和 rag 块**任意顺序混排**。LLM 模块按 chunks 列表顺序处理：

- **message chunk**：直接展开为消息列表
- **rag chunk**：检索后以 **system message** 插入到当前位置

### 5.1 混排示例

```java
List.of(
    // 第1块：人设 + 用户输入
    LLMPromptRequestPayload.ChunkPayload.message(List.of(
        LLMPromptRequestPayload.MessageItemPayload.system("你是铁匠NPC，性格粗犷"),
        LLMPromptRequestPayload.MessageItemPayload.user("我想买一把铁剑")
    )),
    // 第2块：NPC 记忆（RAG）
    LLMPromptRequestPayload.ChunkPayload.rag(
        "npc_blacksmith",              // uid
        "以下是该NPC的相关记忆：",      // prompt 前缀
        List.of("玩家上次买了铁盾", "玩家信用良好"), // RAG 内容
        true,                           // useCache
        true,                           // includeRagHits
        2000                            // memoryRagTokenBudget
    ),
    // 第3块：世界知识（RAG）
    LLMPromptRequestPayload.ChunkPayload.rag(
        "world_knowledge",             // uid
        "以下是关于当前世界的参考信息：", // prompt 前缀
        List.of("铁剑价格10金币", "铁匠铺在村庄东侧"), // RAG 内容
        true,                           // useCache
        true,                           // includeRagHits
        1000                            // memoryRagTokenBudget
    ),
    // 第4块：补充用户消息
    LLMPromptRequestPayload.ChunkPayload.message(List.of(
        LLMPromptRequestPayload.MessageItemPayload.user("我上次买的铁盾好用吗？")
    ))
)
```

最终发送给 LLM 的消息顺序：

```text
system: "你是铁匠NPC，性格粗犷"
user:   "我想买一把铁剑"
system: "以下是该NPC的相关记忆：\n1. 玩家上次买了铁盾\n2. 玩家信用良好"    ← rag chunk 1
system: "以下是关于当前世界的参考信息：\n1. 铁剑价格10金币\n2. 铁匠铺在村庄东侧" ← rag chunk 2
user:   "我上次买的铁盾好用吗？"
```

---

## 6. RAG 缓存

### 6.1 prompt 字段

`prompt` 是 rag chunk 的注入前缀，控制 RAG 检索结果拼入 system message 时的格式。

- **有 prompt**：`"以下是该NPC的相关记忆：\n1. 片段1\n2. 片段2"`
- **无 prompt（空字符串）**：`"1. 片段1\n2. 片段2"`（直接序号片段）

不同场景的 prompt 示例：

| 场景 | prompt | 注入效果 |
|---|---|---|
| NPC 记忆 | `"以下是该NPC的相关记忆："` | `以下是该NPC的相关记忆：\n1. ...` |
| 世界知识 | `"以下是关于当前世界的参考信息："` | `以下是关于当前世界的参考信息：\n1. ...` |
| 任务上下文 | `"以下是当前任务的背景资料："` | `以下是当前任务的背景资料：\n1. ...` |
| 通用 | `""` | `1. 片段1\n2. 片段2` |

### 6.2 缓存检索返回什么

缓存检索结果**同时做两件事**：

1. **注入 system message**：检索到的文本片段以 `prompt` 为前缀，以 system role 插入到 chunks 顺序对应位置
2. **返回 ragHits**：每个 rag chunk 返回一个 `RagHitPayload(uid + List<HitEntry>)`，供调用方做后续处理

流程：

```text
1. 调用方发送 rag chunk (uid + prompt + ragContent + useCache=true)
2. LLM 层 embed ragContent 并存入向量缓存
3. LLM 层用用户消息检索缓存，得到相似文本片段
4. 检索结果以 system message 插入到 chunks 顺序对应位置：
   有 prompt: "以下是该NPC的相关记忆：\n1. 片段1\n2. 片段2"
   无 prompt: "1. 片段1\n2. 片段2"
5. LLM 基于增强后的 messages 推理
6. 调用方收到 LLMPromptResultPayload：
   - text: LLM 的回复（已融合 RAG 上下文）
   - ragHits: 每个 rag chunk 的命中详情
```

### 6.3 复用已有缓存

如果之前已经用同一 uid 索引过内容，可以传空 `ragContent`，LLM 会直接用缓存向量检索：

```java
LLMPromptRequestPayload.ChunkPayload.rag(
    "npc_blacksmith",       // 之前索引过的 uid
    "以下是该NPC的相关记忆：", // prompt 前缀
    List.of(),              // 空 ragContent，不重复索引
    true,                   // useCache
    true,                   // includeRagHits
    2000                    // memoryRagTokenBudget
)
```

### 6.4 无缓存检索

`useCache=false` 时，不经过 LLM 层缓存，直接调用 libs 检索。每次都需要传入完整 `ragContent`：

```java
LLMPromptRequestPayload.ChunkPayload.rag(
    "one_shot_001",
    "以下是临时参考资料：",
    List.of("临时知识1", "临时知识2"),
    false,                  // useCache = false
    true,
    1000
)
```

### 6.5 RAG chunk 字段

| 字段 | 说明 | 默认值 |
|---|---|---|
| `uid` | 缓存唯一标识，用于索引和检索 | 无（必传） |
| `prompt` | RAG 注入前缀，拼在检索结果前面 | `""`（无前缀，直接序号片段） |
| `ragContent` | RAG 文本数组 | 空列表 |
| `useCache` | 是否使用 LLM 层缓存向量 | `true` |
| `includeRagHits` | 是否在响应中返回命中记录 | `true` |
| `memoryRagTokenBudget` | 记忆 RAG 预算 | `1000` |

### 6.6 RAG 知识库文件管理

LLM 只管理自己的向量缓存文件（`config/Tianshu/module/llm/cache/`）。其他模块的 RAG 知识库文件（静态知识、长期记忆等）由各模块自行管理，LLM 不参与。

调用方需要使用 RAG 时，将文本内容通过 `ragContent` 传入即可，不需要关心 LLM 内部的缓存存储细节。

---

## 7. 缓存管理

外部模块通过 `LLM_CACHE_MANAGE` 能力管理 LLM 缓存的生命周期。典型场景：记忆管理模块在删除某条记忆后，同步删除 LLM 缓存中的对应向量。

### 7.1 LLMCacheManagePayload

```java
public record LLMCacheManagePayload(
    String action,       // "EVICT_ALL" / "EVICT_CONTENT" / "QUERY"
    String uid,          // 缓存标识
    List<String> contents // 仅 EVICT_CONTENT 时使用
)
```

### 7.2 删除指定 uid 的全部缓存

```java
LLMCacheManagePayload payload = LLMCacheManagePayload.evictAll("npc_blacksmith");
adapter.requestCapability(
    ProtocolCapabilities.LLM_CACHE_MANAGE,
    PayloadType.LLM_CACHE_MANAGE,
    payload
);
```

### 7.3 删除指定 uid 的指定内容

```java
LLMCacheManagePayload payload = LLMCacheManagePayload.evictContent(
    "npc_blacksmith",
    List.of("玩家上次买了铁盾", "旧坐标 (50,64,100)")
);
adapter.requestCapability(
    ProtocolCapabilities.LLM_CACHE_MANAGE,
    PayloadType.LLM_CACHE_MANAGE,
    payload
);
```

### 7.4 查询缓存是否存在

```java
LLMCacheManagePayload payload = LLMCacheManagePayload.query("npc_blacksmith");
adapter.requestCapability(
    ProtocolCapabilities.LLM_CACHE_MANAGE,
    PayloadType.LLM_CACHE_MANAGE,
    payload
);
```

### 7.5 LLMCacheManageResultPayload

```java
public record LLMCacheManageResultPayload(
    String action,       // 操作类型
    String uid,          // 缓存标识
    boolean success,     // 操作是否成功
    boolean exists,      // 缓存是否存在（QUERY 时有意义）
    String errorMessage  // 错误信息（仅失败时）
)
```

### 7.6 操作类型

| action | 说明 | 返回 |
|---|---|---|
| `EVICT_ALL` | 删除指定 uid 的全部缓存 | `success=true/false` |
| `EVICT_CONTENT` | 按 content 精确匹配删除 | `success=true/false` |
| `QUERY` | 查询缓存是否存在 | `exists=true/false` |

---

## 8. 响应处理

### 8.1 流式响应

流式场景下（`stream=true`），LLM 通过 `respondTo` 依次发送：

| Payload | 说明 |
|---|---|
| `LLMPromptStreamChunkPayload` (chunk) | 每个 token，`finished=false` |
| `LLMPromptStreamChunkPayload` (end) | 流结束标记，`finished=true` |
| `LLMPromptResultPayload` | 最终结果，包含完整文本和 ragHits |

```java
public record LLMPromptStreamChunkPayload(
    String requestId,
    String text,           // token 文本
    boolean finished,      // 是否结束
    int index,             // chunk 序号
    List<RagHitPayload> ragHits
)
```

### 8.2 同步响应

同步场景下（`stream=false`），LLM 只发送一个 `LLMPromptResultPayload`：

```java
public record LLMPromptResultPayload(
    String requestId,
    String status,         // "COMPLETED" / "FAILED" / "CANCELLED"
    String text,           // 完整文本
    String errorCode,      // 错误码（仅 FAILED）
    String errorMessage,   // 错误信息（仅 FAILED）
    List<RagHitPayload> ragHits
)
```

### 8.3 RagHitPayload

每个 rag chunk 返回一个 `RagHitPayload`，包含该 chunk 的 uid 和所有命中条目：

```java
public record RagHitPayload(
    String uid,           // 缓存标识
    List<HitEntry> hits   // 命中条目列表
)

public record HitEntry(
    double score,         // 相似度分数
    String content        // 命中内容
)
```

示例：

```json
{
  "ragHits": [
    {
      "uid": "npc_blacksmith",
      "hits": [
        { "score": 0.92, "content": "玩家上次买了铁盾" },
        { "score": 0.85, "content": "玩家信用良好" }
      ]
    },
    {
      "uid": "world_knowledge",
      "hits": [
        { "score": 0.78, "content": "铁剑价格10金币" }
      ]
    }
  ]
}
```

调用方可以用 `ragHits` 来：
- 了解哪些记忆被检索到
- 做记忆衰减/清理决策
- 记录 RAG 使用情况

---

## 9. 字段默认值

| 字段 | 默认值 |
|---|---|
| `maxTokens` | 0（不限制） |
| `temperature` | 0.7 |
| `stream` | false |
| `thinking` | false |
| `lane` | "CHAT" |
| `taskPriority` | 0 |
| `taskPreemptible` | false |
| `prompt` | ""（无前缀，直接序号片段） |
| `useCache` | true |
| `includeRagHits` | true |
| `memoryRagTokenBudget` | 1000 |

---

## 10. 错误码

| 错误码 | 含义 |
|---|---|
| `LLM_SERVICE_NOT_READY` | LLM 服务未初始化 |
| `LLM_REQUEST_FAILED` | 请求解析或处理失败 |
| `LLM_INFERENCE_FAILED` | 底层推理失败 |

---

## 11. 接入检查清单

**对话请求：**

- [ ] 使用 `LLM_REQUEST` 能力发起请求
- [ ] `requestId` 包含业务标识，便于追踪
- [ ] `maxTokens` 设置上限（建议 128-512）
- [ ] `temperature` 对话场景建议 0.5-0.9
- [ ] 需要流式展示时 `stream=true`
- [ ] 不 import `function.llm.*` 内部类

**后台任务：**

- [ ] `lane="TASK"`
- [ ] `maxTokens` 设置上限，防止输出失控
- [ ] `temperature` 稳定任务建议 0.0-0.3
- [ ] 可恢复任务才设置 `taskPreemptible=true`
- [ ] 流式任务：`stream=true` + `lane="TASK"`

**RAG 缓存：**

- [ ] `uid` 使用业务唯一标识
- [ ] `prompt` 设置有意义的 RAG 注入前缀（或留空使用纯序号片段）
- [ ] 需要缓存时 `useCache=true`
- [ ] 复用已有缓存时传空 `ragContent`
- [ ] RAG 知识库文件由调用方自行管理
- [ ] 不硬编码 LLM 内部缓存路径
- [ ] 多个 rag chunk 可以混排在 chunks 列表中的任意位置

**缓存管理：**

- [ ] 删除记忆后同步调用 `LLM_CACHE_MANAGE` 清理缓存
- [ ] 使用 `EVICT_CONTENT` 精确删除指定内容
- [ ] 使用 `EVICT_ALL` 清除整个 uid 的缓存
- [ ] 使用 `QUERY` 检查缓存是否存在
