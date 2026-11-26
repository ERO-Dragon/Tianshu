# 舟楫审查

> 记**对不上的地方**：本轮复核查了什么、还差哪几处。怎么跑见 [验收](验收.md)，契约见 [智能体能力中心](智能体能力中心.md)。
> 基线 `e7d4e241` 的 12 项（工作流开关/失败/超时、技能与工具说明的读取边界、内容源卸载与重新注册、会话订阅变更、服务端目录同步）保持已修，本轮未回归。

## 目录

- [1. 本轮结论](#1-本轮结论)
- [2. 待修项](#2-待修项)
- [3. 本轮复验通过](#3-本轮复验通过)
- [4. 交给实机的部分](#4-交给实机的部分)

## 1. 本轮结论

复核确认原列的七处文档问题，现已修正。玩家与实体工具表分别有 35 和 29 个 ID，逐项对应随包附件。平台阅读器另计 1 个，使用说明在两本技能入口。`tool.catalog` 和 `tool.cancel` 属于适配器保留请求。

## 2. 待修项

仓库内本轮文档待修项已清零。开发环境仍有一项配置差异。

| id | 位置 | 现场与影响 |
|---|---|---|
| `ENV-1` | NeoForge 开发运行目录的 `run/config/tianshu_ferry-server.toml` | 本地样本的 `[tools]` 段保留六个旧键，缺少现行的 `command_run` 和 `command_permission_level`。调整命令相关验收配置时，以该版本的 `ServerActionConfig` 及实际启动世界加载的配置为准。 |

## 3. 本轮复验通过

以下为原审查记录的运行结果。此次复核另外检查了源码、文档和本地配置。

- **构建**：全量 `check` BUILD SUCCESSFUL。
- **单测**：api 236、core 191、client 316（1 个 skip——`SkillFileProviderTest` 的符号链接用例，本机不允许建链，`TestAbortedException` 带原因，不是失败）、testkit 4；adapter fabric 71、neoforge 86；features 侧 dubhe 600、echo 347、intake 218、lab 28、presence 97、weaving 373。**全部 0 失败**。
- **门禁**：13 个 `verify*` 全过；`verifyEveryToolHasADocument` 覆盖 65 个工具（35 个玩家工具、29 个实体工具、1 个平台阅读器）。
- **游戏内**：`:tianshu-adapter:1.21.1-neoforge:runGameTestServer` → `All 14 required tests passed`。
- **文档**：原审查标出的技能撰写规范 §3 目录锚点已修复；`Tianshu/doc/` 五处指向 `智能体能力中心.md §7` 的引用路径与章节都有效；文档点到的 API 都在——`CapabilitySubscription.only / atomicFrom / everythingFrom`、`CapabilitySubscriptions.declare / audit`、`AgentSession.describe` 的 `tianshu.agent/1` 与 `maxInFlightPerCaller = 8`。
- **代码抽查**：`CapabilitySources`（注册句柄、卸载可重注册、保留命名空间）、`ToolCatalogPushRegistry`（抖动合并、单线程推送、订阅者异常隔离）、`ContentText`（字节预算裁半个字符、路径必须落在自己目录内）三处新实现没有补丁式分支。

## 4. 交给实机的部分

`验收.md` §3 的 C/D 两层与 §4 的六项证据都还没拿到（原版客户端单侧安装、身体存档重载、两个世界一进程、双端实体验收台、HUD、14 个服务端动作逐个开关）。