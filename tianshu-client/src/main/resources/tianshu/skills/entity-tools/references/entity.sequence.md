# minecraft:entity.sequence

按顺序执行一组身体步骤（最多 32 步）。适用于每步参数已知的动作。移动步骤与 entity.move 遵守同一 NPC 档位，拒绝后停止后续步骤。

## 调用

```json
{"id":"minecraft:entity.sequence","args":{"body":"worker-1","steps":[
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

## 返回

```json
{"ok":true,"result":{"steps":[{"kind":"mine","ok":true,"detail":"..."},{"kind":"pickup","ok":true,"detail":"picked up 2 stacks"}],"failedIndex":-1,"detail":"all 2 steps completed"},"durationMs":530}
```

`failedIndex = -1` 表示全部完成。步骤失败时，外层返回 `ok: false`，消息给出步号和原因。已完成的动作保留，修正后从未完成的步骤继续。取消在步骤边界生效，身体切换维度时序列也会停止。
