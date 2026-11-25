# 装备

`tianshu:body.equip` 按槽位装备物品；`slot` 与 `body.inventory` 的索引一致。

- 挖矿/攻击前先装备，否则用错工具或空手，效率和掉落都不一样。
- 装备会把手上的东西换下来，换下来的东西还在背包里。

## 范例

```json
{"id":"tianshu:body.equip","args":{"slot":36,"target":"mainhand"}}
```

```json
{"ok":true,"result":{"mainhand":"minecraft:iron_pickaxe","previous":"minecraft:dirt"},"durationMs":4}
```
