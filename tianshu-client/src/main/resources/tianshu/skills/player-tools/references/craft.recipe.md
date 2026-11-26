# minecraft:craft.recipe

使用玩家材料执行指定配方。

## 调用示例

```json
{"id":"minecraft:craft.recipe","args":{"recipeId":"minecraft:iron_pickaxe"}}
```

## 返回示例

```json
{"ok":true,"result":{"itemId":"minecraft:iron_pickaxe","count":1,"crafted":true,"message":"crafted"}}
```

从 craft.available 取 recipeId；检查 crafted 与产物数量。
