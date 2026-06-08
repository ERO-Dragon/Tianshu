# LLM 协议中心使用文档

## 1. 使用边界

其他模块优先通过协议中心调用 LLM，不直接构造或持有 `JavaLlamaServer`。

推荐调用路径：

```text
业务模块
  -> ProtocolRuntime.submit(envelope)
  -> capability: LLM.REQUEST / LLM.CACHE_MANAGE
  -> LlmProtocolAdapter
  -> LLMService
  -> libs JavaLlamaServer
```

`LLMService` 直连方式只适合 LLM 模块内部、测试或明确需要同进程强耦合的 common 服务。跨模块业务默认使用 capability envelope，这样生命周期、队列、取消、失败和流式响应都由协议中心统一管理。

## 2. 能力与 Payload

| 能力 | PayloadType | 入参 payload | 响应 payload |
|------|-------------|--------------|--------------|
| `ProtocolCapabilities.LLM_REQUEST` | `LLM_PROMPT_REQUEST` | `LLMPromptRequestPayload` | `LLMPromptResultPayload` / `LLMPromptStreamChunkPayload` |
| `ProtocolCapabilities.LLM_CACHE_MANAGE` | `LLM_CACHE_MANAGE` | `LLMCacheManagePayload` | `LLMCacheManageResultPayload` |

LLM 模块注册 capability 时使用 `CompletionPolicy.MANUAL_COMPLETE`。调用方不能把 handler 返回当成推理完成；必须以协议响应 payload 或 envelope lifecycle 完成为准。

## 3. 普通聊天

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "blacksmith-chat",
    512,
    0.7f,
    false,
    false,
    "CHAT",
    0,
    false,
    List.of(LLMPromptRequestPayload.ChunkPayload.message(List.of(
        LLMPromptRequestPayload.MessageItemPayload.system("你是铁匠NPC"),
        LLMPromptRequestPayload.MessageItemPayload.user("我需要一把剑")
    )))
).withDialogueAuthorization(
    delivery.sessionId(),
    "module.blacksmith",
    "blacksmith.default",
    delivery.turnId()
);

TianshuEnvelope envelope = EnvelopeBuilder.requestCapability(
        "module.blacksmith",
        ProtocolCapabilities.LLM_REQUEST,
        PayloadType.LLM_PROMPT_REQUEST,
        payload
).build();

protocolRuntime.submit(envelope);
```

`lane=CHAT` 是对话型请求入口。调用方必须是 IA 当前 session owner，并携带 dialogue 授权上下文：`dialogueSessionId`、`requesterModuleId`、`requesterParticipantId`、`dialogueTurnId`。推荐从 `DialogueDeliveryPayload` 调用 `withDialogueAuthorization(...)` 补齐这些字段。LLM adapter 会在推理前向 `ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE` 查询 IA；裸 `lane=CHAT` 请求会被拒绝。`requesterModuleId` 必须和请求 envelope 的 `sourceId` 一致，不能代替其他模块发起对话型 LLM 请求。

结果由 LLM adapter 通过协议响应返回。调用方需要为原请求 `envelopeId` 登记响应处理器，并在处理器中接收 `LLMPromptResultPayload`：

```java
if (response.payload() instanceof LLMPromptResultPayload result) {
    if (result.isCompleted()) {
        String text = result.text();
    } else {
        String code = result.errorCode();
        String message = result.errorMessage();
    }
}
```

## 4. RAG 请求

RAG 是 chunk 级配置，不是全局配置。每个 `rag` chunk 自己决定 uid、prompt、是否使用缓存、是否返回命中结果和 token 预算。

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "memory-chat",
    1024,
    0.7f,
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
            "ax.player_memory",
            "相关记忆：",
            List.of("玩家昨天把钻石镐放进了家门口箱子"),
            true,
            true,
            1000
        )
    )
).withDialogueAuthorization(
    delivery.sessionId(),
    ownerModuleId,
    ownerParticipantId,
    delivery.turnId()
);
```

同一 `uid` 是一个增量向量集合：新 content 会追加索引；已存在的相同 content 会跳过 embedding 和写盘；删除单条 content 不影响同 uid 下其他内容。

## 5. 流式聊天

设置 `stream=true` 后，LLM adapter 会先发送若干 `LLMPromptStreamChunkPayload`，最后发送一个 `finished=true` 的 stream end，再发送最终 `LLMPromptResultPayload`。

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "stream-chat",
    1024,
    0.7f,
    true,
    false,
    "CHAT",
    0,
    false,
    chunks
).withDialogueAuthorization(delivery.sessionId(), ownerModuleId, ownerParticipantId, delivery.turnId());
```

接收规则：

- `LLMPromptStreamChunkPayload.finished=false`：增量 token，按 `index` 顺序拼接。
- `LLMPromptStreamChunkPayload.finished=true`：流式输出结束。
- `LLMPromptResultPayload.status=COMPLETED`：最终完整文本和最终 `ragHits`。
- 这些 payload 都是原请求的 `PacketType.RESPONSE`，由协议中心按 `parentId = requestEnvelopeId` 路由给请求方登记的响应处理器。
- 协议 envelope 只在 stream end 和最终 result 都发出后完成。

## 6. 后台任务

后台任务使用同一个 capability，只把 lane 设置为 `TASK`。

```java
LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
    "memory-compress",
    2048,
    0.3f,
    false,
    false,
    "TASK",
    10,
    true,
    List.of(LLMPromptRequestPayload.ChunkPayload.message(List.of(
        LLMPromptRequestPayload.MessageItemPayload.system("你是记忆压缩器"),
        LLMPromptRequestPayload.MessageItemPayload.user(longText)
    )))
);
```

`TASK + stream=true` 支持流式后台任务。此时 envelope 完成必须以 libs 返回的 `CompletableFuture` 完成为准，不能在提交任务成功时提前完成。
`lane=TASK` 不代表 IA 对话 owner，不需要 dialogue 授权上下文，也不能用于回答当前 IA delivery。

## 7. 缓存管理

查询缓存：

```java
TianshuEnvelope envelope = EnvelopeBuilder.requestCapability(
        "module.example",
        ProtocolCapabilities.LLM_CACHE_MANAGE,
        PayloadType.LLM_CACHE_MANAGE,
        LLMCacheManagePayload.query("ax.player_memory")
).build();
protocolRuntime.submit(envelope);
```

删除整个 uid：

```java
LLMCacheManagePayload.evictAll("ax.player_memory");
```

删除 uid 下的部分 content：

```java
LLMCacheManagePayload.evictContent(
    "ax.player_memory",
    List.of("玩家昨天把钻石镐放进了家门口箱子")
);
```

## 8. Adapter 设计原则

- Adapter 只做协议和 LLMService 的转换，不承载业务记忆逻辑。
- 请求完成由 `ProtocolContext.complete/fail` 管理，异步任务不能提前 complete。
- stream token 和最终 result 不做 `trim`，避免破坏代码块、换行和缩进。
- `thinking=false` 会明确传给 sampler，避免模型模板默认进入 thinking。
- LLM 未加载时返回 `LLM_SERVICE_NOT_READY`；调用方应把它视为可恢复状态，引导用户到【织言】页面加载模型。
