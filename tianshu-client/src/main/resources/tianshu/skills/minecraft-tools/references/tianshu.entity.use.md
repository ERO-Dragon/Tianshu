# tianshu:entity.use

对实体做交互：喂食、驯服、拴绳、交易。用当前主手物品或空手。

## 调用

```json
{"id":"tianshu:entity.use","args":{"entity":"entity-42"}}
```

## 返回

```json
{"ok":true,"result":{"interaction":"feed","item":"minecraft:bone","result":"tamed"},"durationMs":6}
```

手上拿什么决定会发生什么：先 `body.inventory` 或 `item.inspect` 确认主手。
