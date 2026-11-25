---
name: body-tools
description: 舟楫每个工具自己的用法说明：做什么、调用怎么写、返回长什么样、失败之后做什么。当你要调用某个具体工具却不确定参数或返回字段时读它——一个工具一份，文件名就是工具 id。
---
# 工具说明

一个工具一份说明，**文件名就是工具 id**：

| 工具 | 说明 |
|---|---|
| `tianshu:block.break` | [references/block.break.md](references/block.break.md) |
| `tianshu:container.transfer` | [references/container.transfer.md](references/container.transfer.md) |
| `tianshu:body.mine` | [references/body.mine.md](references/body.mine.md) |

完整清单看 `references/` 目录，或用 `tianshu:capability.read` 不带 `id` 列一遍。

## 怎么读

先读工具自己的那一份，比整本翻一遍省：

```json
{"id":"tianshu:capability.read","args":{"id":"tianshu:container.transfer"}}
```

## 每份说明里有什么

| 段 | 回答的问题 |
|---|---|
| 开头一句 | 这个工具做什么 |
| 调用 | 一次调用长什么样、参数名怎么填（取自它的 schema） |
| 返回 | 成功时的信封与字段 |
| 失败与下一步 | 这个工具特有的失败原因，以及该做什么——通常不是重发同一次调用 |

## 两条规矩

- **参数不确定就读说明，不要猜字段名**：猜错的代价是一次 `INVALID_ARGS` 加一轮往返。
- **失败之后先看原因**：够不着、背包满、材料不够、级别不够是四件不同的事，做法不同。

调用约定（信封格式、档位与开关）在 `tianshu:minecraft-tools`；这里只讲每个工具自己怎么用。