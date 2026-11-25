# 拾取

`tianshu:body.pickup` 捡起附近掉落的物品。

- 背包满了会失败，先 `body.inventory` 看空间。
- 掉落物有存活时间：犹豫太久就没了。
- 捡不到先看距离，再看背包——排查顺序反了会白跑。

## 范例

```json
{"id":"tianshu:body.pickup","args":{"radius":3}}
```

```json
{"ok":true,"result":{"picked":["minecraft:raw_iron x3"],"leftBehind":[]},"durationMs":12}
```
