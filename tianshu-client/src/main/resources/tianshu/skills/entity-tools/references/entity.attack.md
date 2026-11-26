# minecraft:entity.attack

让身体攻击指定实体。

## 调用示例

```json
{"id":"minecraft:entity.attack","args":{"body":"worker-1","entity":"entity-42"}}
```

## 返回示例

```json
{"ok":true,"result":{"outcome":"DONE","detail":"ok"}}
```

entity 取自扫描返回的 entityId。以 outcome 判断是否生效；当前血量可通过 entity.inspect 查询。
