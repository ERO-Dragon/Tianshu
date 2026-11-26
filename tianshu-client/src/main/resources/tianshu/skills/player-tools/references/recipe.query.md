# minecraft:recipe.query

按产物 ID 查找配方。

## 调用示例

```json
{"id":"minecraft:recipe.query","args":{"outputItemId":"minecraft:stick","limit":5}}
```

## 返回示例

```json
{"ok":true,"result":{"recipes":[{"id":"minecraft:stick","type":"minecraft:crafting_shaped","inputs":["minecraft:oak_planks"],"output":"minecraft:stick"}]}}
```

recipes 为空表示查询没有命中；能否立即合成另查 craft.available。
