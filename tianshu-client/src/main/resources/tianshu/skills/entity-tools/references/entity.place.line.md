# minecraft:entity.place.line

让一具身体沿一个方向连续放同一种方块（最多 16 块）：第一块就放不下时整个调用失败，中途停下则返回已经放成的和停下原因。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.place.line","args":{"body":"changsui","item":"minecraft:stone","x":120,"y":64,"z":-30,"direction":"east","count":4}}
```

## 返回

```json
{"ok":true,"result":{"placed":[{"blockId":"minecraft:stone","x":120,"y":64,"z":-30,"placed":true,"message":""}],"requested":4,"stoppedBecause":"the actor has no minecraft:stone"},"durationMs":41}
```

## 失败与下一步

- `INVALID_ARGS` / `direction` 不是 `north`/`south`/`east`/`west`/`up`/`down`，或 `count` 不在 1..16 → 改正再用；`direction` 必填、没有默认方向，`count` 省略按 1。
- `stoppedBecause` 非空：线在中途停了（材料不够、被方块挡住、身体到不了）→ 看 `placed` 已经放成几块，补材料或换起点重算；它不是断点续传，重发会从起点重新铺。
