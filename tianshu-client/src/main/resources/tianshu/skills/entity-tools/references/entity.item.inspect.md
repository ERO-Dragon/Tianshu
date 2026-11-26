# minecraft:entity.item.inspect

读取身体背包指定槽位的物品属性。

## 调用

```json
{"id":"minecraft:entity.item.inspect","args":{"body":"worker-1","slot":0}}
```

## 返回

```json
{"ok":true,"result":{"slot":0,"itemId":"minecraft:iron_pickaxe","count":1,"maxStackSize":1,"damageable":true,"damage":12,"maxDamage":250,"remainingDurability":238,"customName":"","enchantments":[],"components":[],"message":""},"durationMs":3}
```

空槽或无效槽位返回 `INVALID_ARGS`。从 `entity.inventory` 选取已占用槽位。耐久看 `remainingDurability`，附魔和组件按 ID 返回。
