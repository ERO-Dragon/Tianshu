# 发现：两个清单，别混

`list` 返回两个数组：

| 清单 | 里面是什么 | 能做什么 | 不能做什么 |
|---|---|---|---|
| `targets` | 可读材料：`kind` = skill / resource / prompt / tool | 读内容、调工具 | 投任务 |
| `taskEntries` | 任务入口：`kind` = connector 且 `attributes.taskEntry=true` | `submit` 投任务 | 当说明书读 |

## 怎么用

1. 玩家说"让某某去办件事"：
   - 先在 `taskEntries` 里找对应的入口；
   - 找到 → `describe` 看它的 `attributes`（输入约定）；
   - 找不到 → 如实说没有这个入口，不要拿 `targets` 里的材料顶替。
2. 玩家问"某某能做什么"：
   - 用 `targets` + `describe` 看描述；
   - 需要细节再读它的正文（技能/资源）。
3. 只读技能不会让工具变得可调用；工具能不能调，看工具目录与你的档位。

## `describe` 返回什么

```json
{
  "outcome": "OK",
  "id": "maid:task",
  "kind": "connector",
  "description": "女仆任务入口",
  "attributes": { "taskEntry": true, "accepts": "task", "returns": "taskId" }
}
```

`attributes` 是对方给的输入约定：先看它再投递，别猜字段。

## 找不到怎么办

- 说"没有能办这事的入口"，并给出可选项（列出 `taskEntries` 里的名字）；
- 不要自己编一个 id，也不要把任务塞进 `targets` 里的材料。