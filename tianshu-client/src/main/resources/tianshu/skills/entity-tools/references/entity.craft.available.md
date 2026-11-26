# minecraft:entity.craft.available

按身体背包中的材料列出可合成的配方。

## 调用

```json
{"id":"minecraft:entity.craft.available","args":{"body":"worker-1","limit":10}}
```

## 返回

```json
{"ok":true,"result":{"recipes":[{"recipeId":"minecraft:stick","outputItemId":"minecraft:stick","outputCount":4,"needsTable":false}],"limit":10},"durationMs":9}
```

`limit` 默认 20，上限 64。条数达到上限时可能还有更多。`needsTable: true` 表示合成时身体须靠近工作台。
