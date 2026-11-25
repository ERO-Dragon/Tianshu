# 扫描周围

`minecraft:entity.scan` 按半径列出周围的方块与实体，带坐标与 id。

- 它是"我周围有什么"的唯一可靠来源；不要用坐标或记忆去猜。
- 半径开大一点代价是多几行输出，不是多几次调用——先看清再动手通常更省。
- 返回里实体与方块是分开的：找目标先看实体，找落脚点/矿脉看方块。

## 范例

```json
{"id":"minecraft:entity.scan","args":{"radius":16,"blocks":"minecraft:iron_ore"}}
```

```json
{"ok":true,"result":{"blocks":[{"id":"minecraft:iron_ore","x":118,"y":52,"z":-30}],"entities":[]},"durationMs":6}
```

只给 `blocks` 时不会列出实体；两个都给才是"这里有什么"的完整回答。
