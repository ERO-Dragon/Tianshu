# minecraft:entity.world.survey

以身体的位置为原点，勘探当前维度内最近的结构或群系。范围包括尚未生成的地形，结构查询可能推进候选区块的结构生成。

`structure` 和 `biome` 恰好给一个。水平半径 `radius` 默认 256 格，可取 1–512 格。 模组传入自己要驱动的 `body`，身体以自身身份调用时可省略。

## 调用

```json
{"id":"minecraft:entity.world.survey","args":{"body":"worker-1","biome":"minecraft:desert","radius":512}}
```

## 返回

```json
{"ok":true,"result":{"kind":"biome","targetId":"minecraft:desert","found":true,"x":312,"y":70,"z":-140,"distanceBlocks":214.6,"sampled":1024,"message":"found"},"durationMs":180}
```

## 失败与下一步

- `INVALID_ARGS` 表示目标、注册 ID 或半径无效，修正参数后再查。
- `found:false` 表示本次范围内没有匹配的结构起点，或群系采样没有命中。群系每 16 格采样，`sampled` 给出检查过的有效采样点数。
