# minecraft:entity.use

让身体与目标实体交互。

## 调用示例

```json
{"id":"minecraft:entity.use","args":{"body":"worker-1","entity":"entity-42"}}
```

## 返回示例

```json
{"ok":true,"result":{"outcome":"DONE","detail":"ok"}}
```

使用扫描得到的 entityId，确认身体主手持有目标需要的物品。按 outcome 判断交互结果。
