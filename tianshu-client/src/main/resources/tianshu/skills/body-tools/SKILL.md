---
name: body-tools
description: 用实体（身体）做事：扫描、移动、看、挖、攻击、捡、丢、装备、给、列清单、读状态、串几步。先判断这具身体被允许做到哪一级，再读对应参考。
covers: tianshu:body.scan, tianshu:body.move, tianshu:body.look, tianshu:body.mine, tianshu:body.attack, tianshu:body.pickup, tianshu:body.drop, tianshu:body.equip, tianshu:body.give, tianshu:body.inventory, tianshu:body.list, tianshu:body.status, tianshu:body.sequence
---
# 实体（身体）操作

本技能讲**怎么指挥一具身体**：先看清它在哪、它能做什么，再一步一步让它做。身体是一个真的玩家实体，
所以它受物理、碰撞、背包和区块加载的约束——没有"瞬间搬完"这回事，工具也会因此失败。

## 1. 先问三件事，再动手

1. **哪一具**：`tianshu:body.list` 列出身体 id 与状态；不带 `body` 参数时工具作用于调用者默认的那具。
2. **它在哪**：`tianshu:body.status` 给出位置、维度、血量、饥饿、手上物品；`tianshu:body.scan` 扫周围有什么。
3. **它被允许做到哪**：生存级（SURVIVAL）只能做玩家不靠作弊能做的事；特权级（PRIVILEGED）才允许飞行与瞬移。
   服务器上限不够时，工具会明确拒绝并说明原因——不要改参数硬试。

## 2. 按档位读参考

| 要做的事 | 先读 |
|---|---|
| 走路、挖、打、捡、放、装备、看背包 | [references/survival.md](references/survival.md) |
| 飞行、瞬移，或一次串好几步 | [references/privileged.md](references/privileged.md) |

## 3. 三条硬规矩

- **不要用自然语言代替调用**：说"挖到了"之前必须真的调过 `tianshu:body.mine`，并读过它的返回。
- **失败要读原因**：`距离太远`、`背包满`、`目标不是方块`、`被允许的级别不够` 是四种完全不同的事，
  对应四种不同的下一步；把失败当"没成功"重试只会重复同一个错误。
- **坐标是绝对坐标**：`body.move` 的 `x/y/z` 是世界坐标，不是"向前三格"。移动前用 `body.status` 确认起点。

## 4. 一次做多步

`tianshu:body.sequence` 按顺序跑一串 `{action, args}`，中途失败即停并告诉你停在第几步、为什么。
它适合"扫→走过去→挖→捡"这类固定流程；需要判断的流程不要硬塞进它，读一步的结果再决定下一步。