# minecraft:world.info

读取玩家所在位置的世界信息。

## 调用示例

```json
{"id":"minecraft:world.info","args":{}}
```

## 返回示例

```json
{"ok":true,"result":{"dimension":"minecraft:overworld","dimensionType":"minecraft:overworld","biome":"minecraft:plains","dayTime":4200,"dayNumber":3,"daylight":true,"weather":"clear","difficulty":"normal","lightLevel":15,"playerCount":2,"message":""}}
```

时间读 dayTime，位置亮度读 lightLevel。
