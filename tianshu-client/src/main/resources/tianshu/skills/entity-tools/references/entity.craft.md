# minecraft:entity.craft

使用身体背包中的材料，按指定配方合成一次。

## 调用

```json
{"id":"minecraft:entity.craft","args":{"body":"worker-1","recipeId":"minecraft:iron_pickaxe"}}
```

## 返回

```json
{"ok":true,"result":{"itemId":"minecraft:iron_pickaxe","count":1,"crafted":true,"message":"crafted"},"durationMs":12}
```

从 `entity.craft.available` 获取配方 ID。合成失败时检查材料和附近的工作台。身体需要 `INVENTORY` 能力。
