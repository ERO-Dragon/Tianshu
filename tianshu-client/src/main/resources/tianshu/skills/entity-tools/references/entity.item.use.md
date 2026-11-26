# minecraft:entity.item.use

使用身体主手的物品。对方块使用时给全 `x/y/z`，对空气使用时省略坐标。

## 调用

```json
{"id":"minecraft:entity.item.use","args":{"body":"worker-1","x":120,"y":64,"z":-30,"face":"up","holdTicks":0}}
```

## 返回

```json
{"ok":true,"result":{"outcome":"DONE","detail":"used minecraft:snowball"},"durationMs":5}
```

支持即时使用。需要持续按住的动作会被拒绝。`holdTicks` 上限为 100。即使外层 `ok` 为真，也要检查 `result.outcome`。若为 `REJECTED`，按 `detail` 检查主手物品、距离和维度。身体需要 `USE` 和 `INVENTORY`。
