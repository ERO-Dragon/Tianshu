# tianshu:world.locate

找最近的结构或群系。`structure` 与 `biome` 只能给一个。

## 调用

```json
{"id":"tianshu:world.locate","args":{"structure":"minecraft:village_plains","radius":512}}
```

## 返回

```json
{"ok":true,"result":{"structure":"minecraft:village_plains","x":312,"y":70,"z":-140,"sampled":64},"durationMs":180}
```

## 失败与下一步

- 同时给两个 → 明确拒绝；按需要只给一个。
- 找不到 → 换更大的 `radius` 或换写法（未知结构 id 与"附近没有"是两件事，消息会区分）。
