---
name: body-tools
description: 舟楫基座每个原子能力各自的一份简短说明：做什么、调用怎么写、返回长什么样、失败之后做什么、常见错误是什么。当你要调用某个具体能力却不确定参数或返回字段时读它——一个能力一份，文件名就是能力 id。
---
# 基座原子能力

一个能力一份说明，**文件名就是能力 id**：

| 能力 | 说明 |
|---|---|
| `tianshu:block.break` | [references/tianshu.block.break.md](references/tianshu.block.break.md) |
| `tianshu:container.transfer` | [references/tianshu.container.transfer.md](references/tianshu.container.transfer.md) |
| `tianshu:body.mine` | [references/tianshu.body.mine.md](references/tianshu.body.mine.md) |

完整清单看 `references/` 目录，或用 `tianshu:capability.read` 不带 `id` 列一遍。

## 怎么读

先读能力自己的那份，比整本翻一遍省：

```json
{"id":"tianshu:capability.read","args":{"id":"tianshu:container.transfer"}}
```

## 每份说明里有什么

| 段 | 回答的问题 |
|---|---|
| 开头一句 | 这个能力做什么 |
| 调用 | 一次调用长什么样、参数名怎么填（取自它的 schema） |
| 返回 | 成功时的信封与字段 |
| 失败与下一步 | 这个能力特有的失败原因，以及该做什么——通常不是重发同一次调用 |

## 两条规矩

- **参数不确定就读说明，不要猜字段名**：猜错的代价是一次 `INVALID_ARGS` 加一轮往返。
- **失败之后先看原因**：够不着、背包满、材料不够、级别不够是四件不同的事，做法不同。

调用约定（信封格式、档位含义）在 `tianshu:minecraft-tools`；这里只讲每个能力自己怎么用。