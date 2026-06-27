# LLM 底层原语与 AX 记忆接入需求

## 1. 文档定位

本文是一份需求说明，不是实现规格。它描述 LLM 模块需要补充哪些底层原语，才能让 AX 自己管理记忆；同时说明现有 `chat` / `chatStream` / `task` / `taskStream` 仍然保留，且仍然是最终把请求送入 LLM 的主入口。

## 2. 总体原则

### 2.1 高阶接口继续保留

`chat`、`chatStream`、`task`、`taskStream` 继续作为高阶调用入口，供业务模块快速使用。

LLM 不需要因为 AX 记忆系统而新增一套专用“记忆聊天 API”。AX 负责把记忆内容整理成 prompt，最后仍然走现有请求路径。

### 2.2 原语能力需要补足

为了让 AX 自己管理记忆，LLM 需要补充更底层、可组合的原语能力，例如：

- token 计数
- embedding 向量化
- 运行时 usage telemetry
- 模型与能力快照
- cache 管理

这些原语是辅助能力，不是记忆生命周期管理器。

### 2.3 记忆由 AX 预组装

AX 的记忆内容应当由 AX 在送入 LLM 前自行选取、折叠、拼接。

换句话说：

- AX 记忆内容进入 LLM 时，应当只是普通 `message` 内容
- 不应依赖 LLM 自己的 RAG 记忆管线来“替 AX 记住”
- 外部知识库 RAG 可以继续复用现有能力
- AX 私有记忆不应混进 LLM 内部 cache 语义里

### 2.4 Dialogue auth 继续保留

凡是最终走 `lane=CHAT` 的用户可见请求，仍然必须保留 dialogue auth 语义：

- `dialogueSessionId`
- `requesterModuleId`
- `requesterParticipantId`
- `dialogueTurnId`

AX 记忆接入不会取消这层校验。

### 2.5 通信必须走协议中心

本文涉及的所有 LLM 访问，都必须通过协议中心转发，不允许业务模块直接调用 `LLMService`、`JavaLlamaServer` 或其他底层实现类。

允许的边界是：

- 业务模块 -> 协议中心 -> LLM 协议能力
- 业务模块 -> 协议中心 -> IA / LLM 授权链路

不允许的边界是：

- 业务模块直接访问 LLM 内部服务
- 业务模块直接绕过协议 payload 拼底层请求

## 3. 需要补充的底层原语

### 3.1 容量与运行态原语

LLM 侧需要提供稳定的容量计数和运行态回传能力，用于：

- prompt 预算计算
- 记忆窗口控制
- 压缩触发判断
- 后台任务成本评估
- 调试、校准和排障

需求要点：

- 能对文本、消息数组、请求块做真实 token 计数
- 能返回模型相关的 usage telemetry
- token 计数必须来自 libs 暴露的当前模型真实 tokenizer 与实际 chat template，不允许 LLM 侧估算

当前 usage 合同以 `promptTokens`、`completionTokens`、`totalTokens` 为主；推理状态事件里的生成量或恢复细节只用于运行态观测，不作为 AX 预算决策的主依据。

### 3.2 Embedding 原语

LLM 需要保留向量化能力，并能明确返回向量结果。

需求要点：

- 支持单条文本向量化
- 支持批量向量化
- 可识别 embedding 模型版本或标识
- 可提供向量维度信息

embedding 向量不应混进普通 `LLMPromptResultPayload` 里返回；它是独立原语，不是对话结果的一部分。

### 3.3 能力快照原语

AX 需要知道当前模型“能不能做、做到什么程度”。

建议至少能获取：

- 上下文窗口信息
- embedding 是否可用
- 生成 / 检索相关能力
- 当前模型标识或能力版本

这类信息用于动态伸缩，不用于硬编码固定参数。

### 3.4 Cache 管理原语

`LLM_CACHE_MANAGE` 可以继续保留，但它只适合外部知识库、静态资料、可复用语料。

不建议把 AX 私有记忆直接当作 LLM 内部 cache 来管理。

## 4. AX 记忆接入方式

### 4.1 送入 LLM 的方式

AX 记忆接入 LLM 时，默认应复用现有请求路径：

```text
AX memory selection
  -> AX 自己完成折叠 / 过滤 / 拼接
  -> 组装 LLMPromptRequestPayload
  -> 通过 chat / chatStream / task / taskStream 发送
```

也就是说，LLM 只负责“吃进已经准备好的 prompt”，不负责替 AX 选择记忆。

### 4.2 AX 私有记忆

AX 私有记忆建议作为普通 `message` 内容进入 prompt，例如：

- system prompt 中的记忆摘要
- user prompt 前的上下文片段
- assistant 历史片段
- 由 AX 预处理后的事实列表

不要为 AX 私有记忆额外走一层 LLM RAG 语义。

### 4.3 外部知识库

外部知识库、规则库、模组资料可以复用现有 `rag` chunk 和 cache 管线。

也就是说：

- 静态、可复用、可共享的内容，可以继续走 RAG
- 私有、动态、和玩家会话强绑定的记忆，交给 AX 自己拼 prompt

## 5. 请求与通道约束

### 5.1 CHAT

`lane=CHAT` 仍然是玩家可见对话的主路径。

要求：

- 保留 dialogue auth
- 保留 requester / session / turn 语义
- 允许 AX 把自己的记忆内容预填进 prompt
- 不要求 LLM 自己理解“这是 AX 记忆”

### 5.2 TASK

`lane=TASK` 继续用于后台任务，例如：

- 记忆压缩
- 原子事实抽取
- 记忆摘要生成
- 其他后台整理工作

TASK 仍然应走现有请求入口，只是内容由 AX 事先准备好。

## 6. 非目标

- 不把 AX 记忆生命周期交给 LLM
- 不新增“AX 专用记忆聊天接口”
- 不强制普通结果回传 raw vector
- 不要求知识库 RAG 和 AX 私有记忆共用同一套语义层
- 不把 token / embedding / telemetry 写死成唯一实现

## 7. 结论

这次扩充的核心不是“让 LLM 更懂记忆”，而是“让 LLM 提供更底层的可组合能力，同时保持现有高阶请求入口不变”。

AX 负责记忆策略、折叠、注入和生命周期；LLM 负责推理、向量化、统计与调度回传。两边边界清楚，系统才不会越长越糊。

## 8. 实施计划

这部分只是 LLM 侧的落地方案，不改 AX 代码。

1. 维持 `chat` / `chatStream` / `task` / `taskStream` 的现有行为不变。
2. 新增一组通过协议中心暴露的原语能力，用于 AX 未来做预算、向量化和运行态查询。
3. 原语能力拆成三类更清晰的协议面：
   - token 计数
   - embedding 向量化
   - 运行态与能力快照
4. 运行态查询返回保守值即可，未知就返回 `-1` 或不可用，不硬猜。
5. 只允许外部模块通过协议中心调用这些能力，不再依赖 `LLMService` 之类的直接共享服务入口。
6. `LLMService` 内部不得保留 token 估算路径；token 预算相关能力必须等待 libs 提供真实计数。
7. 如果能力缺口位于 libs，LLM 侧只声明接口和待完成项，不用特殊手段绕过或保底。
