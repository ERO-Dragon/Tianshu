# minecraft:tag.query

查询物品、方块与标签的对应关系。

## 调用示例

```json
{"id":"minecraft:tag.query","args":{"kind":"item","itemId":"minecraft:oak_log"}}
```

## 返回示例

```json
{"ok":true,"result":{"entries":["minecraft:logs"]}}
```

给 itemId 时返回标签；给 tagId 时返回成员。一轮仅选一种方向。
