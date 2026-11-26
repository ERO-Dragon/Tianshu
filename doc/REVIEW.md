# 舟楫审查

> 逐条记录验收过程中发现的问题；修掉就删行，历史在 git 里。怎么跑见 [验收](验收.md)，契约见 [智能体能力中心](智能体能力中心.md)。

## 目录

- [1. 未修项](#1-未修项)
- [2. 已确认](#2-已确认)
- [3. 证据](#3-证据)

## 1. 未修项

| id | 位置 | 现象 | 建议 |
|---|---|---|---|
| `ENT-1` | `tianshu-adapter` 的 `ServerToolHandler` / `FabricServerToolHandler` 注册表 | 9 个实体工具在客户端有声明、有说明书，但服务端两个加载器都没有注册实现，因此永远不会出现在目录里、也永远调不通：`entity.craft`、`entity.interact`、`entity.machine`、`entity.transfer`、`entity.command.run`、`entity.block.inspect`、`entity.item.inspect`、`entity.world.info`、`entity.craft.available`。它们的玩家侧孪生（`craft.recipe`、`block.interact`、`block.machine`、`container.transfer`、`command.run`、`block.inspect`、`item.inspect`、`world.info`、`craft.available`）都注册了 | 两个 handler 各自补上这 9 个 `registrations.tool(...)`，或确认它们本该由另一处统一注册而那条路没接 |
| `ENT-2` | `tianshu-lab` 的 `ACC-BDY-1` | 这条用例从目录里挑 `entity.*` 工具，目录里只有 20 个，它就按 20 个判通过——正好把 `ENT-1` 那 9 个漏掉的情况判成 PASS | 断言数量或断言"29 个里少一个就失败"，别按目录里碰巧有几个来定基线 |

## 2. 已确认

- **档位两轴**：玩家侧 `READ_ONLY` / `CONSERVATIVE` / `FULL` 与实体侧 `SURVIVAL` / `CHEAT` 分开，`defaultAutonomy()` 对 body 抛错，不是靠分支绕；
- **两把上限独立**：`autonomy.ceiling` 与 `npc.ceiling` 各是各的端口与配置，启动日志各印一条；
- **实体动作三道判定**：工具最低档（`requiredBodyAutonomy`）、归属（controller 阶梯）、能力位（`EntityCapability`），加上按身体的移动方式判定；
- **目录属性分得清**：`BODY_WRITE` 写 `bodyAutonomy`，`BODY_READ` 不写，玩家工具仍写 `autonomy`；服务端推送按它过滤；
- **工具总体**：`ACC-ALL-1` 扫到 56 个工具行，全部给出信封，没有抛异常、没有超时（8 个真执行、48 个被参数校验或档位拒）。

## 3. 证据

- **实机**（NeoForge，默认配置）：`52 PASS / 4 SKIP / 1 FAIL`（`SELF-CHECK-1` 是刻意失败），`accepted=true`；
- **新增用例** `ACC-ALL-1`：把目录里的每个工具用空参调一遍，只判"有没有信封"，`56 tools answered (8 executed, 48 refused)`；
  `ACC-BDY-1` 同时报 `20 tools registered, documented, and every call answered an envelope`——这两个数字（29 − 20 = 9）就是 `ENT-1`；
- **注册表核对**：`ServerToolHandler.java` 与 `FabricServerToolHandler.java` 的 `registrations.tool(...)` 清单里没有那 9 个实体工具的类；
- **单测**：api 240、core 191、client 319（1 skip 符号链接）、testkit 4，0 失败；全量 `check` 与 13 个门禁绿。