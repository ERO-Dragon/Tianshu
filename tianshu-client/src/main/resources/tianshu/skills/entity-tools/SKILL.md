---
name: entity-tools
description: Minecraft 实体侧工具：让身体（实体化身）代替玩家行动——移动、挖掘、攻击、拾取、装备、交付、扫描、读自身状态，以及有界序列。当你要通过身体做事、不确定参数或返回字段时读它——一个工具一份 reference，文件名就是工具 id。
---
# 实体侧工具

这一组通过**身体**（entity body，一个可以移动和操作的实体化身）行动：移动、挖掘、攻击、
拾取、装备与交付、扫描周围、读自身状态，以及把多步动作绑成一次有界调用。

## 与 player-tools 互斥

以**玩家本人**身份行动的那一套是 `minecraft:player-tools`。两者**互斥**：

- 本组每个工具都由**身体**执行，`body` 参数必填（你自己就是那具身体时可以省略）；
- 同一个动作在那边有另一个 id：`entity.place` ↔ `block.place`、`entity.transfer` ↔ `container.transfer`、
  `entity.craft` ↔ `craft.recipe`、`entity.mine` ↔ `block.break`……那一边以玩家身份执行，用的不是这具身体的背包与权限；
- 你看不到的那一套不会出现在工具表里——不是坏了，是不适用。

## 动手之前

- 先用 `minecraft:entity.list` 看清有哪些身体、各自能做什么；动作走那具**不能做**的工具会被明确拒绝；
- 坐标来自 `minecraft:entity.status` 或 `minecraft:entity.scan`，不要凭玩家位置推断身体位置；
- 一次调用只推进一步，靠 `minecraft:entity.sequence` 组合（上限 32 步，失败即停并给出步号）。

## 一次调用长什么样

- 工具 id 形如 `minecraft:entity.mine`；参数是 JSON 对象，字段名必须与 schema 一致；
- 返回：成功 `{ "ok": true, "result": { ... }, "durationMs": 31 }`，失败 `{ "ok": false, "code": "...", "message": "..." }`；
- 身体工具默认**关闭**，由宿主按开关放行；被关掉时回 `DISABLED`，不要重试。

## 失败怎么读

| 现象 | 含义 | 下一步 |
|---|---|---|
| `OUT_OF_RANGE` | 够不着，或不在同一维度 | 先 `entity.move` 靠近，别重发同一坐标 |
| `NO_BODY` / 找不到身体 | 这具身体不存在或已消失 | `entity.list` 重新确认 |
| 结果里 `blocked` / `moved=0` | 路被挡或材料不够 | 先 `entity.scan` 看地形，再改路线 |
| `NOT_PERMITTED` | 这具身体没有该能力位 | 换一具身体，或换成玩家侧的等效工具 |

## 需要一条命令时，走审批出口

`minecraft:entity.command.run` 是本组唯一的"任意命令"入口，也是全权档在实体侧的**唯一载体**：
第一次调用只返回一次性令牌，操作员批准后才真正执行，且一条令牌只跑一次
（详见 `references/entity.command.run.md`）。命令以**那具身体**的身份与权限执行。

玩家侧不走这里：那边有 14 个结构化的 `minecraft:server.*` 工具（`gamemode`、`time`、`weather`…），
参数有枚举校验、开关也分项；需要一条任意命令时玩家侧有 `minecraft:command.run`。
两边各有一套，不要交叉使用。

## 参考资料

`references/` 下每个工具一份，文件名就是去掉命名空间的工具 id（`entity.mine.md`）。
用 `minecraft:capability.read` 带工具 id 读最省事：

```json
{"id":"minecraft:capability.read","args":{"id":"minecraft:entity.mine"}}
```