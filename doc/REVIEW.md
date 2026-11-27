# 舟楫审查

> 逐条记录验收过程中发现的问题；修掉就删行，历史在 git 里。怎么跑见 [验收](验收.md)，契约见 [智能体能力中心](智能体能力中心.md)。

## 目录

- [1. 待实机复验](#1-待实机复验)
- [2. 已确认](#2-已确认)
- [3. 证据](#3-证据)

## 1. 待实机复验

以下两项等待实机复验；`NET-1` 已关闭（见 1.1）。

| id | 已处理 | 待验证 |
|---|---|---|
| `INV-1` | 没有本地玩家时返回 `UNAVAILABLE`，有玩家时读取该玩家的非空槽位。 | 旧截图和空背包回复缺少同一时刻的槽位记录，需在切换前后对照游戏界面。 |
| `HUD-1` | 设置界面的模组导航标题按左栏剩余宽度裁切，给修改标记和滚动条留出空间。 | 窄窗口、长模组名下目视复验。 |

### 1.1 `NET-1` 的根因与修法

现象是「客户端目录只有 9 个本地工具，实体用例全跳过」。客户端加一个探针后才看清分层：

```
entries=40 available=true activated=40 hasTime=false
```

**目录到得了客户端**（40 条，40 个声明被激活、可用了），只是联机那一侧根本没人往上递——`hasChannel` 在 `PlayerLoggedInEvent` 那一刻是 false，而 NeoForge 那个 handler 查一次就 return，**没有任何重试**（Fabric 另挂了一个「通道稍后宣告」的事件才补上一次）。所以联机时目录从未发出过，客户端永远停在 9 个本地工具上。

修法（结构，不是补丁）：

- `ServerToolSession` 记住谁在等目录，每个 tick 问一次加载器给的 `ServerCatalogueChannel`；通道可用了就注册变化推送并停手，玩家退出时清掉。**时机只有一处决定**，两个加载器不能再各答一次；
- 两个 handler 的 `onPlayerLogin/onPlayerLogout` 缩成一行转发，各自的 `sendCatalog` 删掉；加载器只实现 `ready`/`send`；
- 把一个连接的协议版本收成一份：目录原来注册在比其它通道更新的版本上（`4` vs `3`），等于「服务端被允许点名、却没有能发出去的版本」。现在四条通道同一个 registrar。

复验（四组，都是本轮实机跑出来的）：

| 环境 | 结果 |
|---|---|
| NeoForge 单机、默认配置 | 54 PASS / 5 SKIP / 1 FAIL，`accepted=true`；`ACC-ALL-1` **67** 个工具，`ACC-EXP-1` 52，`ACC-BDY-1` 28 |
| NeoForge 专用服 + 客户端（`=server`） | 49 PASS / 10 SKIP / 1 FAIL，`accepted=true`；`ACC-EXP-1` 9 → **52**，`ACC-BDY-1` SKIP → **28**，`ACC-ALL-1` 51 |
| Fabric 单机 | 54 PASS / 5 SKIP / 1 FAIL，`accepted=true`；67 个工具 |
| Fabric 专用服 + 客户端（`=server`） | 49 PASS / 10 SKIP / 1 FAIL，`accepted=true`；`ACC-EXP-1` 52，`ACC-BDY-1` 28，客户端进服不再被断开 |

`ACC-ACT-*` 在联机里按规矩跳过：保守上限的服务端不会点名作弊档动作，`CAT-3` 之后 `setEnabled` 对未知工具抛异常，验收台现在先问 `offered()` 再动开关，没被交付就带原因跳过。

### 1.2 顺带修掉的两处验收台问题

- **`CAT-3`**：`TianshuTools.setEnabled` 对未知工具抛 `IllegalArgumentException` 是新行为（以前忽略）。严格化本身没问题，但验收台/任何在目录到达之前设状态的代码会炸。验收台改成先问 `offered()`；产品侧保留抛异常（它抓出了真实的时序问题）。
- **lab 的 Fabric `presence` 通道**：服务端在 `BEFORE_CONFIGURE` 里、客户端在配置完成时各查一次「对端宣告了没有」，查不到就主动断开——可那时客户端的宣告还没到，于是把**装了验收台的客户端**也踢了（`NET-2` 的 Fabric 同款）。两处断开都去掉了，通道照旧注册；需要另一侧的用例自己报「没有权威侧」并跳过。
- **Fabric 专用服的 mod 架由构建管理**：`runServer` 用的是独立运行目录 `run-server`，而 `prepareDevMods` 只往 `run/mods` 里放东西——`run-server/mods` 里于是躺着六个 11-16 的旧 jar（`tianshu_lab-1.0.0.jar` 之类）。服务端跑的是那一套旧装配、客户端跑的是正在改的代码，联机数字来自两个不同的构建。现在 `runServer` 依赖自己的 `clearDevModsServer` + `prepareDevModsServer`，和客户端一样的规则：每次运行先清空再放。
- **模块级能力终于走通了两端**：此前**没有任何 feature 模块注册过工具**，所以“模块在服务端发布能力、客户端发现并调用”这条路只有 API 没有实机。验收台现在自己注册两个（`tianshu_lab:summon_body` / `release_body`，服务端执行），`ACC-XEN-1` 走的正是这条：客户端调用 → 权威侧生成身体 → 客户端用 `entity.list` 看见 → 释放后消失。两个加载器的单机与联机都是 PASS。
- **`ACC-BDY-8` 改成显式两轮程序**：`=persist` 生成身体并记下 id，保存世界，`=persist-check` 再来看。以前它在每一轮里都跑，标记会活到下一轮、被另一个世界读到，于是把「这具身体在别的世界」误报成「存档没保住」。现在其它模式一律带原因跳过。

## 2. 已确认

- 客户端声明、Fabric 和 NeoForge 的服务端会话使用同一份内建工具清单。ACC-BDY-1 按预期工具 ID 报告缺项，并检查实际随包文档。
- 验收台注册自定义实体 `acceptance_orb`，按双端模组部署。测试服及其所有测试客户端须安装验收台。仅安装舟楫时的进服规则见下方单侧安装实测。
- **档位两轴**：玩家侧 `READ_ONLY` / `CONSERVATIVE` / `FULL` 与实体侧 `SURVIVAL` / `CHEAT` 分开，工具统一声明最低档位，身体查询无需动作档位；
- **两把上限独立**：`autonomy.ceiling` 与 `npc.ceiling` 各是各的端口与配置，启动日志各印一条（`ceiling=full npc.ceiling=SURVIVAL ...`）；
- **开关逐个独立**：`ACC-ACT-5` 一次开一个开关、跑 14×14，每个动作只跟自己的开关走；配置键名与工具 id 的对应（`set_time`→`server.time`、`broadcast`→`server.broadcast`）在 `ServerActionTools` 与 `ServerActionConfig.switchFor` 里逐个核对过，14 个都对得上；
- **`FULL` 档全开可执行**：`ACC-ACT-4` 在 `ceiling=full` + 14 开关全开时 10 个动作真的执行（服务端审计日志逐条 `ok=true`），4 个被自己的参数校验拒；
- 修复前的 `ACC-ALL-1` 扫到 56 个工具，均返回结果信封。其中 48 个空参调用被拒绝，这次扫描只验证调用链。
- **一个进程三个会话**：世界 A → 标题 → 世界 B → 标题 → 回 A 全部在同一进程，世界 B 与回到 A 之后各调一次工具控制台，`tool.console` 都是 `ok=true`（`durationMs` 9 和 0）；`ClientTools` 的一次性注册守卫生效，第二次登录没有注册冲突报错；
- 舟楫的 NeoForge 网络通道均按可选注册。含自定义实体的验收台另按双端模组验证。
- **无舟楫客户端单侧安装**：服务端 `runServer -PfeatureMods=`（`tianshu dev run: … installing the kernel alone`，模组列表里只有 `tianshu_ferry` 与 `tianshu_foundry`），客户端用 `D:\Minecraft\.minecraft\versions\Tianshu`（NeoForge 1.21.1、`mods/` 为空、手搓启动命令进服）。结果：服务端 `VanillaTest logged in … / joined the game`，两端日志里 payload / ERROR / 断连都是 0 条，客户端暂停菜单里没有「天枢控制台」——确实没装舟楫，也确实进得去；
- **身体存档重载**（本轮，显式两轮）：`=persist` 生成身体 `68976828-…` 并把 id 写进 `tianshu-lab/persisted-body.txt`（这一轮 53 PASS / 5 SKIP / 1 FAIL，`ACC-BDY-8` 带原因跳过），用「保存并退回到标题屏幕」把 `region/*.mca` 与 `level.dat` 落盘（12:02:10，`platform.world.logout`），`=persist-check` 重开同一世界：54 PASS / 4 SKIP / 1 FAIL，`ACC-BDY-8` **PASS**：`body 68976828-… came back from the save`。这条只有在身体真的进了存档、且重启后能被列出来时才通过。

## 3. 证据

以下为修复前的验收记录。

- **单机，默认配置**：`52 PASS / 4 SKIP / 1 FAIL`，`accepted=true`；
- **单机，`ceiling=full` + 14 开关全开 + `command_run` 开**：`56 PASS / 1 SKIP / 1 FAIL`（`ACC-ACT-2` 因 teleport 已开而跳过"关"的那一半），`accepted=true`；
- **联机，专用服（同样 full/全开）+ 客户端加入**：`51 PASS / 6 SKIP / 1 FAIL`，`accepted=true`；跳过的 6 条里有 5 条是身体用例（原因见 `NET-1`），第 6 条是 `ACC-ACT-2`；
- **服务端侧证据**：联机那轮的服务端 stderr 有 `write tool minecraft:server.difficulty ... ok=true`、`...server.gamerule ... ok=true`，证明跨进程转发与开关都生效；
- **单测**：api 240、core 191、client 319（1 skip 符号链接）、testkit 4，0 失败；全量 `check` 与 13 个门禁绿；
- **新增用例**：`ACC-ALL-1`（每个工具空参要有信封，detail 里带扫到的 id）、`ACC-ACT-5`（每个动作只跟自己的开关走）。