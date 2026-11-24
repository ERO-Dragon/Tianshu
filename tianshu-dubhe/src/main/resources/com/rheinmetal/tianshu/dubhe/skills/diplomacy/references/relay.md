# relay：投递只收受理回执

```
action   = submit
target   = 对方的任务入口 id（必须是 taskEntries 里的）
task     = 一句话，脱离上下文也能读懂（不要"这个""刚才"）
```

## 例子：投给女仆

```
relay {"action":"submit","target":"maid:task","task":"把主屋箱子里的原木搬到仓库"}
→ {"outcome":"OK","taskId":"t-9","progress":"RUNNING"}
```

回答玩家："我跟女仆说了，让她搬。"（只能说"去办了"，不能说"搬完了"）

## 返回

受理成功：

```json
{ "outcome": "OK", "taskId": "t-9", "progress": "RUNNING" }
```

- `taskId` 是**受理回执**，不是"办完"；
- 拿到它才可以说"我让人去办了"。

失败：

```json
{ "outcome": "FAILED", "reason": "NO_TASK_ID" }
```

| reason | 含义 | 怎么办 |
|---|---|---|
| `NO_TASK_ID` | 对方接了但没给 id | 当作没办成，如实说；不要编 id |
| `CALL_REFUSED` | 对方拒绝（看 `block`） | 把游戏内原因告诉玩家 |
| `CALL_FAILED` | 调用失败/超时 | 说"没办成，稍后再试" |
| `INVALID_TARGET` | id 不是 `namespace:name` | 重新 `list` 找正确 id |
| `INVALID_ARGS` | 少了 `task` | 补上一句话再投 |

## 例子：投错了口子

```
relay {"action":"submit","target":"maid:guide","task":"把原木搬到仓库"}
→ {"outcome":"FAILED","reason":"NO_TASK_ID"}
```

`maid:guide` 是说明书（在 `targets` 里），不是投递口。任务不会有人执行。
正确做法：回 `list` 找 `taskEntries` 里的 `maid:task`。

## 硬规则

1. **不重复投递同一件事**：已经有 `taskId` 就查进度，不要再投一次；
2. **任务句要自足**：对方看不到我们的对话，写"把箱子里的原木搬到仓库"而不是"把这个搬过去"；
3. **不等执行**：投完就回话，不要在这里挂着等结果；
4. **一次一件事**：把复合请求拆成多件，分别投、分别记 `taskId`。

## 记下什么

投递成功后，记住这三件事直到任务结束：

- `taskId`；
- 目标（哪个入口/哪个读工具）；
- 玩家当时的原话（回报时用得上）。