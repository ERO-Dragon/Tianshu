# minecraft:entity.world.info

读取身体所在位置的世界信息。

## 调用

```json
{"id":"minecraft:entity.world.info","args":{"body":"worker-1"}}
```

## 返回

```json
{"ok":true,"result":{"dimension":"minecraft:overworld","dimensionType":"minecraft:overworld","biome":"minecraft:plains","dayTime":4200,"dayNumber":3,"daylight":true,"weather":"clear","difficulty":"normal","lightLevel":15,"playerCount":2,"message":""},"durationMs":2}
```

用 `dayTime`、`lightLevel`、`weather` 判断当前环境。读取失败时用 `entity.list` 确认身体仍在世界中。
