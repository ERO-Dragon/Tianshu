# minecraft:entity.drop

将身体背包指定槽位的物品丢到地上。

## 调用示例

```json
{"id":"minecraft:entity.drop","args":{"body":"worker-1","slot":2,"count":1}}
```

## 返回示例

```json
{"ok":true,"result":{"itemId":"minecraft:bread","dropped":1}}
```

用 dropped 核对实际数量，槽位范围为 0–35。
