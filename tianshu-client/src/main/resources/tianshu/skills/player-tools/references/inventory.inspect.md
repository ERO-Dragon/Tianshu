# minecraft:inventory.inspect

读取玩家背包的已占用槽位。

## 调用示例

```json
{"id":"minecraft:inventory.inspect","args":{}}
```

## 返回示例

```json
{"ok":true,"result":{"slots":[{"slot":2,"itemId":"minecraft:bread","count":6}]}}
```

按 slot 识别物品位置；列表中元素的顺序不能代替槽位号。
