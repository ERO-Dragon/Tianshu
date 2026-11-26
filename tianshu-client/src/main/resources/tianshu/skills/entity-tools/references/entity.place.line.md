# minecraft:entity.place.line

从指定起点沿一个方向连续放置同一种方块。

## 调用

```json
{"id":"minecraft:entity.place.line","args":{"body":"worker-1","item":"minecraft:stone","x":120,"y":64,"z":-30,"direction":"east","count":4}}
```

## 返回

```json
{"ok":true,"result":{"placed":[{"blockId":"minecraft:stone","x":120,"y":64,"z":-30,"placed":true,"message":""}],"requested":4,"stoppedBecause":"the actor has no minecraft:stone"},"durationMs":41}
```

`direction` 必填，可选 `north/south/east/west/up/down`。`count` 默认 1，范围 1–16。首块放置失败时整次调用失败。中途停止时，`placed` 列出已放置的方块，`stoppedBecause` 给出原因。续放时从下一个位置开始。
