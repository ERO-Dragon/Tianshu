# 丢弃

`minecraft:entity.drop` 把手上或指定槽位的物品丢到地上。

- 丢出去就是掉在世界里，别人能捡；不是"收到背包里"。
- 丢之前确认槽位，槽位索引与 `entity.inventory` 一致。

## 范例

```json
{"id":"minecraft:entity.drop","args":{"slot":36,"count":1}}
```

```json
{"ok":true,"result":{"dropped":"minecraft:stone x1"},"durationMs":4}
```
