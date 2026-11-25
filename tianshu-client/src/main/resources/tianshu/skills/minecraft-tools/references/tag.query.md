# tianshu:tag.query

查物品或方块标签：给 `itemId` 列出它属于哪些标签，给 `tagId` 列出标签里有什么。

## 调用

```json
{"id":"tianshu:tag.query","args":{"kind":"item","itemId":"minecraft:oak_log"}}
```

## 返回

```json
{"ok":true,"result":{"tags":["minecraft:logs","minecraft:logs_that_burn"]},"durationMs":3}
```

用标签判断"能不能当木板用"比猜具体物品更可靠：配方常常按标签匹配。
