# tianshu:recipe.query

列出能产出某个物品的配方（给产物 id，不是配方 id）。

## 调用

```json
{"id":"tianshu:recipe.query","args":{"outputItemId":"minecraft:stick","limit":5}}
```

## 返回

```json
{"ok":true,"result":{"recipes":["minecraft:stick"],"truncated":false},"durationMs":4}
```
