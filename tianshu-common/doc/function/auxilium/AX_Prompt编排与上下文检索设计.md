# AX Prompt 编排与上下文检索设计

## 1. 文档定位

本文描述 AX 在获得 IA 授权后，如何把玩家当前输入、动态环境、静态知识、玩家记忆和近期对话组合成一次 `LLM_REQUEST lane=CHAT`。

本文只讨论 prompt 编排和上下文检索链路，不定义 AX 记忆字段协议，不定义 LLM RAG cache 的底层文件格式，也不定义二期以后技能协作的技能执行协议。

相关文档：

- [AX_记忆策略设计.md](./AX_记忆策略设计.md)：定义 Raw Turn、STM、E、检索、存储和记忆注入边界。
- [AX_辅星自然交互基线设计.md](./AX_辅星自然交互基线设计.md)：定义一期边界。
- [AX_辅星技能协作设计.md](./AX_辅星技能协作设计.md)：定义二期边界。
- [../llm/LLM接口设计.md](../llm/LLM接口设计.md)：定义 LLM_REQUEST、LLM_PRIMITIVE_QUERY、LLM_CACHE_MANAGE 等协议能力。

## 2. 核心原则

AX 的 prompt 输入不是简单的“静态、动态、记忆”三个池子并列塞入 prompt。更准确的模型是三条链路：

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
- 动态环境用于命中本轮动态事实，理解“这个、手上、附近、我面前”等现场指代，并为静态知识检索提供更准确的 query 材料。
- 玩家记忆用于解释玩家和 AX 的历史；权威数据、E/STM 语义和最终注入都由 AX 管理。LLM RAG 只可承接 AX 私有、可重建的检索投影，不作为玩家记忆权威库，也不混入 shared 静态知识库。

命中的动态事实进入 `<game_context>` 的 `dynamic_content`，同时作为动态引导静态 RAG 的 query 材料。动态事实和动态事实命中的静态 RAG 内容属于同一条动态内容链路，不应混成长期记忆。

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
    static_content
    dynamic_content
  player_memory
    retrieved_stm
    recent_stm
  recent_dialogue
  current_input
```

`game_context` 内部只承载两类组装内容：

- `static_content`：仅由当前输入直接检索静态 RAG 库得到的命中内容。
- `dynamic_content`：先用当前输入检索动态环境得到的命中动态事实，以及用这些动态事实扩展静态 RAG query 得到的命中内容。

玩家记忆、静态内容、动态内容、近期对话和当前输入都由 AX 排版为普通 message 注入。静态知识库可以复用 LLM 的 RAG cache 能力，但最终进入玩家可见 CHAT 请求前，应先由 AX 获得命中内容，再放入 `<game_context>`，避免 LLM 模块在最终请求中追加位置不受控的 rag prompt。

实现上，静态知识检索结果先被适配为 AX 自己的知识命中对象，例如 `AXKnowledgeHit`。Prompt 编排层只消费 AX 语义类型，不直接消费 `LLMPromptRequestPayload.ChunkPayload.rag`。这样正式 LLM RAG cache 返回、端到端测试桩或未来替代检索实现，都必须先经过同一个 AX 语义边界，再进入 `<game_context>` 渲染流程。

## 4. 请求编排流程

推荐一期流程：

```text
1. 校验 IA 授权和 session owner
2. 规范化当前输入
3. 读取近期完整对话轮次
4. 通过 `PRESENCE.QUERY_CONTEXT` 等能力请求获取本轮动态事实候选
5. 用当前输入对本轮动态环境候选做临时动态事实检索，得到命中动态事实
6. 用当前输入直接规划静态知识 RAG query
7. 用 当前输入 + 命中的动态事实 规划动态引导静态知识 RAG query
8. 在静态 RAG 可用时解析静态知识命中内容
9. 检索玩家记忆 E，并映射到 STM 注入片段
10. 按模型预算选择 message 分区内容
11. 组装单一 message chunk
12. 通过 LLM_REQUEST lane=CHAT 调用 LLM
```

第 6 步和第 7 步是并存关系：

- 直接静态 RAG 解决普通知识问题，例如“钻石镐怎么修”“下界合金怎么做”。
- 动态引导静态 RAG 解决现场指代问题，例如“我手上这个能干嘛”“面前这个方块怎么用”。

实现上可以把两路 query 合并后检索，也可以保留多个 query 通道。文档只固定职责，不固定检索协议字段。无论静态知识由什么底层能力返回，最终 prompt 中都作为 `<game_context>` 的一部分出现。

## 5. 动态环境检索

动态环境是当前游戏状态的短 TTL 事实，例如：

- 当前世界、维度、位置粗信息。
- 玩家正在看的方块、实体或物品。
- 附近关键方块、实体、结构。
- 背包或装备中的关键物品。
- 当前打开的界面。
- 近期世界事件。

动态环境检索的目标不是“记住历史”，而是给当前输入补齐现场语义，并产出本轮可解释的动态事实。

本轮 prompt 所需的动态环境必须通过协议中心能力请求/响应获取，不能依赖 topic 广播作为权威输入：

```text
AX
  -> PRESENCE.QUERY_CONTEXT
  -> NeoForge 映迹模块或其他平台动态环境 provider 回传本轮事实候选
  -> AX 过滤、排序后作为 dynamic_content 的动态事实部分进入 <game_context>
```

原因：

- 本轮 prompt 需要确定的时序边界。
- AX 需要知道某次环境快照是否属于当前 turn。
- provider 未注册、超时或失败时，AX 可以立即降级到 IA delivery 已携带的上下文。
- topic 只适合非关键状态广播、观测和调试，不得作为本轮 prompt 的权威动态环境来源。

```text
当前输入：“这个怎么用？”
  -> 动态环境检索命中：玩家准星指向 minecraft:enchanting_table
  -> dynamic_content 动态事实：玩家准星指向 minecraft:enchanting_table
  -> 静态知识 query 材料：enchanting table / 附魔台 / 当前输入
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

在正式静态 RAG 库完成前，可以用测试数据模拟静态知识命中。推荐把可公开、可脱敏的运行日志或调试记录整理成“一行一条知识命中”的测试数据，例如从 NeoForge `run/logs` 中抽样后手动或测试工具转换为 `AXKnowledgeHit`。这只用于验证检索结果进入 `<game_context>` 的编排效果，不代表正式知识库 schema，也不要求 AX 内置一套日志检索器。

静态知识由 LLM RAG cache 管理：

```text
AX 准备静态资料
  -> LLM_CACHE_MANAGE REGISTER_LIBRARY / UPSERT_ENTRY
  -> LLM 模块生成并缓存向量
  -> AX 按当前输入 / 动态环境 query 获取命中内容
  -> AX 将命中内容渲染进 <game_context>
```

AX 不直接写 LLM cache 文件，不直接访问 RAG cache 二进制索引。

静态知识有两种 query 来源：

```text
直接静态 RAG:
  当前输入
    -> SEARCH_TAGS(tags=[main])
    -> static knowledge query hit

动态引导静态 RAG:
  当前输入
    -> 对本轮动态环境候选做临时动态事实检索
    -> dynamic fact hits
    -> 当前输入 + 命中动态事实中的方块/物品/实体/维度等规范化环境事实
    -> SEARCH_TAGS(tags=[main, addon])
    -> dynamic path static knowledge hit
```

动态环境命中后，应优先把动态事实规范化为稳定标识，例如物品 ID、方块 ID、实体 ID、模组 ID、维度 ID 或结构名，而不是把完整环境快照塞进 query。
这里的“临时动态事实检索”只检索本轮动态候选，不写持久 RAG cache，也不直接作为最终 CHAT 的 `rag` chunk 注入；它产出的命中动态事实与随后命中的静态知识一起进入 `game_context.dynamic_content`。
当前 LLM 协议已通过 `LLM_CACHE_MANAGE / SEARCH_INLINE_CONTENTS` 提供“只检索、不生成、不落盘”的内联 RAG 查询动作；AX 通过该动作召回命中动态事实，不在自身模块内复刻轻量 RAG 实现，也不把全部动态候选直接拿去做 `main/addon` 静态召回。

## 7. 玩家记忆注入

玩家记忆走 AX 私有记忆系统。AX 可以把二级簇 centroid 与 E factText 投影到 LLM 私有 RAG uid，用于复用 LLM 的向量检索能力；但 LLM 只返回 `uid + entryId` 命中，AX 必须回自己的权威库加载 E，再做 `E -> STM` 折叠、链式发散和预算注入。

```text
当前输入
  -> L1 私有 RAG uid 检索二级簇 entryId
  -> 命中的 L2 私有 RAG uid 检索 eventId
  -> 回 AX 权威库加载 E
  -> STM 有效映射
  -> STM 贡献分计算
  -> 可选 STM 链式发散
  -> 按预算注入 player_memory
```

一期实现里，`player_memory` 必须执行 `E -> STM` 折叠注入：

- 检索命中的 E 只能作为定位锚点。
- 最终注入必须是 E 所属的完整 STM 文本。
- 不允许把孤立 E 文本直接拼进 prompt。
- 当命中置信度足够高时，才允许沿 STM 前后链路做链式发散。
- 链式发散深度必须与当前模型档位预算策略挂钩，不能写死为固定常量。

`player_memory` 的检索和 `game_context` 的取数在一期 baseline 中不做模块级握手，二者是并列模块。真正的融合只发生在 `core` 末端装配时。

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
静态内容、动态事实、动态事实路径 RAG 命中
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
  system: recent_dialogue
  user: current_input
```

不在玩家可见文本里解释这些标签或内部机制。

XML-like 包裹、列表前缀、小标题和聊天行格式都属于 prompt 排版资源，不应硬编码在 Java 业务逻辑里。AX common 内置 `ax_prompt_texts.json` 作为默认目录，运行时可释放到 AX 配置目录供后续覆盖；Java contributor 只负责选择语义槽位、传入变量并决定 LLM message role。

`general_ax.*.default.json` 中的 `sectionOrder` 只描述顶层 prompt 区块顺序，例如 `ax_system`、`game_context`、`player_memory`、`recent_dialogue`、`current_input`。`identity`、`behaviorRules` 这类内容属于 `ax_system` 内部字段，不应作为独立顶层区块参与排序。为兼容早期已释放配置，读取旧的 `identity` / `rules` / `persona` / `scope` 排序项时，可映射为 `ax_system`。

AX 的身份、行为规则和默认分区顺序也属于 prompt profile 资源。common 内置 `general_ax.<lang>.default.json`，运行时同样释放到 AX 配置目录；Java 只负责读取 profile 和组装 message，不在流程代码中写死具体提示词内容。

## 9. 预算策略

AX 不在本文写死上下文配比。预算分配先按模型档位和任务类型选布局档，再按本轮上下文密度做动态裁剪。

- `current_input` 是生成锚点，通常只有少量 token，默认应完整保留，不作为预算分配的主要矛盾。
- 真正要动态区分的是不同模型的“甜点窗口”：小模型优先保留最近对话和最小必要 `game_context`；中模型可以同时容纳较完整的 `game_context`、记忆和静态知识；更大模型才扩展到更长的记忆链和更丰富的知识命中。
- 同一模型内部，预算不按固定比例常量实现，而按本轮任务、上下文密度、命中质量和剩余窗口动态选取。
- 当窗口不足时，优先裁剪低收益、可重建或可替换内容；不要截断完整轮次、完整 STM 或已成型的知识命中块。
- 预算策略只规定选择原则，不规定硬编码比例、固定排序和静态阈值。

`AXMemory` 的检索预算也要按这个原则分层，不得退化为单一 Top-K。Hot / Warm / Cold 的比例、阈值和发散深度都应由预算策略配置化提供，而不是硬编码。

## 10. 一期 core

一期 `core` 由 `AXPromptOrchestrator` + `AXPromptAssemblyBuilder` + `AXPromptAssembly` 构成，和 `module` 同级。`core` 不是新的业务块，只负责把五块内容装配成最终 message chunk。

它负责：

- 接收 `AXSystem`、`AXGameContext`、`AXMemory`、`AXRecentDialogue`、`AXCurrentInput` 的候选内容。
- 根据预算决定各区块保留内容。
- 排序、去重、裁剪和分区。
- 产出最终 `LLM_REQUEST lane=CHAT` 所需的 message chunk。

它不替代数据来源本身，也不把 IA delivery 携带的输入快照升格为独立 prompt 区块。

`AXPromptAssemblyBuilder` 只负责 message 级别的拼装，不负责把 E 原文当独立文本注入；它最终只能接收已经折叠好的 STM 和其它区块内容。

## 11. 面向二期技能协作的扩展性

一期自然交互基线的 prompt 编排流程可以是固定管线，但结构上应允许二期技能协作插入受控技能结果。这里的扩展点只描述 prompt 编排边界，不提前冻结技能 manifest 或执行协议。

应预留的扩展点：

```text
KnowledgeNeedDecider
  一期可由规则实现，二期可由 IntentRouter 接管

QueryMaterialBuilder
  分别构造直接静态 RAG、动态引导静态 RAG、玩家记忆检索 query

PromptContributor
  每类上下文独立贡献 prompt 分区

SkillResultContributor
  二期以后把技能结果归一化后加入 prompt

ExecutionPolicy
  控制权限、预算、超时、脱敏和可见性
```

这些扩展点不是要求一期创建复杂框架，而是要求一期不要把所有逻辑硬编码在一个不可拆分的方法里。二期引入技能调用时，应能把“技能结果”作为新的受控上下文来源加入 prompt，而不是重写整个对话链路。技能结果进入 prompt 前必须先经过 `core` 校验、归一化和脱敏，不能由子执行体直接改写玩家可见消息。

## 12. 协议与安全

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

## 13. 最终原则

- 直接静态 RAG 和动态引导静态 RAG 是两条不同链路。
- 动态环境先服务当前指代，再服务静态知识检索扩展。
- 玩家记忆由 AX 自己管理和注入；LLM RAG 只承接 AX 私有检索投影，不拥有 E/STM 语义。
- 静态知识复用 LLM RAG cache，不污染 AX 私有记忆。
- Prompt 编排必须保持分区，不做无差别文本池。
- 一期自然交互基线是固定管线，但必须保留二期技能协作的扩展位置。
- 所有跨模块调用走协议中心。
