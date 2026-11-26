# minecraft:entity.transfer

让一具身体在容器的一个槽位与**它自己背包**的一个槽位之间搬物品；两个槽位都属于它。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.transfer","args":{"body":"changsui","x":119,"y":64,"z":-31,"containerSlot":0,"playerSlot":12,"count":8,"toPlayer":true}}
```

## 返回

```json
{"ok":true,"result":{"itemId":"minecraft:iron_ingot","moved":8,"message":"moved 8"},"durationMs":6}
```

## 失败与下一步

- `INVALID_ARGS` / `containerSlot` 或 `playerSlot` 为负、`playerSlot` ≥ 36、`count` 不在 1..64 → 改正；`count` 只是上限，实际搬了多少只看 `moved`。
- `moved:0` 是成功信封但不是好消息，`message` 说明卡在哪（`that block is not a container`、`the container slot is empty`、`the actor slot cannot accept that item`、`the container slot is full`）→ 按消息换槽位或先清空，同样的参数重发不会移动任何东西。
