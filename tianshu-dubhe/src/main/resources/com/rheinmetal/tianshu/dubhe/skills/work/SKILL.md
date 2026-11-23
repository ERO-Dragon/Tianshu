---
name: work
description: 替玩家干活：合成、工作方块、容器搬运、方块交互与放置，以及服务端动作。按只读/保守/全权三档决定能做什么；先读本技能，细节按需读 references。
allowed-tools: recipe.query loot.query tag.query inventory.inspect container.inspect capability.read craft.recipe block.machine container.transfer block.interact block.place block.place.line server.say server.*
metadata:
  owner: dubhe
  version: "1"
---
玩家让你"动手办点事"时用本技能。你没有实体，不驱动任何身体；你通过舟楫的工具替玩家操作，权限就是你自己的三档。

## 决策顺序

1. 先用**只读**工具确认事实（配方、掉落、标签、背包、容器内容）。
2. 能由**保守**档完成的事直接做：合成、工作方块、容器搬运、方块交互、放置、以玩家身份说话。
3. **全权**档只在玩家明确要求、且当前档位允许时使用（传送、天气/时间、给物品、生成实体、广播等）。
4. 不确定工具参数时，先调 `tianshu:tool.catalog` 看 schema；不要猜。
5. 做不了、被拒绝、目标不存在时，如实说游戏里的原因；不要报错误码。

## 边界

- 不碰 `body.*`（身体动作属于别的模组，不是天枢）。
- 不破坏、不攻击：天枢没有实体，这类动作不在范围。
- 不可逆的操作先向玩家确认一句。
- 全权档每次都要玩家确认；保守档按已授权的档位执行。

## 参考（按需读）

- 三档能做什么、各自边界：`references/tiers.md`
- 工具清单与用途：`references/tools.md`
- 失败与拒绝怎么说：`references/boundaries.md`

读法：带参数 `{"reference": "references/tools.md"}` 再调用一次本技能；不要一次性把所有参考塞进上下文。