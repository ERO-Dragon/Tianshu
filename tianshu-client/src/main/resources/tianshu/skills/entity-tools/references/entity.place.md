# minecraft:entity.place

用身体背包中的物品在目标位置放置方块，需有 `PLACE` 能力。

## 调用

```json
{"id":"minecraft:entity.place","args":{"body":"worker-1","item":"minecraft:torch","x":120,"y":64,"z":-30,"face":"up"}}
```

## 返回

```json
{"ok":true,"result":{"blockId":"minecraft:torch","x":120,"y":64,"z":-30,"placed":true,"message":""},"durationMs":6}
```

`face` 可选 `up/down/north/south/east/west`。省略时按身体站位决定朝向。放置失败时检查物品、距离和区块加载状态。
