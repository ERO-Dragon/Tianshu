# tianshu:block.break

破坏指定坐标的一个方块（你自己或某具体身体）。掉落按真实工具与方块规则结算。

## 调用

```json
{"id":"tianshu:block.break","args":{"x":120,"y":64,"z":-30}}
```

## 返回

```json
{"ok":true,"result":{"broken":"minecraft:stone","dropped":["minecraft:cobblestone"]},"durationMs":44}
```

## 失败与下一步

- `OUT_OF_RANGE`：够不着 → 先靠近；不要重发同一坐标。
- `INVALID_ARGS`：坐标不在已加载区域，或方块 id 写错。
- 什么都没掉落：工具不对，换工具再挖；这不是平台的错。
