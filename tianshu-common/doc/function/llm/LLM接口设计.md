# LLM 层接口设计

## 1. 概述

LLM 层是主体的适配层，负责：
- 解析 `LLMRequest`（chunks）
- RAG 编排和缓存管理
- 请求级推理策略覆盖全局配置
- 调用 libs 底层能力

---

## 2. LLMRequest 请求结构

```java
public class LLMRequest {
    public Integer maxTokens;
    public Float temperature;
    public Boolean stream;
    public Boolean thinking;

    public String lane;               // "CHAT" / "TASK"
    public Integer taskPriority;      // 0 ~ 1000
    public Boolean taskPreemptible;

    public LlmInferencePolicy inferencePolicy;
    public List<Chunk> chunks;
}
```

`thinking` 只表示是否让模型生成 thinking。跨模块协议里的 `LLMPromptRequestPayload.includeThinkingContent` 是对外响应展示开关，不属于 `LLMRequest` 内部请求结构；它由 `LlmProtocolAdapter` 在协议响应出口处理。

`inferencePolicy` 只承载三类覆盖项：
- `frameGuardEnabled`
- `targetFps`
- `mtpEnabled`

规则很简单：未传就跟随全局，传了就覆盖。

### 2.1 Chunk 分块

```java
public class Chunk {
    public String type;  // "message" / "rag"

    public List<MessageItem> messageContent;
    public List<String> ragContent;
    public String uid;
    public String prompt;
    public Boolean useCache;
    public Boolean includeRagHits;
    public Integer memoryRagTokenBudget;
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

### 3.1 模块职责

LLM 模块内部职责：
- `LlmEngineProvider` 按配置创建并异步启动 `JavaLlamaServer`
- `LlmModule` 创建并注册 `LLMService`
- `LLMService` 负责请求编排、RAG 检索、缓存管理、推理策略解析和 libs 调用
- 其他模块通过模块服务注册表获取 `LLMService`，或通过 `LLM_REQUEST` / `LLM_CACHE_MANAGE` 协议能力调用

`env` 是 common 层对游戏环境的抽象，包含日志、主线程执行、玩家消息、游戏目录等能力；它由 NeoForge 层注入。`cacheNamespace` 和 `cacheDirectory` 也是 LLM 模块内部装配参数：namespace 绑定当前 LLM/embedding 模型组合，目录由当前世界 scope 自动决定。持久 RAG cache 默认位于 `config/Tianshu/module/llm/ragCache/<world>/`，同一世界目录下按 RAG `uid` 分文件保存。

### 3.2 同步聊天

```java
String reply = service.chat("你好", "你是铁匠NPC");

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
    broadcast(token);
});
```

### 3.4 请求级推理策略

```java
LLMRequest request = LLMRequest.of(Chunk.message(...));
request.setInferencePolicy(new LlmInferencePolicy(true, 60, true));
```

策略解析规则：
- 未设置 `inferencePolicy` 时，完全跟随全局配置
- 请求里传了某一项，就只覆盖这一项
- 保帧率策略只在 LLM 与渲染共用同一张 GPU 时生效
- 如果 LLM 跑在第二张卡上，或当前性能采样不可用，就不启用保帧率调度
- MTP 只在当前模型支持时才可用；不支持时保持正常运行，不暴露额外控制面

### 3.5 后台任务

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

流式后台任务以 `CompletableFuture` 完成为准：

```java
CompletableFuture<String> future = service.submitTaskStream(request, token -> {
    publish(token);
}, ragHits);
```

跨模块调用 `LLM_REQUEST` 时，TASK 会先进入 `LlmTaskAdmissionController`，再由 LLM 模块按优先级送入 `LLMService.submitTask*()`：
- CHAT 不进入 TASK admission 队列，仍直接走聊天通道，并由 IA 授权约束。
- TASK admission 默认只启动一个 active TASK；其余任务进入等待队列。
- 如果当前 active TASK 的 `taskPreemptible=true`，更高有效优先级的任务会立即送入 libs，由 libs 执行 TASK 抢占/取消/挂起语义。
- 协议层只在请求完成后发送最终结果。

---

## 4. 字段默认值

| 字段 | 默认值 |
|------|--------|
| `maxTokens` | 0 |
| `temperature` | 0.7 |
| `stream` | false |
| `thinking` | false |
| `inferencePolicy` | 跟随全局 |
| `lane` | "CHAT" |
| `taskPriority` | 0 |
| `taskPreemptible` | false |
| `useCache` | true |
| `includeRagHits` | true |
| `memoryRagTokenBudget` | 1000 |

---

## 5. 处理流程

```text
LLMRequest
    ↓
解析 chunks
    ↓
解析 inferencePolicy
    ↓
message chunks → 组装 messages
rag chunks → 判断 useCache
    ↓
ragCache.search() / libs.search()
    ↓
组装 prompt
    ↓
计算推理 options
    ↓
libs.chat / chatStream / task / taskStream
```

要点：
- `thinking=false` 会显式传给 libs 的 `SamplerConfig.enableThinking=false`
- `inferencePolicy` 会被 `LlmInferenceGovernor` 解析为 `LlmInferenceOptions`
- 保帧率策略在共享 GPU 场景下根据当前 FPS / GPU 利用率决定 `vulkanPriority`
- MTP 仅在模型支持时启用，校准入口也只在支持时暴露

---

## 6. 示例

```java
LLMRequest request = LLMRequest.of(
    Chunk.message(
        MessageItem.of("system", "你是一个 Minecraft 助手"),
        MessageItem.of("user", "我的钻石镐在哪里？")
    ),
    Chunk.rag("memory", "相关记忆：", List.of("玩家把钻石镐放在末影箱"), true, true, 1000)
);
request.setStream(true);
request.setInferencePolicy(LlmInferencePolicy.defaults());

service.chatStream(request, token -> publish(token));
```
