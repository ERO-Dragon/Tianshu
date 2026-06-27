# LLM 对 libs 底层 token 与 usage 能力需求

## 1. 文档定位

本文记录 LLM 模块向 libs 层提出的底层能力需求。

这些能力是 LLM 原语协议落地的底层合同。libs 必须提供真实能力；LLM 侧只按 libs 必备合同接入，不允许用字符长度、经验比例、固定倍率等估算方式保底。

## 2. 总体原则

1. token 计数必须来自当前已加载模型的真实 tokenizer。
2. chat/task token 计数必须使用与实际推理一致的 chat template / sampler 模板参数。
3. usage telemetry 必须由 libs 在实际请求执行路径中产生，而不是由上层重算。
4. token / usage 能力是必备合同，LLM 模块按该合同直接接入。

## 3. 推理请求 usage 回传

### 3.1 chat / task 同步结果

LLM 需要 libs 提供结构化结果，例如：

```java
public final class LlmGenerationResult {
    public String text();
    public LlmTokenUsage usage();
}

public record LlmTokenUsage(int promptTokens, int completionTokens) {
    public int totalTokens();
}
```

需求：

- `chat(...)` 返回最终文本时同时返回 usage。
- `task(...)` future 完成时同时返回 usage。
- `promptTokens` 必须是实际送入模型的 formatted prompt token 数。
- `completionTokens` 必须是归一化后对上层可见的回答 token 数，不包含 COT。
- `totalTokens()` 必须由 `promptTokens + completionTokens` 得到。

### 3.2 流式 chat / task

当前 stream 只通过 `Consumer<String>` 回传 token 文本。

LLM 需要 stream 在结束时拿到终态包 `LlmStreamFinish`，例如：

```java
public enum StreamFinishType {
    COMPLETED,
    CANCELLED,
    FAILED
}

public final class LlmStreamFinish {
    public StreamFinishType type();
    public LlmTokenUsage usage();
    public Throwable error();
}
```

需求：

- stream token 文本仍按现有方式逐片回调。
- stream 完成、取消、失败时都应回传终态包。
- usage 不应混入文本 token 流。

## 4. 独立 token count 原语

LLM 需要提供随时可调用的 token count 查询接口。

建议 libs 提供：

```java
int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler);
```

要求：

- `countChatPromptTokens` 必须应用实际 chat template。
- `SamplerConfig.enableThinking`、`thinkingMode`、`chatTemplateKwargs` 等会影响模板的字段必须参与计数。
- 如果模型未加载、tokenizer 不可用或模板无法应用，应抛出明确异常。
- 不接受 approximate / estimate 命名或实现。

## 5. 能力快照

LLM 的 `STATUS` 协议快照继续由 LLM 模块聚合当前配置和运行态信息。模型名称和 profile 由 LLM 加载侧天然持有，不要求 libs 在 token / usage 第一版合同中额外暴露 tokenizerId 或 modelId。

## 6. 对 LLM 模块的阻塞项

libs 1.0.5 已补齐第一版 token / usage 合同。LLM 模块当前接入状态：

| LLM 能力 | 当前状态 | 阻塞原因 |
|---|---|---|
| `LLM_PRIMITIVE_QUERY / TOKEN_COUNT` | 可接入 | libs 已提供 `countChatPromptTokens` |
| chat/task result usage 回传 | 可接入 | libs 已提供 `chatWithUsage` / `taskWithUsage` |
| stream terminal 包回传 | 可接入 | libs 已提供 `LlmStreamFinish` |
| RAG 注入 token budget 精确裁剪 | 后续接入 | 需要 LLM 侧在 RAG prompt 拼装阶段基于真实计数做裁剪 |

## 7. 非目标

- 不要求 AX 直接调用 libs。
- 不要求 LLM 用自己的 tokenizer 实现绕过 libs。
- 不接受字符长度估算、固定倍率估算或经验校准作为 token count。
- 不把 usage 写入模型文本结果。
- 不要求这次同时改动 AX 记忆生命周期。

## 8. 最小落地顺序建议

1. LLM 侧 `TOKEN_COUNT` 调用 `countChatPromptTokens`。
2. LLM 侧非流式 chat/task 统一调用 `chatWithUsage` / `taskWithUsage`。
3. LLM 侧流式 chat/task 统一接收 `LlmStreamFinish` 并通过协议 terminal 包回传。
4. 后续再在 RAG prompt 注入阶段使用真实计数做预算裁剪。
