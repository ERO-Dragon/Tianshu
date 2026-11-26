# minecraft:entity.item.inspect

让一具身体读它自己背包里的一个槽位：物品 id、数量、剩余耐久、附魔、自定义名称和这个堆叠上的数据组件。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.item.inspect","args":{"body":"changsui","slot":0}}
```

## 返回

```json
{"ok":true,"result":{"slot":0,"itemId":"minecraft:iron_pickaxe","count":1,"maxStackSize":1,"damageable":true,"damage":12,"maxDamage":250,"remainingDurability":238,"customName":"","enchantments":[],"components":[],"message":""},"durationMs":3}
```

## 失败与下一步

- `INVALID_ARGS` / `the slot is empty`、`that slot does not exist`、`that agent body has no inventory` → 空槽是拒绝，不是一件没名字的物品：先用 `entity.inventory` 找一个真有东西的槽位。
- `remainingDurability` 接近 0 就该换工具：`entity.equip` 之前先读它；`enchantments` 与 `components` 只报 id、不做解释，别按名字猜效果。
