# minecraft:entity.interact

用身体操作可触及的按钮、拉杆、门、活板门或栅栏门。

## 调用

```json
{"id":"minecraft:entity.interact","args":{"body":"worker-1","x":121,"y":64,"z":-30}}
```

## 返回

```json
{"ok":true,"result":{"blockId":"minecraft:oak_door","interacted":true,"message":"operated"},"durationMs":4}
```

需要 `USE` 能力。`interacted: false` 表示方块未响应，按 `message` 检查距离和方块类型。
