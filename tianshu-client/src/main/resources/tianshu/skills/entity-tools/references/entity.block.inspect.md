# minecraft:entity.block.inspect

读取身体可触及方块的状态、物品、流体和能量。

## 调用

```json
{"id":"minecraft:entity.block.inspect","args":{"body":"worker-1","x":119,"y":64,"z":-31}}
```

## 返回

```json
{"ok":true,"result":{"blockId":"minecraft:chest","blockEntityId":"minecraft:chest","state":{"facing":"north"},"items":[{"slot":0,"itemId":"minecraft:iron_ingot","count":12}],"fluids":[],"energy":{"stored":0,"capacity":0},"message":""},"durationMs":4}
```

空箱子和普通石头都可能返回空 `items`。结合 `blockId` 和 `blockEntityId` 判断方块类型。读取失败时检查坐标、距离和区块加载状态。
