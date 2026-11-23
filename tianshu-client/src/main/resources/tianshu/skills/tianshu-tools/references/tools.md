# 工具参数与返回

工具 id 前缀 `tianshu:`；参数是 JSON 对象，字段名必须与下表一致。未列出的工具先用 `tianshu:tool.catalog` 查 schema。

## 只读

| 工具 | 参数 | 返回 |
|---|---|---|
| `recipe.query` | `outputItemId`（必填，如 `minecraft:iron_pickaxe`）、`limit`（1–64） | `result.recipes[]`，每条含 id 与原料 |
| `loot.query` | 方块/实体 id | `result` 掉落表 |
| `tag.query` | 物品/方块 id 或标签 | `result` 匹配清单 |
| `inventory.inspect` | `{}` | 槽位与物品 |
| `container.inspect` | `{}` | `open`、槽位布局 |
| `capability.read` | 可选 `id` / `file` / `page` / `arguments` | `result.text` |

## 保守（写操作）

| 工具 | 参数 | 返回 |
|---|---|---|
| `craft.recipe` | `recipeId`（先 recipe.query 拿）、可选 `body` | 成功/失败原因 |
| `block.machine` | `x`、`y`、`z`、`operation`（`insert_fuel` / `insert_input` / `take_output`）、可选 `playerSlot`、`body` | 成功/失败原因 |
| `container.transfer` | `x`、`y`、`z`、`containerSlot`、`playerSlot`（0–35）、`count`（1–64）、`toPlayer`（true=容器→玩家） 、可选 `body` | `result.itemId`、`result.moved`、`result.message` |
| `block.interact` | `x`、`y`、`z`、可选 `body` | `result.blockId`、`result.interacted`、`result.message` |
| `block.place` | `item`（如 `minecraft:torch`）、`x`、`y`、`z`、可选 `face`、`body` | `result.placed`、`result.message` |
| `block.place.line` | `item`、起点坐标、方向与数量（见 schema） | 逐块结果 |
| `server.say` | `message` | 成功/失败 |

`body` 只给"有身体"的调用方使用；天枢没有实体，**不要传**。

## 全权

`server.*`（传送、天气、时间、给物品、生成实体、广播、经验等）：参数按各自 schema；每次调用前确认。详见 `full-actions.md`。

## 读结果

- `ok` 为 false：读 `code`/`message`；对玩家只说游戏原因。
- 写操作：看 `placed` / `moved` / `interacted` 与 `message`，不要假设成功。
- 坐标类失败通常是"没有目标/够不着"；先回读再重试。