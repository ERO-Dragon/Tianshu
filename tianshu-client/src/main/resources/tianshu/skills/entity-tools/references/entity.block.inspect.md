# minecraft:entity.block.inspect

让一具身体读一个方块：它是什么、方块状态，以及它暴露出来的物品、流体与能量——不打开界面，靠近它自己读。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.block.inspect","args":{"body":"changsui","x":119,"y":64,"z":-31}}
```

## 返回

```json
{"ok":true,"result":{"blockId":"minecraft:chest","blockEntityId":"minecraft:chest","state":{"facing":"north"},"items":[{"slot":0,"itemId":"minecraft:iron_ingot","count":12}],"fluids":[],"energy":{"stored":0,"capacity":0},"message":""},"durationMs":4}
```

## 失败与下一步

- 空集合意思是"没有"不是"有但是空"：普通石头和空箱子都回空 `items`，都对；用 `blockEntityId` 判断它是不是容器或机器，别把空列表读成读取失败。
- `FAILED` / `the block is out of reach`、`that block is in a chunk that is not loaded`、`there is no block there` → 先 `entity.move` 靠近或核对坐标来源（应该来自 `entity.status`/`entity.scan`），别凭玩家位置推。
