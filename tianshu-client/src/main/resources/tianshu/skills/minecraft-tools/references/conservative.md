# 保守档（CONSERVATIVE）

普通生存玩家手工能做的操作，不需要 op。不可逆或消耗资源时先问玩家一句。

| 工具 | 参数 | 返回 |
|---|---|---|
| `tianshu:craft.recipe` | `recipeId`（先 `craft.available` 拿）、`body` | `itemId`、`count`、`crafted`、`message` |
| `tianshu:block.machine` | `x`、`y`、`z`、`operation`（`insert_fuel`/`insert_input`/`take_output`）、`playerSlot`、`body` | `machineId`、`operation`、`itemId`、`moved`、`message` |
| `tianshu:container.transfer` | `x`、`y`、`z`、`containerSlot`、`playerSlot`、`count`、`toPlayer`（true = 容器→玩家）、`body` | `itemId`、`moved`、`message` |
| `tianshu:block.interact` | `x`、`y`、`z`、`body` | `blockId`、`interacted`、`message` |
| `tianshu:block.place` | `item`、`x`、`y`、`z`、`face`、`body` | `blockId`、`x`、`y`、`z`、`placed`、`message` |
| `tianshu:block.place.line` | `item`、`x`、`y`、`z`、`direction`（north/south/east/west）、`count`、`body` | 逐块结果，中途失败即停 |
| `tianshu:block.break` | `x`、`y`、`z`、`body` | 破坏结果与掉落（按工具与方块规则） |
| `tianshu:entity.use` | `entity`、`body` | 交互类型与结果（喂食/驯服/拴绳/交易） |
| `tianshu:item.use` | `x`、`y`、`z`、`face`、`holdTicks`、`body` | 用掉什么（只支持即时用法） |
| `tianshu:server.say` | `text` | 以玩家身份说一句 |

## 例子：把原木搬进箱子

```
1) tianshu:container.inspect {}                       → result.open=true，entries 里是槽位
2) tianshu:container.transfer
   {"x":120,"y":64,"z":-300,"containerSlot":0,"playerSlot":9,"count":8,"toPlayer":false}
   → {"ok":true,"result":{"itemId":"minecraft:oak_log","moved":8,"message":"..."}}
3) 回答："搬了八块原木进箱子。"
```

## 例子：放一个火把

```
先问："要在这里放火把，可以吗？"
tianshu:block.place {"item":"minecraft:torch","x":120,"y":65,"z":-300}
→ {"ok":true,"result":{"blockId":"minecraft:torch","placed":true,...}}
回答："放好了。"
```

## 注意

- `moved=0` / `placed=false` / `crafted=false` 时读 `message` 的原因（够不着、容器没开、材料不够），不要假设成功。
- 失败后先回读（`container.inspect` / `inventory.inspect`）再决定是否换做法；不要重发同一次调用。
- `body` 只给有身体的调用方；没有身体就不要传。