# AX 天枢助手文档索引

## 外部协议接入

- [AX 协议中心使用文档](AX_协议中心使用文档.md)：说明 AX 的 IA participant 身份、delivery capability、下游请求、事件订阅、空唤醒词语义和外部模块禁止绕过的边界。

## 1. 作用

本文只是索引，不再承载分期总纲。

## 2. 正文入口

- [AX_辅星一期_自然交互基线设计.md](./AX_辅星一期_自然交互基线设计.md)：一期，`core` 负责整体排布，`module` 负责 `ax_system`、`game_context`、`player_memory`、`recent_dialogue`、`current_input` 五块，其中 `player_memory` 对应 `AXMemory`。
- [AX_辅星二期_技能协作设计.md](./AX_辅星二期_技能协作设计.md)：二期，`core` 负责技能编排回流，`module` 负责技能模块和受控任务协作。
- [AX_Prompt编排与上下文检索设计.md](./AX_Prompt编排与上下文检索设计.md)：prompt 编排、上下文检索与五块装配。
- [AX_记忆策略设计.md](./AX_记忆策略设计.md)：Raw Turn、STM、E、双层索引检索、折叠注入和存储。

## 3. 说明

一期和二期共用同一套辅星记忆底座。技能协作只是在后续 turn 上扩能力，不重建记忆系统，不改写历史语义。
