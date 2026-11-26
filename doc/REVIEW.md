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
| `NET-1` | 联机（专用服 + 客户端）时的客户端目录 | 客户端 `TianshuTools.catalog(caller, CLIENT)` 只列出 **9** 个本地工具（单机同一份用例是 42 个），所有实体用例因此以"entity tools require a Ferry server"跳过；但**调用是通的**——同一轮服务端审计日志里有 `server.difficulty`、`server.gamerule` 的 `ok=true`。即"列不出来、却能调通"，模型只能靠猜 id | 查登录时的服务端目录推送在客户端有没有被 `ServerToolDeclarations.activate` 吃下去；联机下目录应当是服务端说了算，而不是退化成本地 9 个 |
| `INV-1` | `InventoryQuerySource.lookup()`（适配层） | 没有玩家时返回**空背包快照**而不是"不可用"：`player == null` 直接 `return new InventorySnapshot(List.of())`。世界切换、加载中、标题界面都可能是这个状态，调用方拿到的是"背包是空的"而不是"现在读不到"。本次两世界切换后工具控制台在 A 与 B 都返回 `{"slots":[]}`，而切换前的截图里热栏第 1 格有物品——两者至少有一个是错的，靠这条实现无法分辨 | 无玩家时返回 `UNAVAILABLE`（或带一个 `known=false`），别用空列表冒充答案；同时核对这一次 `slots: []` 到底是时机还是实现读错 |
| `NET-2` | `tianshu-lab` 的 `LabNeoForgeMod` | 它用 `configurationBidirectional` 注册 `tianshu_lab:presence`，**没有 `.optional()`**，所以装了 lab 的服务端会把任何没装 lab 的客户端挡在配置阶段：实机截图是"连接已丢失 / 网络通道名称 `tianshu_lab:presence` / 客户端缺少此服务器需要的网络通道"。后果：开发与验收服上根本做不了"无舟楫客户端单侧安装"；一个测试台模组也不该成为进服的硬门槛 | 给这个 payload 注册加 `.optional()`（客户端没有时退化成"不支持"，而不是踢人）；舟楫自己的 `TianshuNetwork` 已经是 `.optional()`，只有 lab 这一处不是 |
| `HUD-1` | HUD 左侧的模组列表 | 记录模组的那个列表会**溢出左栏边界**（你看到的） | 列表宽度/裁剪按左栏可用宽度算，超出部分省略或换行，别画到栏外 |

## 2. 已确认

- **档位两轴**：玩家侧 `READ_ONLY` / `CONSERVATIVE` / `FULL` 与实体侧 `SURVIVAL` / `CHEAT` 分开，`defaultAutonomy()` 对 body 抛错，不是靠分支绕；
- **两把上限独立**：`autonomy.ceiling` 与 `npc.ceiling` 各是各的端口与配置，启动日志各印一条（`ceiling=full npc.ceiling=SURVIVAL ...`）；
- **开关逐个独立**：`ACC-ACT-5` 一次开一个开关、跑 14×14，每个动作只跟自己的开关走；配置键名与工具 id 的对应（`set_time`→`server.time`、`broadcast`→`server.broadcast`）在 `ServerActionTools` 与 `ServerActionConfig.switchFor` 里逐个核对过，14 个都对得上；
- **`FULL` 档全开可执行**：`ACC-ACT-4` 在 `ceiling=full` + 14 开关全开时 10 个动作真的执行（服务端审计日志逐条 `ok=true`），4 个被自己的参数校验拒；
- **工具总体**：`ACC-ALL-1` 扫到 56 个工具行，全部给出信封，没有抛异常、没有超时。**注意这条只证明"接线通"**：空参被 schema 拒的占多数（48 refused），所以扫到的 56 与档位无关，缺的 9 个是注册问题（`ENT-1`），不是档位问题；
- **一个进程三个会话**：世界 A → 标题 → 世界 B → 标题 → 回 A 全部在同一进程，世界 B 与回到 A 之后各调一次工具控制台，`tool.console` 都是 `ok=true`（`durationMs` 9 和 0）；`ClientTools` 的一次性注册守卫生效，第二次登录没有注册冲突报错；
- **基座的网络通道是可选的**：`TianshuNetwork` 用 `event.registrar(...).optional()` 注册，四个 payload 都是 optional——"服务端装、客户端不装"在基座侧是按这个前提写的，卡住单侧安装的是 lab（`NET-2`），不是舟楫本身。

## 3. 证据

- **单机，默认配置**：`52 PASS / 4 SKIP / 1 FAIL`，`accepted=true`；
- **单机，`ceiling=full` + 14 开关全开 + `command_run` 开**：`56 PASS / 1 SKIP / 1 FAIL`（`ACC-ACT-2` 因 teleport 已开而跳过"关"的那一半），`accepted=true`；
- **联机，专用服（同样 full/全开）+ 客户端加入**：`51 PASS / 6 SKIP / 1 FAIL`，`accepted=true`；跳过的 6 条里有 5 条是身体用例（原因见 `NET-1`），第 6 条是 `ACC-ACT-2`；
- **服务端侧证据**：联机那轮的服务端 stderr 有 `write tool minecraft:server.difficulty ... ok=true`、`...server.gamerule ... ok=true`，证明跨进程转发与开关都生效；
- **单测**：api 240、core 191、client 319（1 skip 符号链接）、testkit 4，0 失败；全量 `check` 与 13 个门禁绿；
- **新增用例**：`ACC-ALL-1`（每个工具空参要有信封，detail 里带扫到的 id）、`ACC-ACT-5`（每个动作只跟自己的开关走）。