# 保守档（CONSERVATIVE）

普通生存玩家手工能做的操作，不需要 op；不可逆或消耗资源时先问玩家一句。

| 工具 | 参数 | 返回 |
|---|---|---|
| `craft.recipe` | `recipeId`（先 `recipe.query` 拿）、可选 `body` | 成功/失败原因 |
| `block.machine` | `x`、`y`、`z`、`operation`（`insert_fuel` / `insert_input` / `take_output`）、可选 `playerSlot`、`body` | 成功/失败原因 |
| `container.transfer` | `x`、`y`、`z`、`containerSlot`、`playerSlot`（0–35）、`count`（1–64）、`toPlayer`（true=容器→玩家）、可选 `body` | `result.itemId`、`result.moved`、`result.message` |
| `block.interact` | `x`、`y`、`z`、可选 `body` | `result.blockId`、`result.interacted`、`result.message` |
| `block.place` | `item`、`x`、`y`、`z`、可选 `face`、`body` | `result.placed`、`result.message` |
| `block.place.line` | `item`、起点坐标、方向与数量（见 schema） | 逐块结果 |
| `server.say` | `message` | 成功/失败 |

## 例子：把原木搬进箱子

```
1) tianshu:container.inspect {}                  → result.open=true，槽位
2) tianshu:container.transfer
   {"x":120,"y":64,"z":-300,"containerSlot":0,"playerSlot":9,"count":8,"toPlayer":false}
   → result.moved=8
3) 回答："搬了八块原木进箱子。"
```

## 例子：放一个火把

```
确认："要在这里放火把，可以吗？"
tianshu:block.place
{"item":"minecraft:torch","x":120,"y":65,"z":-300}
→ result.placed=true
回答："放好了。"
```

## 注意

- `moved=0` / `placed=false` 时看 `message` 的原因（够不着、容器没开、槽位不对），不要假设成功。
- 失败后先回读（`container.inspect` / `inventory.inspect`）再决定是否重试。
- 不要传 `body`：那是给有身体的调用方的。