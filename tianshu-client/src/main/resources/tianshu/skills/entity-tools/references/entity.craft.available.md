# minecraft:entity.craft.available

列出这具身体现在能做哪些配方，并标出哪些需要工作台——答案完全由**它背包里的东西**决定。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.craft.available","args":{"body":"changsui","limit":10}}
```

## 返回

```json
{"ok":true,"result":{"recipes":[{"recipeId":"minecraft:stick","outputItemId":"minecraft:stick","outputCount":4,"needsTable":false}],"limit":10},"durationMs":9}
```

## 失败与下一步

- `limit` 默认 20、上限 64；结果里没有 `truncated` 字段，`recipes` 数量正好等于 `limit` 就说明可能还有更多 → 加大 `limit` 再问一次，不要当成"只有这些"。
- `needsTable:true` 的配方要先站到工作台旁才能 `entity.craft`；不确定做什么时先读这份清单，比按物品 id 猜配方可靠。
