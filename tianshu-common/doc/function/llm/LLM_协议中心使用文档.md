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

`LLMPromptRequestPayload.thinking` 和 `includeThinkingContent` 是两个不同边界：

- `thinking=false`：关闭模型 thinking 生成，LLM adapter 会把该意图传给 libs sampler。
- `thinking=true + includeThinkingContent=false`：允许模型内部 thinking，但协议响应会隐藏规范化后的 `<think>...</think>` 内容。
- `thinking=true + includeThinkingContent=true`：协议响应保留规范化后的 `<think>...</think>` 内容，适合调试、评估或专门展示推理过程的工具。

`includeThinkingContent` 默认是 `false`。普通 UI、TTS 和聊天调用方不需要额外清洗 think 内容；如果 libs 在 thinking 关闭时已经去掉空的 think 包裹，协议层不会要求调用方再做重复处理。

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
- 当 `includeThinkingContent=false` 时，stream chunk 和最终 result 会在 LLM adapter 出口一致隐藏 `<think>...</think>` 内容；只包含 hidden thinking 的 chunk 不会发送。

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

TASK 的接收队列由 LLM 模块自己管理，libs 不再暴露热挂起窗口：

- `LlmTaskAdmissionController` 控制外部 TASK 的接收、等待和启动；默认保持单 active，但当当前 active TASK 标记为 `taskPreemptible=true` 且出现更高有效优先级任务时，会把抢占候选送入 libs，由 libs 执行 TASK 抢占/取消/挂起语义。
- 等待队列按有效优先级降序、同优先级 FIFO 排序；`taskPriority` 公开范围为 `0..1000`，有效优先级 = `taskPriority + 等待期间新 TASK 请求次数 * getLlmTaskAgingBoostPerRequest()`。
- 队列满时，只有更高有效优先级的新任务可以替换等待队列中的最低有效优先级任务。
- 被抢占后仍未终态的旧 TASK 会继续计入 admission 的 in-flight 边界；只有所有已送入 libs 的 TASK future 终态后，等待队列才会自动启动下一个任务，避免隐形扩容。
- 被 admission 队列拒绝或替换的请求返回 `LLMPromptResultPayload.status=FAILED`，错误码为 `LLM_TASK_QUEUE_FULL`。
- 被排队但尚未送入 libs 的请求只由 envelope lifecycle 和最终响应表达；`LLM.STATUS` 不发布 adapter 自己推断的 admission 状态。
- TASK 挂起统一走 COLD replay，不额外保留热 KV/context；底层 `COLD_RESUME_STARTED`、`PREFILL_*`、`COLD_RESUME_COMPLETED` 等事件会通过 `LLM.STATUS` topic 发布给 GUI 或诊断订阅者。

TASK 暂停和终止语义：

- libs 因 `taskSuspendOnChat=true` 暂停 TASK 时，`CompletableFuture` 不完成，LLM adapter 不发送 stream end / final result，也不完成 envelope。
- 暂停期间已发送的 stream chunk 保留；恢复后后续 chunk 仍通过同一个请求的响应处理器继续发送。
- TASK 被取消、线程中断或被更高优先级任务终止抢占时，LLM adapter 会发送 stream end，并返回 `LLMPromptResultPayload.status=CANCELLED`。
- `CANCELLED` result 的 `text` 是已经对外发送过的可见 partial text；调用方应把它当成终止态清理响应处理器，而不是当作普通失败重试。

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
- `includeThinkingContent=false` 只控制协议响应是否暴露 `<think>...</think>` 内容，不改变模型是否生成 thinking。
- TASK 暂停不产生协议终止态；只有取消、抢占或异常终止才会结束响应流。
- LLM 未加载时返回 `LLM_SERVICE_NOT_READY`；调用方应把它视为可恢复状态，引导用户到【织言】页面加载模型。
