# minecraft:entity.sequence

按顺序执行一组身体步骤（最多 32 步）。每一步走的是单个工具同一条处理器，第一个失败的步就停。

## 调用

```json
{"id":"minecraft:entity.sequence","args":{"steps":[
  {"kind":"mine","x":118,"y":52,"z":-30},
  {"kind":"pickup","radius":2}
]}}
```

| 字段 | 含义 |
|---|---|
| `kind` | 必填：`move` / `look` / `mine` / `place` / `interact` / `item_use` / `pickup` / `equip` |
| `x` `y` `z` | 目标坐标（`move` / `look` / `mine` / `place` / `interact` / `item_use` 用） |
| `mode` | `move` 的走法：`any` / `walk` / `fly` / `teleport` |
| `item` `face` | `place` 用：物品 id 与朝向（`face` 也给 `interact` / `item_use`） |
| `holdTicks` | `item_use` 按住多少 tick，0 = 用物品自己的 |
| `radius` | `pickup` 的半径，默认 3 |
| `slot` `target` | `equip` 用：背包槽位（-1 = 卸下）与身体槽（`main` / `offhand` / `head` / `chest` / `legs` / `feet`） |

`body` 省略时由调用者指定的那具身体执行；身体自己调用时可以省略。

## 返回

```json
{"ok":true,"result":{"steps":[{"kind":"mine","ok":true,"detail":"..."},{"kind":"pickup","ok":true,"detail":"picked up 2 stacks"}],"failedIndex":-1,"detail":"all 2 steps completed"},"durationMs":530}
```

`failedIndex = -1` 表示全跑完；`steps` 里每项是这个步的 `kind`、是否成功和一句说明。

## 失败与下一步

- 任何一步没走成，整个调用就是失败信封：`ok:false`，消息里带步号与原因（`entity.sequence stopped at step N: ...`）。
  **不要重发整条序列**——先修那一步的前提，再重排。
- 三条规则：失败即停（不会"尽力而为"继续）、取消只在步边界生效（已完成的步不回滚）、没有分支/循环/变量。
- 中途身体换了维度会停：剩下的坐标是按原维度算的，平台不肯把它们当成新世界的坐标去做。
- 适合路线固定、每步参数事先算得出来（扫→走→挖→捡）；下一步取决于上一步发现了什么时，自己一步步调。