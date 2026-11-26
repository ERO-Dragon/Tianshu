# minecraft:world.survey

以玩家的位置为原点，勘探当前维度内最近的结构或群系。范围包括尚未生成的地形，结构查询可能推进候选区块的结构生成。

`structure` 和 `biome` 恰好给一个。水平半径 `radius` 默认 256 格，可取 1–512 格。

## 调用

```json
{"id":"minecraft:world.survey","args":{"structure":"minecraft:village_plains","radius":512}}
```

## 返回

```json
{"ok":true,"result":{"kind":"structure","targetId":"minecraft:village_plains","found":true,"x":320,"y":0,"z":-144,"distanceBlocks":214.6,"sampled":0,"message":"found"},"durationMs":180}
```

## 失败与下一步

- `INVALID_ARGS` 表示目标、注册 ID 或半径无效，修正参数后再查。
- `found:false` 表示本次范围内没有匹配的结构起点，或群系采样没有命中。群系每 16 格采样，`sampled` 给出检查过的有效采样点数。
