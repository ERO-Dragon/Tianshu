# 只读档（READ_ONLY）

只读事实，不改变世界与玩家状态；不需要确认。

| 工具 | 参数 | 返回 |
|---|---|---|
| `recipe.query` | `outputItemId`（必填，如 `minecraft:iron_pickaxe`）、`limit`（1–64） | `result.recipes[]`，每条含 id 与原料 |
| `loot.query` | 方块/实体 id | `result` 掉落表 |
| `tag.query` | 物品/方块 id 或标签 | `result` 匹配清单 |
| `inventory.inspect` | `{}` | 槽位与物品 |
| `container.inspect` | `{}` | `open`、槽位布局 |
| `capability.read` | 可选 `id` / `file` / `page` / `arguments` | `result.text` |

## 例子

```
调用 tianshu:recipe.query
参数 {"outputItemId":"minecraft:chest","limit":4}
返回 {"ok":true,"result":{"recipes":[{"id":"minecraft:chest","ingredients":[...]}]}}
```

```
调用 tianshu:inventory.inspect
参数 {}
返回 {"ok":true,"result":{"slots":[{"slot":0,"item":"minecraft:oak_log","count":8}]}}
```

## 注意

- 只读工具返回空列表是合法结果，不是失败；如实说"没有找到"。
- 坐标、槽位不要编；需要位置时从 `game_context` 或只读结果里拿。
- 写操作前先用这里确认条件（材料够不够、容器开没开、槽位对不对）。