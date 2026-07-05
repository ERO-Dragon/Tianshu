# AX 天枢助手文档索引

## 1. 作用

本文只是索引，不再承载分期总纲。

## 2. 正文入口

- [AX_辅星自然交互基线设计.md](./AX_辅星自然交互基线设计.md)：一期，`core` 负责整体排布，`module` 负责 `ax_system`、`game_context`、`player_memory`、`recent_dialogue`、`current_input` 五块，其中 `player_memory` 对应 `AXMemory`。
- [AX_辅星技能协作设计.md](./AX_辅星技能协作设计.md)：二期，`core` 负责技能编排回流，`module` 负责技能模块和受控任务协作。
- [AX_Prompt编排与上下文检索设计.md](./AX_Prompt编排与上下文检索设计.md)：prompt 编排、上下文检索与五块装配。
- [AX_记忆策略设计.md](./AX_记忆策略设计.md)：Raw Turn、STM、E、双层索引检索、折叠注入和存储。
- [AX_JJML_CTX预算与TASK思考需求.md](./AX_JJML_CTX预算与TASK思考需求.md)：2B `TASK + thinking` 历史问题收敛与 `maxTokens=0` 生成预算边界。
- [AX_LLM_CTX预算职责边界需求.md](./AX_LLM_CTX预算职责边界需求.md)：AX 对 LLM 最终安全 `contextTokenBudget` 的职责边界需求。

## 3. 说明

一期和二期共用同一套辅星记忆底座。技能协作只是在后续 turn 上扩能力，不重建记忆系统，不改写历史语义。
