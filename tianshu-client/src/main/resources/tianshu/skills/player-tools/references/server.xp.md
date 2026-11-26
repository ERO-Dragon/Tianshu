# minecraft:server.xp

按等级增减玩家经验。

## 调用示例

```json
{"id":"minecraft:server.xp","args":{"levels":5}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true,"detail":"experience changed by 5 level(s)"}}
```

负数扣除等级；重复成功调用会累加。
