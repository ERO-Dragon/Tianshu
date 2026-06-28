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
Qwen3-0.6B-Q4_K_M.gguf
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

测试会把日志中的有效行作为一条条 AXKnowledgeHit 内容放入 game_context 的静态知识分区。这个逻辑只存在于 smoke test，不进入 AX 主链路。

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
- Extraction Format Smoke：0.6B 对结构化 E 抽取格式的单点探针。
- Task Response Summary：压缩和抽事实 TASK 的原始模型输出。
- Memory Store Snapshot：实际落盘 STM、E、向量记录和可重建检索索引快照摘要。

## 7. 当前验收口径

当前只要求链路正确，不要求提示词语义最终定型：

- CHAT 使用 maxTokens=0、thinking=false。
- 压缩和 E 抽取使用 TASK lane、maxTokens=0、thinking=true、includeThinkingContent=false。
- 模型返回的 think 内容必须在写入 STM/E 前清理。
- E 抽取只接受严格 JSON array，元素必须只有 fact 字符串字段。
- 非 JSON array、JSONL、对象、额外字段、非字符串 fact、包含 Unicode replacement character 的 fact 都不入库。
- E 只补齐代码能确定的客观元数据：stmId、worldId、createdAtMillis、happenedAtMillis、sourceKind、token 估算等。
- 不做实体、位置、维度、标签的 if-contains 推断。

## 8. 已知观察项

0.6B 模型可以在部分回合输出严格 JSON array，但长背景下可能产生乱码替换符或把建议句抽成事实。当前 parser 会拒绝含 replacement character 的 fact；建议句的历史化表达主要依赖 memory task prompt，后续提示词重设计时需要专门处理。

如果报告中出现以下情况，需要继续排查：

- storageStm 不增长：检查 Raw Turn 压缩阈值和后台维护是否运行。
- storageEvents 不增长：检查抽事实 TASK 是否返回合格 JSON array。
- storageVectors 不增长：检查 LLM_PRIMITIVE_QUERY / STATUS 是否返回可用 embedding namespace。
- finalRetrievedStm 长期为 0：说明当前问题没有命中已向量化 E，或检索 query 与事实距离太远；这不一定是链路错误，需要结合问题内容判断。
- Task Response Summary 出现 think 内容：可以出现在报告的原始模型响应里，但不能出现在 Memory Store Snapshot 的 STM/E 正文里。
