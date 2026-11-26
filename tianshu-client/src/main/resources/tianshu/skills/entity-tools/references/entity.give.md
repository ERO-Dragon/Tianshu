# minecraft:entity.give

从身体背包向指定对象交付物品。

## 调用示例

```json
{"id":"minecraft:entity.give","args":{"body":"worker-1","slot":2,"count":1,"target":"Alex"}}
```

## 返回示例

```json
{"ok":true,"result":{"itemId":"minecraft:bread","dropped":1}}
```

用 dropped 核对实际数量，槽位范围为 0–35。
