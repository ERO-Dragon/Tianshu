# minecraft:container.inspect

读取玩家当前打开的容器界面。

## 调用示例

```json
{"id":"minecraft:container.inspect","args":{}}
```

## 返回示例

```json
{"ok":true,"result":{"open":true,"title":"Chest","entries":[{"slot":2,"itemId":"minecraft:bread","count":6,"displayName":"Bread"}]}}
```

先检查 open。容器已打开且 entries 为空时，表示没有可列出的物品。
