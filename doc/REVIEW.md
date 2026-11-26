# 舟楫审查

> 逐条记录验收过程中发现的问题；修掉就删行，历史在 git 里。怎么跑见 [验收](验收.md)，契约见 [智能体能力中心](智能体能力中心.md)。

## 目录

- [1. 待实机复验](#1-待实机复验)
- [2. 已确认](#2-已确认)
- [3. 证据](#3-证据)

## 1. 待实机复验

以下三项等待本轮改动后的实机复验。

| id | 已处理 | 待验证 |
|---|---|---|
| `NET-1` | 旧联机报告先见 9 个本地工具，稍后同轮已见 56 个。目录和工具声明按当前连接合并，联机验收可等待目录就绪。 | 用 `=server` 在两个加载器重跑；确认首个目录用例和实体用例都看到服务端工具。 |
| `INV-1` | 没有本地玩家时返回 `UNAVAILABLE`，有玩家时读取该玩家的非空槽位。 | 旧截图和空背包回复缺少同一时刻的槽位记录，需在切换前后对照游戏界面。 |
| `HUD-1` | 设置界面的模组导航标题按左栏剩余宽度裁切，给修改标记和滚动条留出空间。 | 窄窗口、长模组名下目视复验。 |

### 1.1 本轮实机复验结果

**修好的**：`ENT-1` 已修。单机默认配置下 `ACC-ALL-1` 从 56 涨到 **65** 个工具全部应答，`ACC-BDY-1` 报 28 个实体工具（生存上限下 `entity.command.run` 因 `npc.ceiling=survival` 被目录挡掉，符合设计）。

**没修好的：`NET-1` 反而更明显**。专用服 + 开发客户端那一轮 `accepted=false`：

| 用例 | 现象 |
|---|---|
| `ACC-EXP-1` / `ACC-EXP-5` | 客户端目录只有 **9** 个本地工具（单机是 42 个），`capability.read` 列表只有 507 字符（单机 1592） |
| `ACC-BDY-1` / `ACC-BDY-2` | 仍然以"entity tools require a Ferry server"跳过——`TianshuTools.serverAvailable()` 在联机客户端判为 false |
| `ACC-ACT-1/2/3/4/5` | **新失败**：`IllegalArgumentException: cannot change the state of an unknown tool: minecraft:server.xp`（teleport / setblock / give 同）。14 个服务端动作在客户端连行都没有，`setEnabled` 现在直接抛异常而不是忽略 |
| `ACC-ALL-1` | 只有 **49** 行（单机 65），差的正是那 14 个动作加 2 个 |

也就是说：内建清单把实体孪生补齐了（单机 65），但**联机时服务端目录仍然没有到达客户端**；`setEnabled` 从"忽略未知工具"改成抛异常之后，这个缺口从"静默少几个工具"变成"一轮 5 条用例直接崩"。

`CAT-3`：`TianshuTools.setEnabled` 对未知工具抛 `IllegalArgumentException` 是新行为（以前忽略）。严格化本身没问题，但它的调用方有两个——服务端启动时按开关设状态（工具已注册，安全）和验收台/任何在目录到达之前就设状态的代码（会炸）。建议：抛之前先确认调用方语义，或者给"目录尚未就绪"一个可辨认的拒绝（如 `UNKNOWN_TOOL` 的软失败），别把时序问题变成异常。

**我自己用例的问题已修**：`ACC-BDY-8` 原来把身体 id 写进 `tianshu-lab/persisted-body.txt`，没记世界；联机那轮进的是另一个世界，于是把"这具身体在另一个世界"误报成"存档没保住"。现在标记里带 dimension，维度不同按 SKIP 处理。

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
- **身体存档重载**：`ACC-BDY-8` 两轮验证——第一轮生成身体并把 id 写进 `tianshu-lab/persisted-body.txt`（SKIP，提示去保存），用「保存并退回到标题屏幕」把 `region/*.mca` 与 `level.dat` 落盘，重开同一世界第二轮 PASS：`body 0bee68f0-… came back from the save`。这条只有在身体真的进了存档、且重启后能被列出来时才通过。

## 3. 证据

以下为修复前的验收记录。

- **单机，默认配置**：`52 PASS / 4 SKIP / 1 FAIL`，`accepted=true`；
- **单机，`ceiling=full` + 14 开关全开 + `command_run` 开**：`56 PASS / 1 SKIP / 1 FAIL`（`ACC-ACT-2` 因 teleport 已开而跳过"关"的那一半），`accepted=true`；
- **联机，专用服（同样 full/全开）+ 客户端加入**：`51 PASS / 6 SKIP / 1 FAIL`，`accepted=true`；跳过的 6 条里有 5 条是身体用例（原因见 `NET-1`），第 6 条是 `ACC-ACT-2`；
- **服务端侧证据**：联机那轮的服务端 stderr 有 `write tool minecraft:server.difficulty ... ok=true`、`...server.gamerule ... ok=true`，证明跨进程转发与开关都生效；
- **单测**：api 240、core 191、client 319（1 skip 符号链接）、testkit 4，0 失败；全量 `check` 与 13 个门禁绿；
- **新增用例**：`ACC-ALL-1`（每个工具空参要有信封，detail 里带扫到的 id）、`ACC-ACT-5`（每个动作只跟自己的开关走）。