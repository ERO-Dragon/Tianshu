# 挖掘

`tianshu:body.mine` 破坏视线内、够得着的方块。

| 返回 | 含义与下一步 |
|---|---|
| 方块被破坏 | 真的掉了；有些方块需要正确工具才掉落 |
| `距离太远` | 不在可触及范围内 → 先 `body.move` 靠近 |
| 工具不合适 | 用 `body.equip` 换上对的工具；用错工具可能什么都没有 |

- 手里没有工具时，很多方块挖了等于白挖。
- 挖一串方块时逐块调用，或用 `body.sequence` 串起来（流程固定时）。

## 范例

```json
{"id":"tianshu:body.mine","args":{"x":118,"y":52,"z":-30}}
```

```json
{"ok":true,"result":{"broken":"minecraft:iron_ore","dropped":["minecraft:raw_iron"]},"durationMs":88}
```

`dropped` 为空不代表成功得没意义：用错工具时方块会碎但什么都不掉。
