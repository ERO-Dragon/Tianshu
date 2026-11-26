# minecraft:server.spawn_entity

在玩家所在位置生成指定数量的实体。

## 调用示例

```json
{"id":"minecraft:server.spawn_entity","args":{"type":"minecraft:sheep","count":1}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true,"detail":"spawned 1 minecraft:sheep"}}
```

生成后检查现场；重复调用会再次生成。
