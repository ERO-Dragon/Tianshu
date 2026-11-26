# minecraft:entity.transfer

在容器和身体背包之间搬运物品。

## 调用

```json
{"id":"minecraft:entity.transfer","args":{"body":"worker-1","x":119,"y":64,"z":-31,"containerSlot":0,"playerSlot":12,"count":8,"toPlayer":true}}
```

## 返回

```json
{"ok":true,"result":{"itemId":"minecraft:iron_ingot","moved":8,"message":"moved 8"},"durationMs":6}
```

`containerSlot` 是容器槽位，`playerSlot` 在此指身体背包槽位（0–35）。`count` 范围 1–64。`toPlayer: true` 表示取到身体背包。实际移动量看 `moved`，为 0 时按 `message` 检查来源和目标槽位。
