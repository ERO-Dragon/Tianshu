# 舟楫审查

> 记**对不上的地方**与每轮复核的结论。怎么跑见 [验收](验收.md)，契约见 [智能体能力中心](智能体能力中心.md)。
> 本轮的评审对象是 `3a29ade5`（舟楫）、`c20449ff`（验收台）、`dfa0c903`（适配层）这一组"实体侧单独一把档位轴"的改动。

## 目录

- [1. 这轮改了什么](#1-这轮改了什么)
- [2. 判对了的地方](#2-判对了的地方)
- [3. 待议项](#3-待议项)
- [4. 证据](#4-证据)

## 1. 这轮改了什么

上一版我把 `ToolPolicy` 改成"按档位给默认值"，顺手让 `TianshuTool.policy()` 也走 `requiredAutonomy()`，
于是**实体工具被套上了玩家的尺子**：`BODY_WRITE` 要从玩家三档里挑一个默认值，策略也从那把尺子推。
这轮把两条轴分开，方向是对的：

- 类别拆出 `BODY_READ`；`isBody()` 成为唯一判据；body 类别**不再回答玩家档位**（`defaultAutonomy()` 直接抛错）；
- 新增 `BodyAutonomy`（`SURVIVAL` / `CHEAT`）与 `ServerBodyAutonomyCeiling`，服务端多一把 `npc.ceiling`，
  与玩家侧的 `autonomy.ceiling` 互不相干；
- `EntityActor` 让提供方声明"哪项能力要作弊档"（`requiredAutonomy(capability)`）与"原生移动方式"
  （`nativeMovementModes()`）；`MotionRouter` 在生存档下只放行载体自带的方式；
- 工具用 `requiredBodyAutonomy()` 声明最低档，`entity.command.run` 声明 `CHEAT`；
- 目录行只在 `BODY_WRITE` 上写 `bodyAutonomy`，`BODY_READ` 不写动作档，玩家工具照旧写 `autonomy`。

## 2. 判对了的地方

- **混用在开发期就炸**：`defaultAutonomy()` 对 body 抛错，比"运行期悄悄按玩家档位放行"好得多；
- **两把上限真的分开了**：各是各的端口、各是各的配置项，启动日志两条都印，能对着日志排；
- **三道判定各答各的问题**：工具最低档（`requiredBodyAutonomy`）、归属（controller 阶梯）、能力位（`EntityCapability`），
  再加一层"这个动作在这个身体上算不算作弊"（`nativeMovementModes`）——四个问题四种答案，没有一处互相顶替；
- **服务端推送按 `bodyAutonomy` 过滤**：`serverTools` 走的是 `CapabilityExposure.exposed(..., SERVER, ...)`，
  所以 `npc.ceiling=survival` 时作弊级实体工具不会出现在推送目录里；
- **新增拒绝码是可比的**：`body.autonomy.denied` 走 `BodyFailure` 枚举，带语言包条目，不是借用 `NO_PERMISSION` 的裸文本。

## 3. 待议项

| id | 位置 | 现象 | 建议 |
|---|---|---|---|
| `B-1` | `CapabilityExposure.autonomyAllows` 的 body 分支 `.orElse(true)` | 纯客户端（连专用服）没有 `ServerBodyAutonomyCeiling` 端口，`ClientTools` 又在本地登记了全部工具，于是 `entity.command.run` 会照常出现在会话目录里，调用时才被服务端拒成 `body.autonomy.denied`——正是这个仓库一直在防的"目录说有、调用说没有" | 服务端推送目录时把 `npc.ceiling` 一并带给客户端，客户端收到后按它过滤本地行 |
| `B-2` | 验收台 | 这把新轴**一条实机用例都没有**：`npc.ceiling` 的 `survival` / `cheat` 两个方向、`body.autonomy.denied` 都只在单测里出现过 | 加一条 `ACC-BDY-*`：同一个 NPC 动作在两种上限下的通过/拒绝，两个加载器各跑一次 |
| `B-3` | `ToolCategory.defaultAutonomy()` | 对 body 类别抛 `IllegalStateException`。今天两处调用都先按 `isBody()` 分支，所以不会炸；但"遍历所有工具问它需要哪一档"这种通用代码会踩雷 | 要么给一个"不适用"的显式表达（Optional），要么别让 body 工具在通用接口上暴露玩家档位 |
| `B-4` | `ToolPolicy.forCategory` 与 `TianshuTool.policy()` | body 特例写了两遍（两处各判一次 `isBody()`），将来加类别要同时改两处 | 收敛到一处 |
| `B-5` | `MotionRouter.movementFor` | 请求 `ANY` 时按 `MoveMode.values()` 的枚举顺序取第一个原生方式，实际等于"走优先"；换个枚举顺序行为就变，代码里没写这条规则 | 写明优先级，或按距离/可达性挑 |
| `B-6` | [实体操作](实体操作.md) §3 与 [实体](实体.md) §6 | 同一把归属阶梯两处命名：前者举 `body.controller.mismatch`，后者用 `body.access.denied` | 完整阶梯只写一次，另一处链接 |
| `B-7` | `BodyCommandRunTool`（`CHEAT`）+ `npc.ceiling` 默认 `survival` | 默认配置下"命令审批"整条路不可达（工具连目录都不出现）。文档只说它是作弊工具，没说默认看不见 | 在 [实体操作](实体操作.md) 或配置注释里写明：要用它必须先升 `npc.ceiling` |
| `B-8` | `EntityActor.java` | 文件末尾没有换行 | 补一个 |
| `B-9` | `dfa0c903` 的提交说明 | 写"BodyRegistry 给提供方声明的一项能力标成作弊档"，实际只加了 `nativeMovementModes()`，没有 `requiredAutonomy(capability)` 的覆盖 | 改说明，或补上那项覆盖 |

## 4. 证据

- **提交**：`3a29ade5`、`c20449ff`、`dfa0c903`，三个仓库都干净；
- **构建**：全量 `check` + 13 个 `verify*` + `verifyEveryToolHasADocument`：BUILD SUCCESSFUL；
- **单测**：api 240、core 191、client 319（1 个 skip——`SkillFileProviderTest` 的符号链接用例，本机不允许建链）、
  testkit 4，**0 失败**。新增的 `serverCatalogueAndExecutionRespectIndependentCeilings` 把两把上限的四种组合
  都走了一遍，包括目录过滤与 `body.autonomy.denied`；
- **实机**：默认配置下 `51 PASS / 4 SKIP / 1 FAIL`，`accepted=true`，与改动前一致——说明这条新轴没有碰坏既有行为，
  也说明它还没被**正向**验证过（见 `B-2`）；
- **文档**：`README`、[职责边界](职责边界.md)、[实体操作](实体操作.md)、[智能体能力中心](智能体能力中心.md)、
  [验收](验收.md) 的锚点与跨文件链接全通；两条轴的配置项（`autonomy.ceiling` / `npc.ceiling`）在文档里各自只写一次。