# minecraft:entity.interact

让一具身体按按钮、拉杆、开关门/活板门/栅栏门；只碰这几类无害方块，不破坏方块也不点燃。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.interact","args":{"body":"changsui","x":121,"y":64,"z":-30}}
```

## 返回

```json
{"ok":true,"result":{"blockId":"minecraft:oak_door","interacted":true,"message":"operated"},"durationMs":4}
```

## 失败与下一步

- `interacted:false` 仍是成功返回，`message` 说明原因：`the block did not react`（门已到位或被红石锁住）、`the block is out of reach`、`only buttons, levers, doors, trapdoors and fence gates may be operated` → 换目标或先靠近，别重发同一坐标。
- `UNSUPPORTED_ACTOR` / `agent body … cannot use`：这具身体没有 `USE` 能力位 → 换一具身体；`body.target.required` 说明是模块在调用却没点名身体。
