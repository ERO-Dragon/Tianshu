# minecraft:entity.machine

在身体背包与熔炉类机器之间转移燃料、原料或产物。

## 调用

```json
{"id":"minecraft:entity.machine","args":{"body":"worker-1","x":119,"y":64,"z":-31,"operation":"insert_input","playerSlot":12}}
```

## 返回

```json
{"ok":true,"result":{"machineId":"minecraft:furnace","operation":"insert_input","itemId":"minecraft:raw_iron","moved":3,"message":"inserted"},"durationMs":9}
```

`insert_fuel` 和 `insert_input` 需要身体背包槽位 `playerSlot`（0–35）。`take_output` 取出产物。操作失败时检查燃料类型、产物和剩余空间。身体需要 `INVENTORY`。
