# minecraft:craft.available

查询玩家当前可合成的配方。

## 调用示例

```json
{"id":"minecraft:craft.available","args":{"limit":10}}
```

## 返回示例

```json
{"ok":true,"result":{"recipes":[{"recipeId":"minecraft:stick","outputItemId":"minecraft:stick","outputCount":4,"needsTable":false}],"limit":10}}
```

用 recipeId 调用 craft.recipe；needsTable 指示工作台需求。
