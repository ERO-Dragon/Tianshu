# minecraft:loot.query

按方块或实体 ID 查询掉落物。

## 调用示例

```json
{"id":"minecraft:loot.query","args":{"blockId":"minecraft:iron_ore"}}
```

## 返回示例

```json
{"ok":true,"result":{"items":["minecraft:raw_iron"]}}
```

blockId 与 entityId 只提供一项。items 给出物品 ID，无法据此断言掉落数量。
