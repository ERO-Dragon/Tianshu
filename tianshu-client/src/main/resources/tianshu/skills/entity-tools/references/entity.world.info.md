# minecraft:entity.world.info

读这具身体所在位置的世界：维度、维度类型、群系、时间、天气、难度、光照和在线玩家数。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.world.info","args":{"body":"changsui"}}
```

## 返回

```json
{"ok":true,"result":{"dimension":"minecraft:overworld","dimensionType":"minecraft:overworld","biome":"minecraft:plains","dayTime":4200,"dayNumber":3,"daylight":true,"weather":"clear","difficulty":"normal","lightLevel":15,"playerCount":2,"message":""},"durationMs":2}
```

## 失败与下一步

- `FAILED` / 读不到世界，消息是 `the agent body is not present` 或身体已不在世界里 → 先 `entity.list` / `entity.status` 确认身体还在，别把这个信封当成"世界是空的"。
- 判断现在安不安全用三件事一起看：`daylight:false`、`lightLevel` 小于 8、难度不是 `peaceful`，三者同时成立才会刷怪；时间不是缓存，每次调用都是当下。
