# minecraft:world.info

读身体所在世界的信息：维度、群系、时间、天气、难度、光照。

## 调用

```json
{"id":"minecraft:world.info","args":{}}
```

## 返回

```json
{"ok":true,"result":{"dimension":"minecraft:overworld","biome":"minecraft:plains","time":4200,"weather":"clear","difficulty":"normal","light":15},"durationMs":2}
```

时间是从当前世界读的，不是缓存：判断"是不是夜里"要用它，不要靠猜。
