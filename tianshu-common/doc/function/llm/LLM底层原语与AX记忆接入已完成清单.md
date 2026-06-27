# LLM 底层原语与 AX 记忆接入已完成清单

## 1. 文档定位

本文只记录对 [LLM底层原语与AX记忆接入需求.md](./LLM底层原语与AX记忆接入需求.md) 的完成情况更新。

不展开 LLM 模块全部能力，不描述 AX 记忆系统自身实现。

## 2. 对应需求完成情况

### 2.1 高阶接口继续保留

对应原文 `2.1 高阶接口继续保留`。

已完成：

- `chat` / `chatStream` / `task` / `taskStream` 对应的协议入口继续保留。
- AX 仍可通过 `LLM_REQUEST` 发送 CHAT / TASK 请求。
- 记忆内容可作为普通 `message` chunk 进入 prompt。

### 2.2 原语能力补足

对应原文 `2.2 原语能力需要补足` 和第 3 章。

已完成：

- `LLM_PRIMITIVE_QUERY / TOKEN_COUNT`
- `LLM_PRIMITIVE_QUERY / EMBED`
- `LLM_PRIMITIVE_QUERY / STATUS`
- `LLM_CACHE_MANAGE`
- `LLMPromptResultPayload.usage`
- 流式 `LLMPromptStreamChunkPayload` terminal 包

### 2.3 真实 token 计数

对应原文 `3.1 容量与运行态原语`。

已完成：

- `TOKEN_COUNT` 已接入 libs 的 `countChatPromptTokens`。
- 计数使用当前模型 chat template 和真实 tokenizer。
- `TOKEN_COUNT` 只接受 text / message-only 输入。
- `TOKEN_COUNT` 不处理 `rag` chunk，避免触发检索、索引或 cache 修改。
- LLM 侧不保留 token 估算器。
- 不使用字符长度、固定倍率或经验估算兜底。

已暴露结果：

- `LLMPrimitiveResultPayload.tokenCount`

### 2.4 usage telemetry

对应原文 `3.1 容量与运行态原语`。

已完成：

- 非流式 CHAT / TASK 返回 `LLMPromptResultPayload.usage`。
- 流式 CHAT / TASK 的 terminal 包返回 `usage`。
- usage 包含 `promptTokens`、`completionTokens`、`totalTokens`。
- `completionTokens` 不包含 COT。

### 2.5 embedding 原语

对应原文 `3.2 Embedding 原语`。

已完成：

- `LLM_PRIMITIVE_QUERY / EMBED` 支持批量文本向量化。
- 可选择是否回传 vector 本体。
- 返回向量维度。
- 返回 `embeddingModelName` 和 `embeddingNamespace`，供 AX 校验持久化向量是否仍可用。
- embedding 结果不混入普通 `LLMPromptResultPayload`。

### 2.6 能力快照原语

对应原文 `3.3 能力快照原语`。

已完成：

- `LLM_PRIMITIVE_QUERY / STATUS` 返回运行态快照。
- 快照包含 ready、模型加载状态、embedding 可用性、embedding 维度、上下文配置、队列状态、MTP 状态、模型名称和 profile。
- 快照包含 `embeddingModelName` 和 `embeddingNamespace`。
- 未知信息按保守值返回。

### 2.7 Cache 管理原语

对应原文 `3.4 Cache 管理原语`。

已完成：

- `LLM_CACHE_MANAGE` 保留并可用。
- 支持 index、query、evict all、evict content。
- 支持当前世界 cache 和全局 cache。

边界保持：

- 该能力适合外部知识库、规则库、模组资料。
- AX 私有动态记忆不应写入 LLM 内部 RAG cache。

### 2.8 AX 记忆接入方式

对应原文第 4 章。

已完成的 LLM 侧支撑：

- AX 可自行选取、折叠、拼接记忆。
- AX 可把整理后的记忆作为普通 `message` chunk 注入。
- LLM 不需要识别“这是 AX 记忆”。
- 外部知识库仍可继续走 `rag` chunk。

### 2.9 CHAT / TASK 通道约束

对应原文第 5 章。

已完成：

- `lane=CHAT` 保留 dialogue auth。
- `lane=TASK` 保留后台任务路径。
- CHAT / TASK 都支持 usage 回传。
- CHAT / TASK 流式请求都支持 terminal 包。
- CHAT / TASK 被取消或打断时可回传 `CANCELLED`。

## 3. 替代与未完成说明

### 3.1 不新增 AX 专用记忆聊天接口

对应原文 `6. 非目标`。

已采用上位方案：

```text
AX 自己管理记忆
  -> 组装 LLMPromptRequestPayload
  -> 通过 LLM_REQUEST 发送
```

因此不新增 AX 专用 LLM API。

### 3.2 embedding token count 不纳入主合同

AX 最终送入 LLM 的是 prompt，而不是 embedding 输入。

因此 prompt 预算统一依赖 `TOKEN_COUNT / countChatPromptTokens`，不单独提供 embedding token count。

### 3.3 RAG 注入精确裁剪尚未完成

当前已完成真实 token 计数原语。

但 LLM 内部 RAG chunk 注入阶段尚未完成基于真实 token count 的精确裁剪策略。该项不计入已完成。

`TOKEN_COUNT` 不作为“完整请求含 RAG 检索后 prompt 计数”接口使用；带 RAG 的完整请求计数属于后续无副作用规划能力。
