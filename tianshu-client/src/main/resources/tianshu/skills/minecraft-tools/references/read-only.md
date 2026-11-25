# 只读档（READ_ONLY）

只读事实，不改变世界与玩家状态，不需要确认。返回空列表是合法结果，不是失败。

| 工具 | 参数 | 返回 |
|---|---|---|
| `tianshu:recipe.query` | `outputItemId`（必填）、`limit`（1–64） | 能产出它的配方 |
| `tianshu:loot.query` | `blockId` 或 `entityId`（二选一） | 掉落表 |
| `tianshu:tag.query` | `kind`（item/block）+ `itemId` 或 `tagId` | 标签或标签成员 |
| `tianshu:craft.available` | `limit`、`body` | 现在能合成的配方，含是否需要工作台 |
| `tianshu:block.inspect` | `x`、`y`、`z`、`body` | 方块 id、状态、里面的物品/流体/能量 |
| `tianshu:container.inspect` | 无 | `open`、`title`、`entries[]`（槽位 + 物品） |
| `tianshu:inventory.inspect` | 无 | 已占用的槽位与数量 |
| `tianshu:item.inspect` | `slot`、`body` | 物品 id、数量、耐久、附魔、自定义名 |
| `tianshu:entity.inspect` | `entity`、`body` | 类型、位置、血量、是否驯服及归属 |
| `tianshu:world.info` | `body` | 维度、群系、时间、天气、难度、光照 |
| `tianshu:world.locate` | `structure` 或 `biome`（二选一）、`radius`、`body` | 坐标与采样数 |
| `tianshu:client.audio.list` | 无 | 音量通道与百分比 |
| `tianshu:client.pack.list` | 无 | 材质包 id、名称、是否启用 |
| `tianshu:tool.catalog` | 无 | 工具表与参数 schema |
| `tianshu:capability.read` | `id` / `file` / `page` | 能力说明或技能正文 |

实体身体自己的只读工具（`body.status`、`body.inventory`、`body.scan`、`body.list`）在 `tianshu:body-tools`。

## 例子

```
tianshu:container.inspect {}
→ {"ok":true,"result":{"open":true,"title":"Chest","entries":[{"slot":0,"item":"minecraft:diamond","count":5}]},"durationMs":2}
```

```
tianshu:recipe.query {"outputItemId":"minecraft:chest","limit":4}
→ {"ok":true,"result":{"recipes":[{"id":"minecraft:chest","ingredients":[...]}]},"durationMs":4}
```

## 注意

- `container.inspect` 的 `open=false` 是"玩家没开箱子"，不是错误：先让玩家打开容器。
- 坐标与槽位不要编：位置从 `body.status` / `world.info` 来，槽位从 `inventory.inspect` / `container.inspect` 来。
- 写操作之前用这里确认前提（材料够不够、容器开没开、槽位对不对）。