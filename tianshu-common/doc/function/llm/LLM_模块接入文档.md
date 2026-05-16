# LLM 模块接入文档

## 1. 接入入口

所有外部模块调用 LLM 都走协议能力：

```text
ProtocolCapabilities.LLM_TASK_REQUEST
PayloadType.LLM_TASK_REQUEST
LlmTaskRequestPayload
```

调用方不直接访问 JavaLlamaServer HTTP，也不直接发公共 LLM 文本广播。LLM 的流式输出和最终结果会绑定原始请求 envelope 返回。

## 2. 请求类型选择

`usageKind` 是天枢 common 协议层字段，不是 JavaLlamaServer 的 message 类型，也不是服务端 `lane`。JavaLlamaServer 文档中只规定：

```text
lane = chat | task
message.role = system | user | assistant
```

当前 common LLM task 网关统一通过 task lane 调用服务端；`INTERACTIVE` 只表示该请求在进入 gateway 调度前必须由 LLM 向 IA 查询使用授权。

| 场景 | usageKind | 是否需要 IA 授权 |
|---|---|---|
| 玩家可见、赢得仲裁后的发言/chat 请求 | `INTERACTIVE` | 需要 |
| NPC/Agent 当前回合正式回复 | `INTERACTIVE` | 需要 |
| 后台摘要、压缩、分析、生成标签、离线任务 | `TASK` | 不需要 |
| 不直接展示给玩家的工具型推理 | `TASK` | 不需要 |

不要用 `purpose` 判断是否需要授权。授权只由 `usageKind` 决定。

## 3. 赢得 IA 仲裁后发 chat 请求

### 3.1 调用方职责

当模块已经赢得 IA 仲裁，并准备让某个 agent 发起玩家可见回复时，调用方负责：

1. 准备业务 prompt。
2. 设置 `usageKind=INTERACTIVE`。
3. 设置自己的 `moduleId`。
4. 设置参与仲裁的 `agentId`，它对应 IA 的 `participantId`。
5. 携带 IA 上下文：`sessionId` 和 `turnId`。
6. 通过 `LLM_TASK_REQUEST` 发给 LLM。

调用方不要传 owner，也不要传 lease。LLM gateway 会统一向 IA 查询：

```text
DIALOGUE_LLM_USAGE_AUTHORIZE
```

IA 根据 session store 判断是否允许。

### 3.2 INTERACTIVE 请求示例

```java
LlmTaskRequestPayload payload = new LlmTaskRequestPayload(
        "maid-chat-" + turnId,
        "maid.chat.reply",
        LlmTaskUsageKind.INTERACTIVE,
        List.of(
                new LlmTaskMessagePayload("system", "你是当前 agent 的回复生成器。只输出最终给玩家看的回复。"),
                new LlmTaskMessagePayload("user", "玩家说：" + playerText)
        ),
        List.of(
                "player_name=" + playerName,
                "agent_mood=calm"
        ),
        100,
        false,
        true,
        true,
        true,
        256,
        0.7D,
        expireAtMillis,
        "module.maid",
        "maid.primary",
        "world",
        List.of(),
        new LlmUsageAuthorizationPayload(sessionId, turnId)
);

adapter.requestCapability(
        parentEnvelope,
        ProtocolCapabilities.LLM_TASK_REQUEST,
        PayloadType.LLM_TASK_REQUEST,
        payload
);
```

### 3.3 INTERACTIVE 字段建议

| 字段 | 建议 |
|---|---|
| `taskId` | 包含 turnId 或 request id，便于追踪 |
| `purpose` | 业务用途，例如 `maid.chat.reply` |
| `usageKind` | 固定 `INTERACTIVE` |
| `stream` | 玩家可见回复建议 `true` |
| `thinking` | 需要模型思考时为 `true`，否则 `false` |
| `useRag` | 需要世界/agent 知识时为 `true` |
| `maxTokens` | 玩家回复必须设置上限，例如 128-512 |
| `temperature` | 对话可用 0.5-0.9，稳定回复可降低 |
| `moduleId` | 发起请求的 owner 模块 ID |
| `agentId` | IA participantId 对应的 agent ID |
| `staticScope` | 常用 `world` 或 `mod` |
| `authorization` | `new LlmUsageAuthorizationPayload(sessionId, turnId)` |

## 4. 普通 TASK 请求

### 4.1 TASK 使用场景

普通后台任务不需要 IA 授权，例如：

- 短期记忆压缩。
- 长文本摘要。
- 生成检索关键词。
- 世界知识分析。
- 非玩家可见的工具型推理。

### 4.2 TASK 请求示例

```java
LlmTaskRequestPayload payload = new LlmTaskRequestPayload(
        "summary-" + sourceId,
        "memory.summary",
        LlmTaskUsageKind.TASK,
        List.of(
                new LlmTaskMessagePayload("system", "把输入内容压缩为结构化摘要。"),
                new LlmTaskMessagePayload("user", rawText)
        ),
        List.of(),
        10,
        true,
        false,
        false,
        false,
        400,
        0.2D,
        System.currentTimeMillis() + 300000L,
        "module.assistant",
        "tianshu.assistant",
        "none",
        List.of(),
        LlmUsageAuthorizationPayload.EMPTY
);

adapter.requestCapability(
        parentEnvelope,
        ProtocolCapabilities.LLM_TASK_REQUEST,
        PayloadType.LLM_TASK_REQUEST,
        payload
);
```

### 4.3 TASK 字段建议

| 字段 | 建议 |
|---|---|
| `usageKind` | 固定 `TASK` |
| `stream` | 后台任务一般 `false` |
| `thinking` | 摘要、分类一般 `false` |
| `useRag` | 默认 `false`，确实需要知识检索才打开 |
| `maxTokens` | 必须设置，防止后台输出失控 |
| `temperature` | 稳定任务建议 0.0-0.3 |
| `taskPriority` | 数值越高越优先，普通后台任务不要过高 |
| `taskPreemptible` | 可重试、可恢复任务可设 `true` |
| `authorization` | `LlmUsageAuthorizationPayload.EMPTY` |

## 5. Message 参数说明

每条消息使用：

```java
new LlmTaskMessagePayload(role, content)
```

### 5.1 role

| role | 用途 |
|---|---|
| `system` | 规则、身份、输出格式、限制条件 |
| `user` | 当前输入、任务材料、玩家话语、待处理文本 |
| `assistant` | 少量历史示例或上下文中的模型已说内容 |

非法或空 role 会归一化为 `user`。

### 5.2 content

`content` 是实际文本内容。null 会归一化为空字符串。

外部模组可以传多个 `system` 消息。common 不会合并或丢弃多条 system；它会按 `messages` 列表顺序转发给 JavaLlamaServer。服务端技术文档示例也包含多条 system 消息。

建议：

- 不要把无限历史塞进 messages。
- 不要把 secret、token、路径敏感信息塞进 messages。
- system 放稳定规则，user 放本轮输入和材料。
- 如果需要结构化输出，在 system 里明确格式。
- 如果调用方已经有上下文裁剪逻辑，应先裁剪再发 LLM。

### 5.3 推荐消息结构

INTERACTIVE：

```text
system: 当前 agent 的行为规则、语气、禁止事项、输出限制
user: 当前玩家输入 + 必要场景上下文
assistant: 可选，少量上一轮回复
```

TASK：

```text
system: 任务说明和输出格式
user: 待摘要/待分析/待转换内容
```

## 6. 多轮上下文与 RAG 参数说明

### 6.1 多轮上下文

外部模组可以在 `messages` 中传多轮上下文，例如：

```text
system: 角色和规则
user: 上一轮玩家输入
assistant: 上一轮 agent 回复
user: 当前玩家输入
```

common 会按顺序转发这些 messages。是否保留多少轮、如何裁剪历史，由调用方负责。LLM 模块不会自动维护每个外部模组的多轮短期对话历史。

### 6.2 长期记忆 RAG

JavaLlamaServer 支持 `use_memory_rag` 和 `memory_rag_token_budget`，但服务端文档规定长期记忆 RAG 只对 `chat` lane 生效，`task` lane 会忽略。

当前 common 的 `LLM_TASK_REQUEST` 网关统一走服务端 `task` lane，因此外部模组通过该入口不能依赖服务端长期记忆 RAG 自动注入多轮记忆。需要多轮记忆时有两种方式：

1. 调用方自己裁剪历史，把需要的多轮上下文放进 `messages`。
2. 调用方把本轮必要事实放进 `dynamicFacts`，并设置 `useRag=true`。

如果未来 common 开放真正的服务端 `chat` lane 接口，再单独暴露 `useMemoryRag` / `memoryRagTokenBudget` 给交互型调用方。

### 6.3 RAG 参数

| 字段 | 说明 |
|---|---|
| `useRag` | 是否启用 RAG |
| `dynamicFacts` | 本次请求的临时事实，会作为 dynamic_rag 发送 |
| `moduleId` | 用于服务端 profile 的模块段 |
| `agentId` | 用于服务端 profile 的 agent 段 |
| `staticScope` | 静态 RAG 范围 |
| `staticMods` | 指定多个 mod 的静态 RAG |

`staticScope` 可选：

| staticScope | 含义 |
|---|---|
| `none` | 不使用静态 RAG |
| `mod` | 使用当前 module 的静态 RAG |
| `world` | 使用当前 world 下与 profile 相关的静态 RAG |
| `list` | 使用 `staticMods` 指定的模组列表 |

调用方不传 world。LLM common 会根据当前 scope 生成 `world`，并把：

```text
moduleId + "/" + agentId
```

发送为 JavaLlamaServer 的 `profile`。默认 fallback 只能区分本地 game directory；单机存档名、服务器地址、Realm id 需要由具体 MC 适配层注入公共 `WorldIdentityProvider`，不是由外部业务模组或 IA 传给 LLM。

## 7. RAG 文件路径能力

外部模组和 Assistant 不需要自己从配置目录拼 RAG 路径，也不需要 import `function.llm.*`。路径查询通过协议中心请求 LLM 能力：

```text
ProtocolCapabilities.LLM_RAG_PATH_RESOLVE
PayloadType.LLM_RAG_PATH_REQUEST
```

请求 payload：

```java
new LlmRagPathRequestPayload(
        requestId,
        "module.maid",
        "maid.primary"
)
```

LLM 返回：

```text
PayloadType.LLM_RAG_PATH_RESULT
LlmRagPathResultPayload
```

常用返回字段：

| 字段 | 用途 |
|---|---|
| `staticRagRoot` | 当前 world 下模块静态 RAG 目录 |
| `memoryRagRoot` | 当前 world 下 agent 长期记忆 RAG 目录 |
| `memoriesFile` | 当前 world 下 agent `memories.jsonl` |
| `profilesFile` | 当前 world 的 `profiles.json` |

目录结构遵循 JavaLlamaServer 契约：

```text
<ragRoot>/<world>/<module>/static_rag/
<ragRoot>/<world>/<module>/agents/<agent>/memory_rag/
<ragRoot>/<world>/<module>/agents/<agent>/memory_rag/memories.jsonl
```

LLM 只提供路径解析。具体写入哪些静态知识、长期记忆如何更新、什么时候 compact，由各模块自己管理。

## 8. 响应处理

LLM 结果通过 parent envelope 返回。

| Payload | 说明 |
|---|---|
| `LlmTaskStreamChunkPayload` | 流式文本、结束、错误 chunk |
| `LlmTaskResultPayload` | 最终完成、失败、取消结果 |

调用方应按自己的业务所有权决定是否把文本转给 UI/TTS。不要让 UI/TTS 直接订阅底层 LLM 输出。

## 9. 错误码

| 错误码 | 含义 |
|---|---|
| `EMPTY_MESSAGES` | messages 为空 |
| `TOO_MANY_MESSAGES` | 消息数量超过 gateway 限制 |
| `MESSAGE_TOO_LARGE` | 单条消息过大 |
| `TOO_MANY_DYNAMIC_FACTS` | dynamicFacts 数量过多 |
| `DYNAMIC_FACT_TOO_LARGE` | 单条 dynamic fact 过大 |
| `SOURCE_PENDING_LIMIT` | 来源模块 pending 过多 |
| `LLM_USAGE_AUTH_DENIED` | IA 明确拒绝 INTERACTIVE 请求 |
| `LLM_USAGE_AUTH_UNAVAILABLE` | IA 授权不可达或超时 |
| `LLM_INVOCATION_FAILED` | 底层推理失败 |
| `LLM_TASK_CANCELLED` | task 被取消 |

## 10. 接入检查清单

INTERACTIVE 请求：

- 已赢得 IA 仲裁。
- `usageKind=INTERACTIVE`。
- `moduleId` 是 owner 模块 ID。
- `agentId` 与 IA participantId 对齐。
- `authorization.sessionId` 非空。
- `authorization.turnId` 与当前 turn 对齐。
- `stream=true`，除非调用方明确不需要流式展示。
- `maxTokens` 有上限。

TASK 请求：

- `usageKind=TASK`。
- 不携带 IA owner/lease。
- `authorization=EMPTY`。
- `maxTokens` 有上限。
- 后台任务默认 `stream=false`。
- 可恢复任务才设置 `taskPreemptible=true`。
