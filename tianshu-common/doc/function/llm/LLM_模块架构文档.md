# LLM 模块架构文档

## 1. 模块定位

LLM 模块是天枢 common 内的模型能力层，负责把协议运行时中的 LLM 请求安全、可控地送到本地 JavaLlamaServer，并把流式结果和最终结果按 parent envelope 返回给调用方。

LLM 模块不负责：

- IA 仲裁。
- NPC / Agent 业务身份判断。
- Prompt 业务拼装。
- UI / TTS 分发。
- 记忆策略和长期记忆生命周期。

核心边界：

| 模块 | 职责 |
|---|---|
| `function.llm` | 服务进程管理、gateway、IA 授权查询、调度、HTTP 调用、结果回传 |
| `function.ia` | 仲裁、session store、INTERACTIVE LLM 使用授权 |
| `function.auxilium` / 其他 owner 模块 | 赢得仲裁后的业务 prompt 组装和 LLM 请求发起 |
| JavaLlamaServer | 独立 JVM 推理、RAG 检索、chat/task lane、OpenAI 兼容 HTTP API |

## 2. 总体链路

```text
调用方
  -> requestCapability(LLM_TASK_REQUEST)
  -> ProtocolRuntime
  -> LlmModule.handleTaskRequest
  -> DefaultLlmTaskGatewayService.submit
  -> LlmGatewayAdmissionController 基础校验
  -> TASK: 进入 scheduler
  -> INTERACTIVE: 进入 AUTHORIZING，向 IA 查询授权
  -> LlmInvocationService
  -> LlmEngine
  -> JavaLlamaServer /v1/chat/completions
  -> LlmTaskStreamChunkPayload / LlmTaskResultPayload
```

`LLM_TASK_REQUEST` 的 `context.complete(envelopeId)` 只表示请求已被 gateway 接收或拒绝，不表示推理完成。推理结果通过 `LlmTaskStreamChunkPayload` 和 `LlmTaskResultPayload` 异步返回。

## 3. 主要组件

| 组件 | 职责 |
|---|---|
| `LlmModule` | 注册协议能力，接收 `LLM_TASK_REQUEST`，把 payload 转为 gateway request |
| `LlmProtocolAdapter` | 封装 LLM 协议收发、IA 授权 capability 请求、stream/result 响应、托管调度任务 |
| `DefaultLlmTaskGatewayService` | LLM gateway 主状态机，负责 admission、授权等待、排队、提交、取消、结果收敛 |
| `LlmGatewayAdmissionController` | 只做本地基础校验，不直接知道 IA 细节 |
| `LlmUsageAuthorizer` | INTERACTIVE 授权边界接口 |
| `IaBackedLlmUsageAuthorizer` | 向 IA 发授权请求，维护 pending correlation，处理 IA result |
| `LlmGatewayScheduler` | 单活 submitted + pending 优先级队列 |
| `LlmInvocationService` | 执行底层 invocation，桥接流式 chunk、最终结果、取消 |
| `LlmEngine` | 构造 JavaLlamaServer HTTP 请求并解析 SSE/JSON 响应 |
| `LlmServerProcessManager` | 解压、启动、健康检查和清理 JavaLlamaServer 进程 |

## 4. 请求类型

`LlmTaskUsageKind` 是 common 协议层的使用场景标记，不是 JavaLlamaServer 的 `lane`，也不是 message role。JavaLlamaServer 只接收 `lane=chat/task` 和 `role=system/user/assistant`。

`LlmTaskUsageKind` 有两个值：

| usageKind | 说明 | IA 授权 | 服务端映射 |
|---|---|---|---|
| `TASK` | 普通后台 LLM 任务，例如摘要、压缩、分析、生成配置 | 不需要 | 当前 common 网关发送到服务端 `task` lane |
| `INTERACTIVE` | common 层“需要 IA 使用授权”的玩家可见/仲裁相关请求 | 必须由 LLM gateway 向 IA 查询 | 授权通过后当前也经 common 网关发送到服务端 `task` lane；不是服务端类型 |

LLM 不通过 `purpose` 字符串猜测请求是否需要授权。是否需要 IA 授权只看 `usageKind`。

## 5. INTERACTIVE 授权状态机

```text
LLM_TASK_REQUEST
  -> 基础 admission
  -> usageKind == INTERACTIVE
  -> AUTHORIZING
  -> requestCapability(DIALOGUE_LLM_USAGE_AUTHORIZE)
  -> IA 返回 DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT
  -> allowed: 进入 ACCEPTED / pending / submitted
  -> denied: 返回 LLM_USAGE_AUTH_DENIED
  -> timeout/unavailable: 返回 LLM_USAGE_AUTH_UNAVAILABLE
```

LLM 只向 IA 发送授权上下文：

```text
sessionId
requesterModuleId = moduleId
requesterParticipantId = agentId
turnId
timestampMillis
```

LLM 不信任调用方传入 owner 或 lease。owner、session active、lease、turnId 匹配等判断由 IA 根据 session store 完成。

授权 pending 使用 IA request envelopeId 与 result parentId 做 correlation。授权超时使用 `ProtocolRuntime` 托管的 `ExecutionLane.SCHEDULED`，不自建线程。

## 6. Gateway 调度模型

当前 common 侧 `LlmEngine` 是单 HTTP stream 模型，因此 gateway 保持单活 submitted：

```text
CREATED
  -> AUTHORIZING
  -> ACCEPTED
  -> PENDING
  -> SUBMITTED
  -> STREAMING
  -> COMPLETED
  -> FAILED
  -> CANCELLED
```

规则：

1. 同时最多一个 task 进入 submitted。
2. 其他 task 进入 pending，按 `taskPriority` 和提交顺序排序。
3. INTERACTIVE 必须授权通过后才能进入 scheduler。
4. TASK 不走 IA 授权。
5. 已 submitted 的 HTTP stream 不在 common 侧并发叠加。

## 7. RAG 与 world/profile

JavaLlamaServer 的 RAG root 模式使用：

```text
world + profile + static_scope + static_mods
```

LLM 请求 payload 中调用方提供：

```text
moduleId
agentId
staticScope
staticMods
```

LLM common 侧把它组装为服务端字段：

```text
profile = <moduleId>/<agentId>
```

`world` 由 common 运行时 `WorldScopeProvider` 提供，不由调用方直接覆盖。默认 fallback 只基于 `IGameEnvironment#getGameDirectory()` 生成本地实例级 identity；真正的单机存档名、服务器地址、Realm id 应由 MC 适配层注入 `WorldIdentityProvider`，LLM 不直接依赖 Minecraft API 或 assistant scope。

## 8. RAG 目录能力

外部模组和 Auxilium 不应该硬编码 `config/Tianshu...` 下的 RAG 目录，也不应该 import `function.llm.*`。RAG 路径查询是 LLM 模块暴露的协议能力：

```text
ProtocolCapabilities.LLM_RAG_PATH_RESOLVE
PayloadType.LLM_RAG_PATH_REQUEST
PayloadType.LLM_RAG_PATH_RESULT
```

公共协议 payload 位于 `protocol.payload`，LLM 内部负责解析 JavaLlamaServer 目录契约并返回路径字符串。

服务端目录契约：

```text
<ragRoot>/<world>/profiles.json
<ragRoot>/<world>/<module>/static_rag/
<ragRoot>/<world>/<module>/agents/<agent>/memory_rag/
<ragRoot>/<world>/<module>/agents/<agent>/memory_rag/memories.jsonl
```

能力返回字段：

| 字段 | 含义 |
|---|---|
| `ragRoot` | JavaLlamaServer `--rag-root-path` 根目录 |
| `worldRoot` | 当前 world 的 RAG 根目录 |
| `profilesFile` | 当前 world 的 `profiles.json` |
| `staticRagRoot` | 当前 world 下模块静态 RAG 目录 |
| `memoryRagRoot` | 当前 world 下 agent 长期记忆 RAG 目录 |
| `memoriesFile` | 当前 world 下 agent `memories.jsonl` |

LLM 只提供路径解析和协议对接，不替外部模组写入具体知识文件，也不定义各模块的记忆生命周期。

## 9. JavaLlamaServer HTTP 适配

JavaLlamaServer 技术文档没有 `interactive` lane。服务端只认：

```text
lane = chat | task
```

当前 common 的 `LLM_TASK_REQUEST` gateway 统一映射到 `task` lane；`INTERACTIVE` 只影响 common 侧 IA 授权，不会作为字段发给 JavaLlamaServer。

LLM common 统一调用：

```text
POST /v1/chat/completions
```

主要请求字段：

| 字段 | 来源 |
|---|---|
| `messages` | LLM task messages |
| `lane` | 当前 gateway 使用 task lane |
| `stream` | payload.stream |
| `thinking` | payload.thinking |
| `temperature` | payload.temperature |
| `max_tokens` | payload.maxTokens |
| `use_rag` | payload.useRag |
| `dynamic_rag` | payload.dynamicFacts |
| `world` | common scope |
| `profile` | `moduleId/agentId` |
| `static_scope` | payload.staticScope |
| `static_mods` | payload.staticMods |
| `task_priority` | payload.taskPriority |
| `task_preemptible` | payload.taskPreemptible |

## 10. 进程管理

JavaLlamaServer 独立 JVM 运行，common 负责：

- 解压 server jar / native。
- 固定绑定 `127.0.0.1`。
- 选择端口并健康检查。
- 传递模型、context、RAG root、queue、timeout、cache 参数。
- 读取 stdout。
- 清理残留进程。

RAG root 优先使用：

```text
--rag-root-path <ragRootDir>
--dynamic-rag-top-k <n>
```

无法准备时回退兼容 static/memory RAG 参数。

## 11. 失败原则

| 场景 | 行为 |
|---|---|
| 基础 admission 失败 | 立即返回 rejected result |
| INTERACTIVE IA 拒绝 | `LLM_USAGE_AUTH_DENIED` |
| INTERACTIVE IA 不可达或超时 | `LLM_USAGE_AUTH_UNAVAILABLE` |
| HTTP 调用失败 | 返回稳定错误，不打印 prompt/body 原文 |
| 调用方取消 | 标记 cancelled，与 failed 区分 |
| gateway shutdown | 取消 pending、authorizing 和 submitted task |
