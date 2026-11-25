# 串步骤（特权级）

`minecraft:entity.sequence` 按顺序执行 `{action, args}`，`action` 就是上面那些工具名去掉 `tianshu:` 前缀。

| 规则 | 说明 |
|---|---|
| 失败即停 | 返回失败步号、动作名与原因，不会"尽力而为"继续 |
| 可取消 | 在步边界取消，已完成的步不回滚 |
| 无分支 | 它不判断结果；需要"看情况"就自己一步步调 |

- 适合：路线固定、每步参数事先算得出来（扫→走→挖→捡）。
- 不适合：下一步取决于上一步发现了什么（挖到了什么、周围有没有敌人）。

## 范例

```json
{"id":"minecraft:entity.sequence","args":{"steps":[{"action":"body.move","args":{"x":118,"y":52,"z":-30}},{"action":"body.mine","args":{"x":118,"y":52,"z":-30}},{"action":"body.pickup","args":{"radius":2}}]}}
```

```json
{"ok":true,"result":{"executed":3,"failed":null},"durationMs":530}
```

失败时 `failed` 给出步号与原因；此时**不要重发整条序列**——先修那一步的前提。
