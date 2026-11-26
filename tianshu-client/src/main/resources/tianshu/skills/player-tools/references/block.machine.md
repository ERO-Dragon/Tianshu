# minecraft:block.machine

为工作方块插入原料、燃料或取出产物。

## 调用示例

```json
{"id":"minecraft:block.machine","args":{"x":120,"y":64,"z":-30,"operation":"insert_input","playerSlot":2}}
```

## 返回示例

```json
{"ok":true,"result":{"machineId":"minecraft:furnace","operation":"insert_input","itemId":"minecraft:raw_iron","moved":3,"message":"inserted"}}
```

insert_input 和 insert_fuel 需要玩家背包槽位；take_output 取出产物。核对 moved 和 message。
