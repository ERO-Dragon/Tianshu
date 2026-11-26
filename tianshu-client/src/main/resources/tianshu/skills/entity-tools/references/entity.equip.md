# minecraft:entity.equip

将身体背包中的物品装备到指定位置。

## 调用示例

```json
{"id":"minecraft:entity.equip","args":{"body":"worker-1","slot":2,"target":"main"}}
```

## 返回示例

```json
{"ok":true,"result":{"target":"MAIN_HAND","inventorySlot":2,"itemId":"minecraft:iron_pickaxe","count":1}}
```

target 可选 main、offhand、head、chest、legs、feet。槽位取自身体背包。
