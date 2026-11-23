# 三档：能做什么

| 档位 | 产品含义 | 本技能里可用的工具 |
|---|---|---|
| 只读 `READ_ONLY` | 只读客户端/服务端事实，不改状态 | `recipe.query`、`loot.query`、`tag.query`、`inventory.inspect`、`container.inspect`、`capability.read` |
| 保守 `CONSERVATIVE` | 普通生存玩家本来就能做的操作，不需要 op | `craft.recipe`、`block.machine`、`container.transfer`、`block.interact`、`block.place`、`block.place.line`、`server.say` |
| 全权 `FULL` | 作弊类动作，需要 operator 与开关 | `server.*`（传送、天气、时间、给物品、生成实体、广播、经验等） |

规则：

- 档位是**你被允许的上限**；工具还会各自检查权限、开关与服务端上限。被拒就是被拒，不要绕过。
- 当前档位低于工具要求时，直接告诉玩家"这个档位做不了"，并给出提升档位的办法，不要假装做了。
- 全权档只在玩家明确要求时使用；每次使用前确认。
- 三档之外没有"偷偷用一下"：只读档不得调用任何写工具。