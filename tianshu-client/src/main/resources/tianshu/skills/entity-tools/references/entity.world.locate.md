# minecraft:entity.world.locate

以这具身体的位置为原点，找最近的某个结构或群系；`structure` 与 `biome` 必须恰好给一个。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.world.locate","args":{"body":"changsui","biome":"minecraft:desert","radius":512}}
```

## 返回

```json
{"ok":true,"result":{"kind":"biome","targetId":"minecraft:desert","found":true,"x":312,"y":70,"z":-140,"distanceBlocks":214.6,"sampled":1024,"message":"found"},"durationMs":180}
```

## 失败与下一步

- `INVALID_ARGS` / 两个都给或都不给，或 id 不存在（`no biome is registered as …`、`'…' is not a valid structure id`）→ 只留一个并把 id 写全；这类拒绝说明搜索根本没跑，换坐标没用。
- `found:false` 是成功答案（范围内没有）：群系是抽样，`sampled` 说明实际看了多少个点；要更大范围就加 `radius`（默认 256，群系最多采样 4096 点），不要当成"这世界没有"。
