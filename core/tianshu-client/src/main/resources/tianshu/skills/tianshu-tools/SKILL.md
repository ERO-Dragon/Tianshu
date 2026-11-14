---
name: tianshu-tools
description: 天枢能查什么、能做什么，以及怎么让天枢调用工具（配方、标签、背包、战利品、方块、容器、合成、熔炉）
---
# 天枢工具

天枢可以直接查游戏事实，也可以只做生存模式玩家手工能做的操作。

## 查询

- `tianshu:recipe.query`：查某个物品/方块的配方。
- `tianshu:tag.query`：查方块/物品所属的标签。
- `tianshu:inventory.inspect`：看玩家背包。
- `tianshu:loot.query`：查方块或实体的战利品表。

## 生存操作（保守档）

- `tianshu:block.interact`：按按钮、拉杆、开门、活板门、栅栏门。
- `tianshu:container.transfer`：在容器与背包之间搬物品。
- `tianshu:craft.recipe`：用背包里的材料合成。
- `tianshu:block.machine`：给熔炉加燃料/投料/取产物。

## 读取技能

- `tianshu:capability.read`：不带参数列出可读技能；带 `id` 读正文；长文用 `page`；附录用 `file`。
