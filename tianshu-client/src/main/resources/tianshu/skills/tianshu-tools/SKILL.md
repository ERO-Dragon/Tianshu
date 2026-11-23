---
name: tianshu-tools
description: 舟楫工具的调用格式与用法：一次调用要输出什么参数、返回长什么样、按问题该先调哪个工具；三档授权与确认规则。模型读完应能直接写出工具调用。
---
# 舟楫工具（公共）

本技能教你**怎么调用**舟楫工具；"什么时候该做什么"由各模组自己的业务技能写。

## 1. 一次调用长什么样

- 选一个工具 id（形如 `tianshu:recipe.query`）；
- 输出一个**参数对象**（JSON），字段名必须与工具 schema 一致；
- 拿回结果对象：成功是 `{ "ok": true, "result": { ... } }`，失败是 `{ "ok": false, "code": "...", "message": "..." }`；
- **不要用自然语言代替调用**（例如"我帮你把木头搬过去"之前，必须先真的调用 `container.transfer`）；
- 参数不确定时，先调用保留工具 `tianshu:tool.catalog` 取 schema，**不要猜字段名**；
- 读技能/参考用 `tianshu:capability.read`：不带 `id` 列目录、带 `id` 读正文、带 `file` 读参考、长文带 `page`。

## 2. 按问题选第一个工具

| 玩家的话 | 先调 | 参数 | 返回里看什么 |
|---|---|---|---|
| "铁镐怎么做" / "这个配方要什么" | `tianshu:recipe.query` | `{"outputItemId":"minecraft:iron_pickaxe","limit":8}` | `result.recipes[]` 的原料清单 |
| "这个方块掉什么" | `tianshu:loot.query` | 方块或实体 id | `result` 掉落表 |
| "哪些木头算木板" | `tianshu:tag.query` | 标签或物品 id | 匹配的物品/方块 |
| "我背包里有什么" | `tianshu:inventory.inspect` | `{}` | 槽位与物品 |
| "箱子里有什么"/“把箱子打开” | `tianshu:container.inspect` | `{}` | `open`、槽位布局 |
| "把这些木头搬进箱子" | `tianshu:container.transfer` | 坐标 + 两侧槽位 + `count` + `toPlayer` | `result.moved` / `message` |
| "做一把铁镐" | `tianshu:craft.recipe` | `{"recipeId":"..."}`（先 recipe.query 拿 id） | 成功/失败原因 |
| "按一下按钮" / "开这扇门" | `tianshu:block.interact` | `{"x":..,"y":..,"z":..}` | `interacted`、`message` |
| "在这儿放个火把" | `tianshu:block.place` | `{"item":"minecraft:torch","x":..,"y":..,"z":..}` | `placed`、`message` |
| "给熔炉添煤/取出产物" | `tianshu:block.machine` | `{"x":..,"y":..,"z":..,"operation":"insert_fuel"}` | 成功/失败原因 |
| "把我传送到……"（全权） | `tianshu:server.*` | 见 `references/full-actions.md` | 成功/失败 |

坐标不确定时先读 `game_context` / 用只读工具确认；不要编坐标。

## 3. 三个完整例子

**例一：问配方**

```
调用 tianshu:recipe.query
参数 {"outputItemId":"minecraft:iron_pickaxe","limit":4}
返回 {"ok":true,"result":{"recipes":[{"id":"...","ingredients":[...]}]}}
回答："铁镐要三块铁锭加两根木棍。"
```

**例二：搬物品**

```
先 tianshu:container.inspect 参数 {}   → result.open / 槽位
再 tianshu:container.transfer
参数 {"x":120,"y":64,"z":-300,"containerSlot":0,"playerSlot":9,"count":8,"toPlayer":false}
返回 {"ok":true,"result":{"itemId":"minecraft:oak_log","moved":8,"message":"..."}}
回答："搬了八块原木进箱子。"
```

**例三：放方块（写操作，先确认）**

```
确认："要在这里放一个火把，可以吗？"
调用 tianshu:block.place
参数 {"item":"minecraft:torch","x":120,"y":65,"z":-300}
返回 {"ok":true,"result":{"placed":true,"message":"..."}}
回答："放好了。"
```

## 4. 结果与失败

- 先看 `ok`；`ok=false` 时读 `code` 与 `message`，**对玩家只说游戏原因**，不念错误码；
- 写操作返回 `placed=false` / `moved=0` 时，看 `message` 里的原因（够不着、容器没开、槽位不对）；
- 失败后先回读状态（`inventory.inspect` / `container.inspect` / `block.interact` 结果），再决定是否换做法；
- 不要为了"成功"重复执行会写入世界的调用。

## 5. 三档与确认

| 档位 | 能做什么 | 确认 |
|---|---|---|
| `READ_ONLY` | 只读查询 | 不需要 |
| `CONSERVATIVE` | 合成、熔炉、搬运、交互、放置、玩家身份说话 | 不可逆或消耗资源时先问一句 |
| `FULL` | 传送、天气、时间、给物品、生成实体、广播等 | **每次**先确认 |

档位不够就直说做不了；不要绕过权限。

## 6. 参考

- `references/tools.md`：每个工具的准确参数与返回字段；
- `references/tiers.md`：三档边界；
- `references/full-actions.md`：全权动作清单与注意事项。