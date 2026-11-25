# tianshu:loot.query

查方块或实体的掉落表：给 `blockId` 或 `entityId`，不能两个都给。

## 调用

```json
{"id":"tianshu:loot.query","args":{"blockId":"minecraft:iron_ore"}}
```

## 返回

```json
{"ok":true,"result":{"drops":["minecraft:raw_iron"],"counts":{"minecraft:raw_iron":[1,1]}},"durationMs":5}
```

掉落是"可能"不是"一定"：带工具的精准采集、时运、经验等级都会改变结果。
