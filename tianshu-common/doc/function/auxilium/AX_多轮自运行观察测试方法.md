# AX 多轮自运行观察测试方法

## 1. 目的

本文记录 AX 一期内部链路的多轮自运行观察测试方法，用于持续观察：

- 对话输入进入 AX 后，prompt 是否按预期组装。
- 日志假 RAG 是否作为静态知识命中进入 game_context。
- Raw Turn 是否自然触发 STM 压缩，而不是手动分解。
- STM 是否被结构化抽取为 E 集，并回写所属 STM 引用。
- E 是否通过 embedding 写入向量组。
- 多轮自续提问是否能推动近期对话、STM、E 和向量持续增长。

该测试只验证 AX 一期内部闭环，不代表正式静态 RAG schema，也不替代 runClient 端到端联调。

## 2. 测试入口

测试类：

```text
tianshu-common/src/test/java/com/rheinmetal/tianshu/function/auxilium/AXLongRunObserverSmokeTest.java
```

默认使用工作区根目录模型：

```text
Qwen3.5-2B-Q4_K_M.gguf
bge-large-zh-v1.5-q4_k_m.gguf
```

不要使用 DeepSeek 蒸馏 9B 模型。测试代码会拒绝文件名包含 deepseek 的 chat 模型。

## 3. 运行命令

最小 1 轮 smoke：

```powershell
.\gradlew.bat :tianshu-common:test --tests "com.rheinmetal.tianshu.function.auxilium.AXLongRunObserverSmokeTest" "-Dtianshu.ax.longrun.smoke=true" "-Dtianshu.ax.longrun.rounds=1"
```

推荐 3 轮观察：

```powershell
.\gradlew.bat :tianshu-common:test --tests "com.rheinmetal.tianshu.function.auxilium.AXLongRunObserverSmokeTest" "-Dtianshu.ax.longrun.smoke=true" "-Dtianshu.ax.longrun.rounds=3"
```

可以覆盖模型路径：

```powershell
.\gradlew.bat :tianshu-common:test --tests "com.rheinmetal.tianshu.function.auxilium.AXLongRunObserverSmokeTest" "-Dtianshu.ax.longrun.smoke=true" "-Dtianshu.ax.longrun.rounds=3" "-Dtianshu.llm.smoke.model=D:\\models\\Qwen3-0.6B-Q4_K_M.gguf" "-Dtianshu.llm.smoke.embeddingModel=D:\\models\\bge-large-zh-v1.5-q4_k_m.gguf"
```

## 4. 自运行方式

测试不是固定脚本问答。每轮流程为：

```text
当前问题
  -> AXTurnOrchestrator.startTurn(...) 
  -> AX 组装 prompt 并调用 CHAT
  -> AX 输出回答
  -> 后台维护自然触发 STM/E/vector
  -> 测试脚本额外调用本地 LLM，根据上一问和 AX 回答生成下一问
  -> 下一轮继续
```

下一问生成走 TASK lane，跳过 IA 仲裁，只作为测试驱动器使用；真实主链路仍然从 IA 授权输入进入 AX。

## 5. 假 RAG 数据

在正式静态 RAG 库完成前，测试用 NeoForge 运行日志模拟静态知识命中：

```text
tianshu-neoforge/run/logs/latest.log
tianshu-neoforge/run/logs/debug.log
```

测试会把日志中的有效行作为一条条 `AXKnowledgeHit` 内容放入 `<game_context>` 的静态知识组。它只是在 AX 语义边界内的测试替身，不生成正式 jsonl，也不写入未来静态 RAG 数据库；正式 RAG schema 完成后再由静态知识接入链路替换这个 planner。

## 6. 报告位置

每次运行会覆盖写入：

```text
tianshu-common/build/reports/ax/long-run-observer-smoke.md
```

重点看这些段落：

- 顶部计数：rounds、finalRawTurns、finalRetrievedStm、finalRecentStm、storageStm、storageEvents、storageVectors。
- Fake Log RAG Source：本次用作假 RAG 的日志行。
- Rounds：每轮问题、回答、raw turn、recent STM、retrieved STM，以及 E -> STM Retrieval Trace。
- Prompt Snapshots：首轮、中间轮、末轮最终 CHAT prompt。
- Extraction Format Smoke：当前小模型对结构化 E 抽取格式的单点探针。
- Task Response Summary：压缩和抽事实 TASK 的原始模型输出。
- Memory Store Snapshot：实际落盘 STM、E、向量记录和可重建检索索引快照摘要。

## 7. 当前验收口径

当前只要求链路正确，不要求提示词语义最终定型：

- CHAT 使用 maxTokens=0、thinking=false。
- 压缩和 E 抽取使用 TASK lane、maxTokens=0、thinking=true、captureThinkingContent=false。
- AX 不在 smoke 或主链路里额外指定 temperature / topK / topP 等采样参数，由 LLM 层按 lane 和模型默认策略兜底。
- LLM 协议应把可见文本和 `thinkingContent` 结构化分离；AX 写入 STM/E 时只消费可见文本，不再维护思考标签文本过滤补丁。
- E 抽取优先按严格 JSON array 解析；JSON 失败时降级按行解析，剥除 markdown 围栏与控制符，保留行首编号或列表标记去除后的纯文本事实。
- 包含 Unicode replacement character 的 fact 不入库；超长（>512 字符）fact 不入库；重复 fact 去重。
- E 只补齐代码能确定的客观元数据：stmId、worldId、createdAtMillis、happenedAtMillis、sourceKind 等。E 不直接注入上下文，不保存 token 预算字段。
- 不做实体、位置、维度、标签的 if-contains 推断。

## 8. TASK + think 与 ctx 预算

当前观察到的问题不是“模型不支持 think”。早期 2B 模型在 `LLM_REQUEST lane=TASK`、`thinking=true`、`captureThinkingContent=false`、`maxTokens=0` 时触发 `Decode failed with status 1`，后续定位为生成预算没有按 ctx 剩余空间钳制：`maxTokens=0` 表示不指定业务输出上限，但仍必须受实际 prompt token 数、已加载 ctx 和 `promptMarginTokens` 约束。

AX 侧要求：

- 不通过关闭 TASK thinking 来掩盖底层问题。
- 不把 TASK 降级成 CHAT 来绕开问题。
- LLM 响应的 `text` 必须是可见输出；如需观察思考内容，通过结构化 `thinkingContent` 单独读取。
- TASK 失败时记录脱敏错误码和 lane / thinking / captureThinkingContent / modelId，不记录完整 prompt 或玩家正文。

LLM / libs 侧已收敛的能力边界：

- `TASK + thinking=true + captureThinkingContent=false` 必须稳定返回可见正文，并把 thinking/COT 与 `text` 分离。
- `maxTokens > 0` 和 `maxTokens == 0` 都必须按 ctx 剩余空间钳制 completion 预算。
- `TOKEN_COUNT` 只需对 message-only 输入给出贴近当前 tokenizer / chat template 的计数；AX 不要求它处理最终带 rag chunk 的请求。

ctx 预算职责边界不在本文展开，详见 `AX_LLM_CTX预算职责边界需求.md`。

## 9. 已知观察项

2B 模型在结构化输出上比 0.6B 更稳定，但仍可能在长背景下把建议句抽成事实。当前 parser 优先 JSON、失败降级按行解析，会拒绝含 replacement character 的 fact；建议句的历史化表达主要依赖 memory task prompt，后续提示词重设计时需要专门处理。

如果报告中出现以下情况，需要继续排查：

- storageStm 不增长：检查 Raw Turn 压缩阈值和后台维护是否运行。
- storageEvents 不增长：检查抽事实 TASK 是否返回合格 JSON array。
- storageVectors 不增长：检查 LLM_PRIMITIVE_QUERY / STATUS 是否返回可用 embedding namespace。
- finalRetrievedStm 长期为 0：说明当前问题没有命中已向量化 E，或检索 query 与事实距离太远；这不一定是链路错误，需要结合问题内容判断。
- Task Response Summary 出现 think 内容：可以出现在报告的原始模型响应里，但不能出现在 Memory Store Snapshot 的 STM/E 正文里。
