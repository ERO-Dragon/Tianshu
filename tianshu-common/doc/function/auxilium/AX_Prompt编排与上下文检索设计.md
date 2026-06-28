# AX Prompt 编排与上下文检索设计

## 1. 文档定位

本文描述 AX 在获得 IA 授权后，如何把玩家当前输入、动态环境、静态知识、玩家记忆和近期对话组合成一次 `LLM_REQUEST lane=CHAT`。

本文只讨论 prompt 编排和上下文检索链路，不定义 AX 记忆字段协议，不定义 LLM RAG cache 的底层文件格式，也不定义二期以后的工具调用协议。

相关文档：

- [AX_天枢助手模块架构与拆分执行计划.md](./AX_天枢助手模块架构与拆分执行计划.md)：定义 AX 模块身份、内部子系统和分期边界。
- [AX_记忆策略设计.md](./AX_记忆策略设计.md)：定义 Raw Turn、STM、E、检索、存储和记忆注入边界。
- [../llm/LLM接口设计.md](../llm/LLM接口设计.md)：定义 LLM_REQUEST、LLM_PRIMITIVE_QUERY、LLM_CACHE_MANAGE 等协议能力。

## 2. 核心原则

AX 的上下文不是简单的“静态、动态、记忆”三个池子并列塞入 prompt。更准确的模型是三条链路：

```text
玩家输入
  -> 直接检索静态知识库

玩家输入
  -> 检索动态环境
  -> 用命中的环境信息检索静态知识库

玩家输入
  -> 检索玩家记忆 E
  -> 映射 STM
  -> 注入玩家记忆片段
```

其中：

- 静态知识用于解释 MC 原版、模组、规则、资料和玩法。
- 动态环境用于理解“这个、手上、附近、我面前”等现场指代，并为静态知识检索提供更准确的 query context。
- 玩家记忆用于解释玩家和 AX 的历史，不进入 LLM RAG cache。

动态环境既可以作为 prompt 的 `<game_context>` 注入，也可以作为静态知识 RAG 的 query 扩展来源。二者是两个用途，不应混成长期记忆。

## 3. 输入与输出

输入来源：

- IA 授权后的当前玩家输入。
- 实时窗口中的近期完整对话轮次。
- AX 通过协议中心能力请求获取的动态环境快照。
- LLM RAG cache 中的静态知识库。
- AX 私有玩家记忆系统。
- LLM `STATUS` / `TOKEN_COUNT` 返回的模型与预算信息。

输出目标：

```text
message chunk:
  ax_system
  game_context
    dynamic_context
    static_knowledge_hits
  player_memory
    retrieved_stm
    recent_stm
  provided_context
  recent_dialogue
  current_input
```

玩家记忆、动态环境、静态知识命中结果都由 AX 排版为普通 message 注入。静态知识库可以复用 LLM 的 RAG cache 能力，但最终进入玩家可见 CHAT 请求前，应先由 AX 获得命中内容，再放入 `<game_context>`，避免 LLM 模块在最终请求中追加位置不受控的 rag prompt。

## 4. 请求编排流程

推荐一期流程：

```text
1. 校验 IA 授权和 session owner
2. 规范化当前输入
3. 读取近期完整对话轮次
4. 通过 `PRESENCE.QUERY_CONTEXT` 等能力请求获取本轮动态环境快照
5. 用当前输入检索动态环境，得到相关环境事实
6. 用当前输入直接规划静态知识 RAG query
7. 用 当前输入 + 命中的环境事实 规划静态知识 RAG query
8. 在静态 RAG 可用时解析静态知识命中内容
9. 检索玩家记忆 E，并映射到 STM 注入片段
10. 按模型预算选择 message 分区内容
11. 组装单一 message chunk
12. 通过 LLM_REQUEST lane=CHAT 调用 LLM
```

第 6 步和第 7 步是并存关系：

- 直接静态 RAG 解决普通知识问题，例如“钻石镐怎么修”“下界合金怎么做”。
- 动态引导静态 RAG 解决现场指代问题，例如“我手上这个能干嘛”“面前这个方块怎么用”。

实现上可以把两路 query 合并后检索，也可以保留多个 query context。文档只固定职责，不固定检索协议字段。无论静态知识由什么底层能力返回，最终 prompt 中都作为 `<game_context>` 的一部分出现。

## 5. 动态环境检索

动态环境是当前游戏状态的短 TTL 事实，例如：

- 当前世界、维度、位置粗信息。
- 玩家正在看的方块、实体或物品。
- 附近关键方块、实体、结构。
- 背包或装备中的关键物品。
- 当前打开的界面。
- 近期世界事件。

动态环境检索的目标不是“记住历史”，而是给当前输入补齐现场语义。

本轮 prompt 所需的动态环境必须通过协议中心能力请求/响应获取，不能依赖 topic 广播作为权威输入：

```text
AX
  -> PRESENCE.QUERY_CONTEXT
  -> NeoForge 映迹模块或其他平台上下文模块回传本轮环境事实
  -> AX 过滤、排序并注入 <game_context>
```

原因：

- 本轮 prompt 需要确定的时序边界。
- AX 需要知道某次环境快照是否属于当前 turn。
- provider 未注册、超时或失败时，AX 可以立即降级到 IA delivery 已携带的上下文。
- topic 只适合非关键状态广播、观测和调试，不得作为本轮 prompt 的权威动态环境来源。

```text
当前输入：“这个怎么用？”
  -> 动态环境检索命中：玩家准星指向 minecraft:enchanting_table
  -> 静态知识 query context：enchanting table / 附魔台 / 当前输入
  -> 静态知识 RAG 召回附魔台用法
```

动态环境可以使用 RAG-like 的检索方式，但它不等同于 LLM 静态 RAG cache：

- 可以是内存索引、短 TTL 文件、轻量向量索引或规则筛选。
- 不写入 AX 长期记忆。
- 不写入 LLM RAG cache。
- 只保存必要的短事实，不保存完整世界快照或 NBT。

## 6. 静态知识 RAG

静态知识包括 MC 原版资料、模组说明、规则文档、玩法知识和项目内置资料。

当前静态 RAG 库尚未搭建时，本节作为架构预留，不阻塞一期其他链路。AX 应先保证 IA 授权、动态环境能力请求、玩家记忆检索注入、prompt 分区和输出闭环稳定；静态知识 RAG 接入后再启用第 6、7 步相关 query。

静态知识由 LLM RAG cache 管理：

```text
AX 准备静态资料
  -> LLM_CACHE_MANAGE INDEX
  -> LLM 模块生成并缓存向量
  -> AX 按当前输入 / 动态环境 query 获取命中内容
  -> AX 将命中内容渲染进 <game_context>
```

AX 不直接写 LLM cache 文件，不直接访问 RAG cache 二进制索引。

静态知识有两种 query 来源：

```text
直接静态 RAG:
  当前输入
    -> static knowledge query

动态引导静态 RAG:
  当前输入
    -> dynamic environment hits
    -> 当前输入 + 方块/物品/实体/维度等规范化环境事实
    -> static knowledge query
```

动态环境命中后，应优先把环境事实规范化为稳定标识，例如物品 ID、方块 ID、实体 ID、模组 ID、维度 ID 或结构名，而不是把完整环境快照塞进 query。

## 7. 玩家记忆注入

玩家记忆走 AX 私有记忆系统，不走 LLM RAG cache。

```text
当前输入
  -> E 检索
  -> STM 有效映射
  -> STM 贡献分计算
  -> 可选 STM 链式发散
  -> 按预算注入 player_memory
```

玩家记忆可以参考动态环境事实，但不应与静态知识混进同一个无差别文本池：

- 静态知识解释游戏规则和资料。
- 玩家记忆解释玩家历史和 AX 与玩家之间发生过的事情。
- 动态环境解释当前现场。

玩家记忆注入必须保留来源边界，避免 LLM 把“规则知识”和“玩家历史”混为一谈。

## 8. Prompt 分区

推荐逻辑结构：

```xml
<ax_system>
辅星身份、语气、行为边界、安全约束、回答风格
</ax_system>

<game_context>
当前环境、短 TTL 动态事实、命中的现场指代、已解析的静态知识命中
</game_context>

<player_memory>
先放 E 检索命中的 STM，再放近期 STM；附属消息跟随对应 STM
</player_memory>

<recent_dialogue>
实时窗口内的近期完整对话轮次。AX/玩家对话和游戏聊天栏消息按时间线交错呈现。
</recent_dialogue>

<current_input>
玩家当前输入
</current_input>
```

实现上不要求这些标签全部在同一个 system message 中。标签是 prompt 排版约定，不是存储协议。最终可拆成：

```text
message chunk:
  system: ax_system + 编排说明
  system: game_context
  system: player_memory
  system: provided_context
  system: recent_dialogue
  user: current_input
```

不在玩家可见文本里解释这些标签或内部机制。

XML-like 包裹、列表前缀、小标题和聊天行格式都属于 prompt 排版资源，不应硬编码在 Java 业务逻辑里。AX common 内置 `ax_prompt_texts.json` 作为默认目录，运行时可释放到 AX 配置目录供后续覆盖；Java contributor 只负责选择语义槽位、传入变量并决定 LLM message role。

## 9. 预算分配

AX 需要根据当前模型能力控制上下文预算。预算分配不在本文写死比例，但应遵守优先级：

```text
当前输入 > 系统约束 > 近期对话 > 动态环境 > 玩家记忆 > 静态知识
```

说明：

- 当前输入和系统约束不可丢。
- 近期对话保留完整轮次，不在句中截断。
- 动态环境只保留和当前输入相关的事实。
- 玩家记忆按 STM 粒度注入，完整 STM 放不下则跳过。
- 静态知识命中内容按 `<game_context>` 内部预算控制；静态 RAG 检索本身的预算在检索阶段控制。
- 外部知识和玩家记忆不能混入同一个文本池做无差别截断。

需要 token 估算时，AX 可以通过 `LLM_PRIMITIVE_QUERY / TOKEN_COUNT` 对 text/message-only 内容计数。最终 CHAT prompt 应由 AX 组装为 message-only；如某些底层检索流程需要 rag chunk，不应把该 rag chunk 直接混入最终玩家可见请求做预算兜底。

## 10. 面向 Agent 的扩展性

一期的 prompt 编排流程可以是固定管线，但结构上应允许二期 agent 能力插入。

应预留的扩展点：

```text
ContextNeedDecider
  一期可由规则实现，二期可由 IntentRouter 接管

QueryContextBuilder
  分别构造直接静态 RAG、动态引导静态 RAG、玩家记忆检索 query

PromptContributor
  每类上下文独立贡献 prompt 分区

ToolResultContributor
  二期以后把工具结果归一化后加入 prompt

ExecutionPolicy
  控制权限、预算、超时、脱敏和可见性
```

这些扩展点不是要求一期创建复杂框架，而是要求一期不要把所有逻辑硬编码在一个不可拆分的方法里。二期引入工具调用时，应能把“工具结果”作为新的上下文来源加入 prompt，而不是重写整个对话链路。

## 11. 协议与安全

AX 对 LLM 的所有访问必须经过协议中心。

允许：

- `ProtocolCapabilities.LLM_REQUEST`
- `ProtocolCapabilities.LLM_PRIMITIVE_QUERY`
- `ProtocolCapabilities.LLM_CACHE_MANAGE`

禁止：

- 直接调用 `LLMService`。
- 直接访问 `JavaLlamaServer`。
- 直接写 LLM RAG cache 二进制文件。
- 让 UI/TTS 直接订阅底层 LLM stream。

玩家可见对话必须携带 IA 授权上下文。后台 TASK 不伪造对话授权。

普通日志禁止输出完整玩家输入、prompt、动态环境完整快照、玩家记忆正文、RAG hit 明细和完整 LLM response。

## 12. 最终原则

- 直接静态 RAG 和动态引导静态 RAG 是两条不同链路。
- 动态环境先服务当前指代，再服务静态知识检索扩展。
- 玩家记忆由 AX 自己检索和注入，不进入 LLM RAG cache。
- 静态知识复用 LLM RAG cache，不污染 AX 私有记忆。
- Prompt 编排必须保持分区，不做无差别文本池。
- 一期是固定管线，但必须保留二期 agent 化的扩展位置。
- 所有跨模块调用走协议中心。
