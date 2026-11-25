# 全权档（FULL）

作弊类动作：需要 operator、服务端对应开关，且受服务端上限约束。**每次调用前都要向玩家确认。**

| 工具 | 参数 | 做什么 |
|---|---|---|
| `tianshu:server.teleport` | `x`、`y`、`z` | 把玩家传送到当前维度的坐标 |
| `tianshu:server.give` | `item`、`count` | 直接发物品；装不下掉在脚下 |
| `tianshu:server.spawn_entity` | `type`、`count` | 在玩家位置生成实体 |
| `tianshu:server.gamemode` | `value`（survival/creative/adventure/spectator） | 改游戏模式 |
| `tianshu:server.effect` | `effect`、`seconds`、`amplifier`；`effect="clear"` 清空 | 加状态效果或清空 |
| `tianshu:server.xp` | `levels`（可为负） | 改经验等级 |
| `tianshu:server.enchant` | `enchantment`、`level` | 给主手物品附魔 |
| `tianshu:server.setblock` | `x`、`y`、`z`、`block` | 在绝对坐标放一个方块 |
| `tianshu:server.kill` | `id` | 清除玩家所在维度里的一个实体 |
| `tianshu:server.time` | `time`（day/noon/night/midnight 或 `ticks:<n>`） | 改时间 |
| `tianshu:server.weather` | `weather`（clear/rain/thunder） | 改天气 |
| `tianshu:server.difficulty` | `value`（peaceful/easy/normal/hard） | 改难度 |
| `tianshu:server.gamerule` | `rule`、`value` | 改白名单游戏规则（doDaylightCycle、doMobSpawning、keepInventory、mobGriefing、naturalRegeneration、doImmediateRespawn） |
| `tianshu:server.broadcast` | `text` | 系统公告（与 `server.say` 不同） |

## 例子

```
先确认："要把时间设成夜晚，可以吗？"
tianshu:server.time {"time":"night"}
→ {"ok":true,"result":{...},"durationMs":3}
```

```
tianshu:server.give {"item":"minecraft:diamond","count":8}
→ 成功；若背包满，多出来的掉在脚下（这也是成功）
```

## 调用前

1. 用只读档确认目标存在（坐标、实体、玩家）；
2. 一句话说明将要发生什么，等玩家同意；
3. 参数名以本表为准，不确定再查 `tianshu:tool.catalog`；
4. 失败按游戏原因回报，不重试会重复写入的操作。

## 失败与下一步

- `DISABLED`：服务端没开这个动作的开关 → 告诉玩家去哪开，不要换别的工具绕过。
- `NO_PERMISSION`：调用者不是 operator → 说明需要 op，不要重试。
- `INVALID_ARGS`：id 写错或超范围（如 `count` 非正数）→ 修参数，别重复同一调用。

## 与保守档的区别

- `server.say` 是保守档：以玩家身份说一句；`server.broadcast` 是全权档：系统公告。
- 全权动作不要描述成"顺手做了"：它改的是服务器状态，要先说再做。