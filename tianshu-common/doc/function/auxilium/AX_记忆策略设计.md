# AX 记忆策略设计

## 1. 记忆职责迁移

助手线记忆策略已经从 LLM 模块迁出。LLM 只提供推理和可选 RAG 检索能力，不决定 NPC 记住什么、不维护记忆生命周期、不修改长期记忆文件。

AX 负责：

- 判断哪些事件值得沉淀。
- 将短期对话压缩为长期记忆候选。
- 管理不同世界、模组、agent 的记忆边界。
- 维护 TTL、importance、hit_count、last_hit_time 等业务字段。
- 在请求 LLM 前选择哪些记忆进入上下文。

## 2. 记忆分层

| 层级 | 说明 | 存储建议 |
|---|---|---|
| Raw recent dialogue | 最近几轮原始对话 | AX 会话内存或短期缓存 |
| Session summary | 当前会话压缩摘要 | AX 状态 |
| Long-term memory | 跨会话稳定事实 | world/mod/agent 归属的持久文件 |
| Dynamic facts | 当前世界状态、玩家状态、临时事件 | 不持久化或短 TTL |

AX 不应把 dynamic facts 直接写成长期记忆。长期记忆必须经过筛选、压缩和去重。

## 3. 多世界、多模组、多 Agent 路径

推荐长期记忆归属模型：

```text
llm_rag/
├── <world>/
│   ├── profiles.json
│   └── <mod>/
│       └── agents/
│           └── <agent>/
│               └── memory_rag/
│                   └── memories.jsonl
```

AX 应根据 IA delivery 或 agent 注册信息确定：

- `world`
- `mod`
- `agent`
- `profile`

同一个 NPC 在不同世界的记忆必须隔离。不同模组的 agent 默认隔离，除非 IA 或上层配置显式允许共享。

## 4. memories.jsonl 契约

服务端兼容的最低字段：

```jsonl
{"uid":"mem-0001","long_term_memory":"玩家曾帮助铁匠找回丢失的矿石。"}
{"uid":"mem-0002","long_term_memory":"玩家偏好远程战斗。"}
```

AX 可在自己的索引或旁路元数据中维护：

```json
{
  "uid": "mem-0001",
  "importance": 0.8,
  "hit_count": 3,
  "last_hit_time": 1710000000,
  "ttl": 1209600,
  "source": "dialogue",
  "created_by": "mod_a/guard_bob"
}
```

如果这些字段直接写入 JSONL，服务端应忽略未知字段；但 AX 不应依赖服务端维护这些字段。

## 5. 写入安全

长期记忆写入必须使用原子替换：

```text
1. 读取当前 memories.jsonl
2. 在内存中合并新增、更新、删除
3. 写 memories.jsonl.tmp
4. flush / close
5. 原子替换 memories.jsonl
```

服务端会忽略 `*.tmp` 和 `*.lock`。AX 应避免在服务端读取过程中写半截文件。

## 6. 记忆生成流程

推荐流程：

```text
对话结束或达到压缩阈值
  -> AX 收集 raw dialogue + session facts
  -> 发起 LLM task 请求做摘要/抽取
  -> 得到候选记忆
  -> 去重、合并、评分
  -> 写入 agent memory store
  -> 必要时更新旁路 metadata
```

用于记忆压缩的 LLM task 应：

- 使用 `stream=false`。
- 设置 `maxTokens`。
- `useRag=false`，除非明确需要参考静态知识。
- 使用较低 priority，避免抢占玩家可见对话。
- 失败时不修改现有记忆。

## 7. 请求时记忆注入

AX 有两种方式使用长期记忆：

### 7.1 AX 侧选择后注入 messages

AX 自己选择相关记忆，把它们整理为 system message。

优点：

- 完全掌控 NPC 语义。
- 不依赖服务端 RAG root 适配进度。
- 易于结合 IA delivery 和 agent 权限。

### 7.2 服务端 memory RAG

AX 维护 `memories.jsonl`，common 启动服务端时传 `--memory-rag-path` 或未来的 `--rag-root-path`，请求时使用 `use_memory_rag`。

优点：

- 向量检索由服务端执行。
- 可返回 `rag_hits`，AX 可据此更新 hit_count/last_hit_time。

限制：

- 当前 common 主要适配兼容 `--memory-rag-path`。
- 多世界 profile RAG 需要 common 后续补齐字段和参数映射。
- task lane 不应使用长期记忆 RAG。

## 8. RAG hit 反馈

服务端返回的 `rag_hits.memory` 只表示本次实际注入 prompt 的记忆命中。AX 可以用它更新：

- hit_count
- last_hit_time
- 记忆热度
- 遗忘策略

但不要把 RAG hit 当成模型发言，也不要因为一次命中就自动提高 importance。importance 应结合对话结果和业务规则。

## 9. 遗忘与合并

长期记忆应定期整理：

- 删除过期且低 importance 的记忆。
- 合并语义重复的记忆。
- 修正被后续事实推翻的记忆。
- 把多条碎片记忆压缩为一条稳定摘要。

整理任务应作为后台 LLM task 执行，并且必须保证失败不破坏原文件。

## 10. 禁止事项

- 禁止 LLM 模块直接写长期记忆。
- 禁止把所有聊天原文无限追加到长期记忆。
- 禁止跨世界共享 NPC 记忆，除非显式配置。
- 禁止把临时战斗状态、背包瞬时内容直接写成长期记忆。
- 禁止在日志中输出完整记忆库或完整 prompt。
- 禁止在 IA 未授权时生成玩家可见记忆相关回复。
