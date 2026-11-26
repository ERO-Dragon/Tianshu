# minecraft:server.give

向玩家发放物品。

## 调用示例

```json
{"id":"minecraft:server.give","args":{"item":"minecraft:torch","count":8}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true,"detail":"gave 8 minecraft:torch"}}
```

背包放不下的物品可能落在玩家脚边。
