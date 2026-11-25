# 背包

`minecraft:entity.inventory` 返回槽位 → 物品与数量。

- `entity.equip` / `entity.drop` 的 `slot` 就用这套索引，别自己另算一套。
- 捡东西之前先看这里有没有空间，比失败后再回来省一次往返。

## 范例

```json
{"id":"minecraft:entity.inventory","args":{}}
```

```json
{"ok":true,"result":{"slots":[{"slot":36,"item":"minecraft:iron_pickaxe","count":1},{"slot":37,"item":"minecraft:torch","count":12}]},"durationMs":2}
```
