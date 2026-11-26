# minecraft:item.inspect

检查玩家某个槽位中物品的属性和耐久。

## 调用示例

```json
{"id":"minecraft:item.inspect","args":{"slot":2}}
```

## 返回示例

```json
{"ok":true,"result":{"slot":2,"itemId":"minecraft:iron_pickaxe","count":1,"maxStackSize":1,"damageable":true,"damage":12,"maxDamage":250,"remainingDurability":238,"customName":"","enchantments":[],"components":[],"message":""}}
```

先从 inventory.inspect 取实际槽位；空槽无法检查。
