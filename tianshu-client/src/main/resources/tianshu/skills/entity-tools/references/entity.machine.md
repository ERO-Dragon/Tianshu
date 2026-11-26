# minecraft:entity.machine

让一具身体操作炉子家族机器：投燃料、投原料、取产物，物品进出的都是**它自己的背包**。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.machine","args":{"body":"changsui","x":119,"y":64,"z":-31,"operation":"insert_input","playerSlot":12}}
```

## 返回

```json
{"ok":true,"result":{"machineId":"minecraft:furnace","operation":"insert_input","itemId":"minecraft:raw_iron","moved":3,"message":"inserted"},"durationMs":9}
```

## 失败与下一步

- `INVALID_ARGS` / `operation` 不是 `insert_fuel`/`insert_input`/`take_output`，或插入类操作没给 0..35 的 `playerSlot` → 补齐；`take_output` 不需要 `playerSlot`。
- `FAILED` / `moved` 为 0，消息就是原因（`only furnace-family machines are supported`、`that item is not a fuel`、`the fuel slot is full`、`there is nothing to take`、`the actor's inventory is full`）→ 按消息换机器、换槽位或先腾背包；`UNSUPPORTED_ACTOR` 表示身体缺 `INVENTORY`。
