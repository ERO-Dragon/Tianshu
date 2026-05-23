# JavaLlamaServer 适配设计

## 1. 文档定位

本文描述天枢 common 侧对 JavaLlamaServer 的适配设计。这里不再把 JavaLlamaServer 当作普通可执行示例，而是把它视为独立推理子系统：它有自己的 JVM、native library path、HTTP API、chat/task lane、RAG 索引和健康状态。

common 侧目标不是“能启动”，而是构建一个可恢复、可观察、边界清晰的服务适配层。

## 2. 服务端能力模型

JavaLlamaServer 暴露 OpenAI 兼容 HTTP API，并增加 Minecraft 场景需要的本地能力。

| 能力 | 说明 | common 适配状态 |
|---|---|---|
| `/v1/chat/completions` | chat/task 推理统一入口 | 已适配 |
| SSE stream | 流式 token 输出 | 已适配 |
| `lane=chat/task` | 玩家实时对话与后台任务分 lane | 已适配，task 网关固定使用 task lane |
| `thinking` | 模型 profile 相关思考控制 | 已透传 |
| `temperature` | 采样温度 | 已透传 |
| `max_tokens` | 输出上限 | 已透传 |
| `dynamic_rag` | 请求级动态事实 | 已透传 |
| `use_rag` | 是否启用静态/动态 RAG | 已透传 |
| `use_memory_rag` | 是否启用长期记忆 RAG | chat lane 可用，task lane 不使用 |
| `include_rag_hits` | 返回实际注入的 RAG 命中 | 已解析并回传 |
| `/health` | 健康检查 | 已适配 |
| `/v1/embeddings` | embedding 服务 | 启动参数可配置，common 当前不直接调用 |
| 多世界 `--rag-root-path` | world/profile/static scope RAG | 已适配启动参数与请求字段 |

## 3. 进程隔离架构

JavaLlamaServer 必须作为独立 JVM 运行。

```text
Minecraft JVM
  ├─ Tianshu common
  │   ├─ ProtocolRuntime
  │   ├─ LlmModule
  │   └─ LlmServerProcessManager
  │       └─ ProcessBuilder
  │
  └─ JavaLlamaServer JVM
      ├─ ServerApp
      ├─ HTTP API
      ├─ jjml / llama native binding
      ├─ chat lane context
      ├─ task lane context
      └─ RAG indexes
```

隔离收益：

- JNI 崩溃不会带崩 Minecraft。
- native 内存和模型资源独立于 MC 堆。
- 服务端 stdout 可独立监控。
- 服务可重启、可清理残留。
- 不把 jjml/native 依赖直接暴露给上层业务模块。

## 4. 启动参数适配

### 4.1 基础参数

common 当前启动命令结构：

```text
<java>
-Xmx1G
-Djava.library.path=<nativesDir>
-cp <serverJar>
com.javallamaserver.core.ServerApp
-m <modelPath>
-c <contextSize>
--chat-context <chatContextSize>
--task-context <taskContextSize>
-ngl 999
--host 127.0.0.1
--port <port>
```

设计要求：

- `java` 优先复用当前 Minecraft JVM 的 Java 路径。
- `-Djava.library.path` 必须指向宿主侧已经解压出的 native 目录。
- 工作目录使用 native 目录，降低 native loader 查找失败概率。
- host 固定为 `127.0.0.1`，不允许默认暴露到局域网。
- server jar 必须先由 `INativeLibBridge` 解压到物理文件系统。

### 4.2 Embedding 参数

当配置了 embedding 模型并且文件存在时，common 追加：

```text
--embedding-model <embeddingGguf>
--embedding-context <embeddingContextSize>
--embedding-gpu-layers 999
```

设计约束：

- 没有 embedding 模型时不传 embedding 参数。
- 静态 RAG、长期记忆 RAG 依赖 embedding；没有 embedding 时不应假设服务端能完成 RAG。
- common 不直接构造 embedding 请求，避免让多个上层模块绕过 LLM task 语义滥用向量化服务。

### 4.3 RAG 参数

当前 common 优先使用新服务端的 RAG root 模式：

```text
--rag-root-path <ragRootDir>
--dynamic-rag-top-k <n>
```

当 `--rag-root-path` 目录无法准备或配置禁用时，common 回退到兼容模式参数：

```text
--static-rag-path <staticRagDir>
--static-rag-top-k <n>
--dynamic-rag-top-k <n>
--memory-rag-path <memoryRagDir>
--memory-rag-refresh-interval-ms <ms>
```

静态 RAG 目录只有在存在可索引文件时才传给服务端。可索引后缀：

```text
.txt
.md
.json
.jsonl
```

长期记忆目录由 common 确保存在后传给服务端。服务端只读 `memories.jsonl`，索引缓存由服务端维护。

### 4.4 队列与超时参数

common 追加：

```text
--chat-max-queue-size <n>
--task-max-queue-size <n>
--task-suspend-on-chat <true|false>
--request-timeout-seconds <n>
```

推荐策略：

| 参数 | 建议 |
|---|---|
| chat queue | 小容量，避免玩家可见延迟堆积 |
| task queue | 默认 1，避免后台任务吞噬资源 |
| task suspend on chat | 默认开启，让玩家实时对话优先 |
| request timeout | 必须有限，禁止无限等待 |

### 4.5 Cache 参数

如果配置了 KV cache 类型，则追加：

```text
--cache-type-k <type>
--cache-type-v <type>
```
默认采用q8量化
这些参数属于服务端模型性能/显存策略，不应由业务调用方在每次请求中控制。

## 5. HTTP 请求适配

### 5.1 Chat completions 请求

common 侧统一调用：

```text
POST http://127.0.0.1:<port>/v1/chat/completions
```

请求 header：

```text
Content-Type: application/json; charset=utf-8
Accept: text/event-stream 或 application/json
```

请求体字段中，`world` 由 common 根据当前 scope 自动补齐；调用方只提供 `moduleId/agentId/staticScope/staticMods`，common 组装为服务端协议字段。

| 字段 | common 来源 | 说明 |
|---|---|---|
| `messages` | invocation messages | 已由调用方完成 prompt 组装 |
| `lane` | invocation lane | task 网关使用 `task` |
| `temperature` | generation options | common 做范围归一化 |
| `stream` | generation options | 决定 SSE 或 JSON 响应 |
| `thinking` | generation options | 服务端按 model profile 处理 |
| `use_rag` | generation options | 控制静态/动态 RAG |
| `include_rag_hits` | generation options | 控制 RAG 元数据返回 |
| `use_memory_rag` | chat lane only | task lane 不发送 |
| `memory_rag_token_budget` | chat lane only | 正数才发送 |
| `max_tokens` | generation options | 正数才发送 |
| `dynamic_rag` | rag context | 非空才发送 |
| `world` | common 当前 scope | 自动补齐，调用方不能外传覆盖 |
| `profile` | `moduleId/agentId` | common 由请求方模块和 agent 组装 |
| `static_scope` | rag routing context | `none` / `mod` / `world` / `list` |
| `static_mods` | rag routing context | static_scope 为 list 时发送 |
| `task_priority` | task lane only | 服务端 task 调度使用 |
| `task_preemptible` | task lane only | 服务端安全点抢占使用 |

### 5.2 非流式响应

服务端返回 OpenAI 兼容 JSON：

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "..."
      },
      "finish_reason": "stop"
    }
  ],
  "rag_hits": {
    "memory": []
  }
}
```

common 处理顺序：

1. 读取完整响应体。
2. 解析 `rag_hits`。
3. 解析 `choices[0].message.content`。
4. 作为单个 chunk 回传。
5. 标记 task completed。

### 5.3 流式响应

服务端返回 SSE：

```text
data: {"choices":[],"rag_hits":{"memory":[]}}

data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

data: {"choices":[{"delta":{"content":null},"finish_reason":"stop"}]}

data: [DONE]
```

common 处理要求：

- `choices` 为空时仍解析 `rag_hits`。
- `delta.content` 不存在或为 null 时跳过文本输出。
- `[DONE]` 表示完成。
- 如果 requestId 已失效，标记为 cancelled 并停止输出。
- 解析单个 chunk 失败不打印原文，避免泄漏 prompt 或模型输出。

## 6. Health check 适配

common 通过：

```text
GET /health
```

判断服务是否 ready。

启动等待策略：

- 总等待 60 秒。
- 每 500ms 检查一次。
- 先检查端口是否开放，再请求 `/health`。
- 只有 2xx 才视为 ready。
- 启动超时必须停止子进程，避免半初始化服务残留。

服务端 health 可包含：

```json
{
  "status": "ready",
  "embedding": true,
  "chat_queue_size": 0,
  "chat_max_queue_size": 2,
  "task_queue_size": 0,
  "task_max_queue_size": 1,
  "current_lane": null,
  "task_suspended": false
}
```

common 目前只使用 HTTP 状态码判断健康，不依赖具体字段。这样可以降低服务端 health JSON 演进造成的兼容风险。

## 7. 错误和安全策略

### 7.1 HTTP 错误

服务端非 200 时，common 对上层返回：

```text
LLM service returned status code: <code>
```

不返回 response body。原因：body 可能包含 prompt、RAG 内容、模型输出、异常堆栈或路径信息。

### 7.2 网络错误

网络异常统一返回：

```text
LLM network error
```

详细异常只进入日志，并且日志不得附带请求体或流式 chunk 原文。

### 7.3 取消语义

common 使用 requestId 判断当前流是否仍然有效：

- requestId 不匹配，说明新请求或取消已经发生。
- 此时停止输出旧 stream。
- 最终状态为 cancelled，而不是 failed。

注意：当前 common 是单活 stream 设计。多路取消需要重构 stream registry。

### 7.4 端口安全

服务端只能绑定 `127.0.0.1`。如果未来需要局域网访问，必须新增显式配置、认证、访问控制和风险提示，不能把 host 默认改为 `0.0.0.0`。

## 8. RAG 适配设计

### 8.1 兼容 RAG 模式

当前 common 已适配兼容模式：

```text
static rag: <llmBase>/rag/static
memory rag: <llmBase>/rag/memory
```

适合单世界或过渡期使用。

common 的职责：

- 准备目录。
- 传递路径。
- 传递 topK/refresh 参数。
- 透传 dynamic_rag。
- 解析服务端返回的 rag_hits。

common 不负责：

- 维护 memory JSONL。
- 更新 hit_count/TTL/importance。
- 决定 NPC 是否应记住某事。
- 把世界状态自动写入长期记忆。

这些属于 Auxilium 或更上层业务模块。

### 8.2 多世界 RAG root 适配方向

服务端新架构支持方向：

```text
--rag-root-path <root>
```

目录形态：

```text
llm_rag/
├── <world>/
│   ├── profiles.json
│   ├── <mod>/
│   │   ├── static_rag/
│   │   └── agents/
│   │       └── <agent>/
│   │           └── memory_rag/
│   │               └── memories.jsonl
```

请求级字段方向：

| 字段 | 说明 |
|---|---|
| `moduleId` | 请求方模块 ID，由调用方提供 |
| `agentId` | 请求方 agent/participant ID，由调用方提供 |
| `staticScope` | `none` / `mod` / `world` / `list` |
| `staticMods` | staticScope 为 list 时使用 |
| `world` | common 自动补齐，不由调用方传入 |
| `profile` | common 组装为 `<moduleId>/<agentId>` 后发给服务端 |

common 已开放这些字段。接入链路为：

1. `ITianshuConfig#getLlmRagRootPath()` 提供启动目录。
2. `LlmServerProcessManager` 启动时传入 `--rag-root-path`。
3. `LlmTaskRequestPayload` 承载 `moduleId/agentId/staticScope/staticMods`、`usageKind` 和 IA 授权上下文。
4. `LlmModule` 使用当前 scope 自动补齐 worldId。
5. `LlmGatewayRequest` 和 `LlmRagContext` 只做路由上下文透传。
6. `LlmEngine` 将 `world/profile/static_scope/static_mods` 写入 `/v1/chat/completions` 请求体。
7. Auxilium 当前使用 `module.ax/tianshu.ax` profile，后台压缩任务默认 `static_scope=none`。

兼容策略：RAG root 准备失败或配置禁用时，启动层回退旧 `--static-rag-path` / `--memory-rag-path` 参数。

## 9. chat/task lane 适配

### 9.1 服务端 lane 语义

| Lane | 用途 | 特点 |
|---|---|---|
| chat | 玩家可见实时对话 | 优先级高，可用长期记忆 RAG |
| task | 摘要、压缩、后台推理 | 优先级低，默认不用长期记忆 |

服务端可以在 task decode 安全点因为 chat 请求而挂起 task。common 不应该假设 task 已经开始后一定连续输出。

### 9.2 common lane 使用

当前外部模块统一通过 `LLM_TASK_REQUEST` 进入 task lane。这符合“LLM 是能力层”的边界：

- Auxilium 的玩家可见输出也经过 IA 授权和 Auxilium 自己的语义层处理。
- 后台模块可以通过 task priority 影响队列顺序。
- common 不直接决定某段文本是否应该播给玩家。

如果未来 Auxilium 需要 chat lane，需要新增明确的 `LLM_CHAT_REQUEST` 或在 payload 中暴露 lane，并同步处理权限、仲裁和所有权。不能让任意模块随意使用 chat lane 抢占玩家对话资源。

## 10. 服务端文档同步要求

每次 JavaLlamaServer 改动后，common 文档和代码必须同步检查以下清单：

- 新增启动参数是否需要 common 配置入口。
- 旧参数是否仍兼容。
- `/health` 的 ready 判断是否仍可靠。
- SSE chunk 是否仍兼容 OpenAI 增量格式。
- RAG metadata 字段是否改变。
- task lane 抢占语义是否影响 common scheduler。
- HTTP 错误码是否需要更精确映射。
- native library path 和 server jar 启动入口是否改变。

## 11. 当前已知缺口

1. common 已适配 `--rag-root-path` 的启动参数与请求路由字段，但服务端目录内容迁移和 profile 文件生成仍由上层模块负责。
2. common 仍是单活 HTTP stream 模型，不支持多 stream 并发。
3. `LLM_STREAM` 旧 topic 仍可能被其他模块订阅，后续应逐步迁移到 Auxilium/dialogue delivery 语义。
4. 服务端 queue/lane 状态目前只用于健康判断，没有进入 common 侧动态背压策略。
5. server jar/native 解压的版本校验、hash 校验、坏缓存清理还可以继续增强。
