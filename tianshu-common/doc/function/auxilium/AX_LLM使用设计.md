# AX 模块与 LLM 使用设计

## 1. 文档定位

AX 已从 LLM 模块中拆出。AX 是对话业务模块，LLM 是推理能力模块。本文记录拆分后的 AX 如何使用 IA 仲裁和 LLM task 能力完成多 NPC、多模组、多世界对话。

## 2. 边界

| 模块 | 职责 |
|---|---|
| AX | 助手/NPC 身份、提示词、对话上下文、记忆策略、LLM 请求组装、输出归属 |
| IA | 对话仲裁、参与者管理、LLM 使用授权、delivery 所有权 |
| LLM | 独立推理服务管理、task 调度、HTTP 调用、结果回传 |
| UI/TTS | 根据 AX/dialogue 语义展示或播放，不直接消费底层 LLM 全局流 |

AX 不再访问 LLM 内部引擎，也不应依赖旧的 `LLM_STREAM` 公共广播。AX 通过协议能力请求 LLM：

```text
AX
  -> IA 请求对话/LLM 使用授权
  -> 收到 delivery / authorization
  -> 构造 LlmTaskRequestPayload（module.ax / tianshu.AX + usageKind=INTERACTIVE + IA 授权上下文）
  -> requestCapability(LLM_TASK_REQUEST)
  -> 接收 LLM_TASK_STREAM_CHUNK / LLM_TASK_RESULT
  -> 以 AX 自己的对话语义输出给 UI/TTS
```

## 3. 对话所有权

在多 NPC、多模组、多世界场景下，任何 LLM 输出都必须能回答：

- 这句话属于哪个世界。
- 属于哪个模组或 agent。
- 属于哪个 NPC/助手实例。
- 属于哪次 IA delivery。
- 是否仍然拥有输出权限。

因此 AX 使用 LLM 时必须保持 parent envelope 关系。LLM chunk 只是模型生成的底层文本，不天然代表可以播放或显示。AX 需要结合 IA delivery 判断是否继续输出。

## 4. LLM 请求组装

AX 构造 `LlmTaskRequestPayload` 前，应先完成业务语义裁剪，并带上 IA 授权 delivery 的租约信息：

1. System prompt：NPC 身份、说话风格、世界规则、当前对话目标。
2. Recent context：有限窗口内的近期对话。
3. Dynamic facts：本 tick 或本轮对话相关的玩家状态、环境状态、事件事实。
4. Memory hints：由 AX 记忆策略选择的长期记忆摘要或索引结果。
5. Output constraints：长度、语气、是否允许动作建议、是否需要 JSON 等格式约束。
6. Routing：`moduleId=module.ax`、`agentId=tianshu.AX`，world 由 LLM/common 自动补齐。
7. Authorization context：`authorization.sessionId`、`authorization.turnId`。`moduleId/agentId` 作为 requester 身份传给 LLM。

LLM 模块不会信任 AX 传入 owner 或 lease；`INTERACTIVE` 请求会由 LLM gateway 统一向 IA 查询授权，IA 根据 session store 判断是否放行。

## 5. Dynamic facts 与长期记忆

AX 需要区分三类上下文：

| 类型 | 生命周期 | 进入方式 |
|---|---|---|
| 对话 prompt | 本次请求 | messages |
| 动态事实 | 本轮或短期状态 | dynamicFacts / dynamic_rag |
| 长期记忆 | 世界/agent 持久状态 | AX 维护，必要时写入 memory rag 文件或直接放入 messages |

不要把所有内容都塞进长期记忆。长期记忆应是稳定、可复用、跨会话仍有价值的事实。

## 6. 流式输出

AX 接收 LLM stream chunk 后，应做最小业务处理再输出：

- 检查 IA delivery 是否仍有效。
- 检查本轮输出是否已经取消。
- 按 NPC/agent 归属转发到 UI/TTS。
- 必要时过滤不可见的模型元信息。
- 结束 chunk 到达后关闭本轮输出。

AX 不应让 UI/TTS 直接订阅底层 LLM task stream。否则多个 NPC 并发时会丢失说话者、音色、位置和权限。

## 7. 失败与取消

AX 应区分：

| 状态 | 处理 |
|---|---|
| LLM failed | 结束本轮输出，可给 UI 一条非沉浸式错误状态 |
| LLM cancelled | 静默停止或按 IA 规则切换到新说话者 |
| IA revoked | 停止转发后续 LLM chunk |
| timeout | 结束本轮，释放会话占用 |

模型失败不应污染长期记忆，也不应被当成 NPC 的真实发言。

## 8. 健壮性要求

AX 使用 LLM 时必须遵守：

1. 不直接管理 JavaLlamaServer 进程。
2. 不调用 LLM engine 内部方法。
3. 不绕过 IA 仲裁输出玩家可见文本。
4. 不把完整 prompt、记忆、模型回答写入普通日志。
5. 不把长期记忆更新逻辑放回 LLM 模块。
6. 所有玩家可见输出都绑定明确的 delivery/agent/world。
7. LLM task 失败必须释放 AX 本轮状态。
