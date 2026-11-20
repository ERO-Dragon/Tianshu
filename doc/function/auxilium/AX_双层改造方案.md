# AX 双层改造方案

> 状态：**方案（施工基准，未实施）**。决策已定（§2），阶段与验收见 §6，改名见 §5，进度回填在 §7。
> 本文只谈"改成什么、按什么顺序改"；当前状态以其余六篇现行文档与代码为准。
> 依据：本目录六篇现行文档、`doc/architecture/舟楫待修问题清单.md` 的 `DEC-1`/`DEC-9`/`P0-2`、
> `tianshu-auxilium` 当前代码。

## 1. 目标与判据

### 1.1 产品目标

天枢是一个"**角色 + 专席**"的双层 agent：

- **角色层（simple）**：与玩家对话，扮演一个真人。它唯一可调用的工具是**委托**。
  它不施法、不越权、不念错误码；它承诺"我让人去办"，然后兑现承诺。
- **执行层（专席池）**：若干可配置的 worker，各自带角色提示词、可用工具/能力、预算与回报契约。
  它们查资料、整理记忆、动手或给别的模组派单，最后交回一份**办事报告**。
  **专席集合是开放集**（类似枚举，后续按功能定义新增）：首批只落 `research`、`memory` 与 `fallback`，
  `world` / `message` 等功能定下来再加——不预先占位，也不在代码里按 id 分支。
- **两层的关系**：真人与他托付出去的人。角色层不认识工具，执行层不认识玩家。

### 1.2 完成判据

| # | 判据 |
|---|---|
| 1 | simple 的工具集**恒为** `{delegate}`，由类型/测试保证，不再靠布尔开关维持 |
| 2 | 玩家一句需要外部信息的话（如"暮色森林怎么进"）能被派给 `research` 专席，并在完成后以一句话回报 |
| 3 | 专席执行期间玩家问"办得怎么样"，simple 能如实回答进度，不编造 |
| 4 | 专席失败/被拒/找不到目标时，玩家听到的是人话，不是错误码 |
| 5 | 记忆压缩在后台完成，不占玩家委托的并发与预算，失败静默重试一次 |
| 6 | 四条架构门禁（§4.4）钉住分层，违约即构建失败 |
| 7 | **新增一个专席只加一份配置与资源，不改循环代码**（扩展性判据） |

### 1.3 明确不做

| 不做 | 理由 |
|---|---|
| DAG / 工作流编排 | 当前没有多专席协同的需求；引入只会让成本与失败归因不可预测 |
| 专席套专席 | 违反 A6；步数与成本将不可预测 |
| 用模型做路由 | 多一次往返（语音场景 1–2 秒），且同一句两次路由不同，违反 P6 |
| 给 simple 开工具 | 违反定位；`tools[]` 一旦进主对话，前缀稳定与语音预算同时失守 |
| 自由 persona（玩家自写提示词） | 破坏 P2 字节冻结与语音可读性；只做短档位 |
| 专席目录进 simple 的 system | 加一个专席就改一次前缀，缓存全 miss |

## 2. 已定决策

| 编号 | 决策 | 结论 |
|---|---|---|
| **G1** | 天枢定位 | 双层：simple 扮演真人，专属工具是委托；complex 为专席池 |
| **G2** | 执行层形态 | 不是一个 agent，而是**专席池**：worker = 数据配置（提示词 profile + 能力集 + 预算 + 回报契约 + 取消范围 + 重试策略），加专席不改循环。**集合是开放集**：注册表接受未知 id，读取方不因不认识的专席而失败；首批 `research` / `memory` / `fallback` |
| **G3** | 记忆压缩归属 | 并入执行层，作为 `memory` 专席；后台触发，simple 永不发起；独立车道与预算 |
| **G4** | 路由 | **确定性规则**：意图词 + 专席声明的能力 + 当前可用性；不中落 `fallback` 专席，并在诊断里记"未命中" |
| **G5** | 层间回报 | 结构化「办事报告」（§3.4），仍限长 |
| **G6** | 跨模块指挥 | 走 capability（`CONNECTOR` / `SKILL`）+ 自主度三关；**不复用 IA 仲裁**（IA 是入站） |
| **G7** | simple 改动面 | 只改三处：人设口径、尾部「在办事项」、失败词表（§3.5） |
| **G8** | 包结构 | `com.rheinmetal.tianshu.dubhe.{simple,agent,agent.worker,shared}`；**全局去掉 `function` 包层**（所有功能模块一起掉，不留特例） |
| **G9** | 改名 | 英文标识 `auxilium` / `AX` → `dubhe` / `Dubhe`：天枢就是 Dubhe，而 AX（辅）是开阳的伴星，文不对题。中文显示名仍是"天枢"。开发期尚未发布，**不做旧 id 与旧配置的兼容**，直接断 |

## 3. 目标结构

### 3.1 分层与依赖

```text
simple（角色层）
  AXTurnOrchestrator · 提示词装配 · 输出/语音 · 在办事项视图
        │  唯一出口：委托（意图，不含专席名）
        ▼
agent（执行层）
  dispatcher（规则路由） · 循环 · 工具轮次 · capability 出站 · 票据/账本
        │
        ├─ worker/research   查资料、整理信息
        ├─ worker/memory     压缩、摘要、偏好抽取（后台车道）
        ├─ worker/fallback   未命中意图时的兜底（无副作用，能力可增长）
        └─ （后续新增：世界动作、跨模块派单……功能定下来再落）
shared：记忆存储 · 事件 · 票据账本
```

依赖方向单向：`simple → agent → shared`，反向零引用。`api` / `core` 不变。

### 3.2 专席描述

| 字段 | 含义 | 约束 |
|---|---|---|
| `id` | 稳定标识（首批 `research` / `memory` / `fallback`，其余后加） | 小写，进诊断与路由表 |
| `intentHints` | 路由用的意图词（中英各一组） | **只用于 dispatcher，不进任何提示词** |
| `promptProfile` | 该专席的 system 文本（自包含） | 不复用主链前缀（沿用现约定） |
| `tools` / `capabilities` | 能力集 | 数据声明，循环里不分支 |
| `budget` | 步数 + 挂钟 | 闭集预设，调用方不得覆盖（A8） |
| `reportSchema` | 回报字段与长度上限 | 归一化后才交给 simple |
| `cancelScope` / `retry` | 取消范围与重试策略 | `memory` 静默重试；玩家委托不重试 |
| `lane` | `player` / `background` | `memory` 恒为后台 |

**开放集约定**：注册表接受未知专席 id，不因不认识而失败；循环里没有"专席 → 行为"的分支，行为全部来自配置。
新增一个专席的完整动作 = 一份配置 + 一段提示词资源 + 一条意图词，循环与调度不改。`fallback` 不带业务语义，
它只是"没有更合适的专席"时的落点，因此它的能力集可以随功能增长，但不能反过来成为 `research` 的别名
（诊断要能区分"查资料"和"没命中"）。

### 3.3 路由（确定性）

```text
委托意图 ──► 1) 意图词命中 intentHints？   ── 命中且唯一 → 指派
          ├─► 2) 需要的能力当前可用？       ── 不可用     → 该专席降级/拒绝
          ├─► 3) 多候选且无法区分           ── research
          └─► 4) 全部未命中                 ── `fallback` 专席（诊断记"未命中"）
```

规则表是**纯数据 + 稳定排序**：同一意图在任意版本、任意次运行都指派同一专席（P6）。

### 3.4 办事报告（层间契约）

simple 只认这一份结构，不认识专席差异：

| 字段 | 说明 | 上限 |
|---|---|---|
| `outcome` | `DONE` / `PARTIAL` / `REFUSED` / `FAILED` / `CANCELLED` | 枚举 |
| `facts` | 做成了什么（可核对事实） | 至多 3 条，每条 1 句 |
| `blocked` | 没做成什么 + 原因（可行动） | 至多 1 条 |
| `needsDecision` | 是否需要玩家再决定（及一句话问题） | 可选 1 条 |
| `elapsed` | 耗时（供 simple 措辞："查了一会儿"） | 毫秒 |
| `refs` | 来源（检索结果 / 模块 id），**不念给玩家** | 至多 3 条 |

`facts + blocked` 的总字数沿用现有 A2 口径（结论至多三句）。

### 3.5 simple 侧的三处改动

| # | 改动 | 现状 | 目标 |
|---|---|---|---|
| 1 | 人设口径 | "涉及游戏动作只给建议，声称能执行会误导玩家" | 允许承诺-兑现："我让人去办"，同时保留"不编造、不承诺做不到的事" |
| 2 | 尾部块 | `ax_system / memory_summary / game_context / player_memory / recent_dialogue / current_input` | 增加 `pending_work`（在办事项，每轮重建、不进历史、不进压缩） |
| 3 | 失败词表 | 只有 API 层分类（限流 / 超时 / 网络 / 内容拒绝…） | 增加**办事失败**：工具被拒、权限不足、目标不存在、被服务端上限拦下 |

`pending_work` 的段序固定在 `current_input` 之前（易变内容仍只在尾部）。

### 3.6 并发与成本

| 规则 | 值 | 理由 |
|---|---|---|
| 单会话并发专席 | ≤ 2 | 成本可控 |
| 会改世界状态的专席（将来如 `world`） | 全局同时 ≤ 1 | 世界只有一个写者 |
| `memory` 专席 | 不占上述名额（后台车道） | 没人在等 |
| 同一会话的重复委托 | 沿用 5s 回放窗（A7） | 避免误识别连环派单 |
| 每次委托 | 只派 1 个专席，不嵌套（A6） | 步数可预测 |
| 成本闸 | 每会话每分钟派单次数上限（值见 §8） | 语音噪音至少触发一次 |

### 3.7 工具轮次的迁移

现状：`AXToolTurn` / `AXToolRoundHandler` 是"主对话带工具"的实现，但入口
`AXRunPolicy.simple().toolsAllowed()` 恒为 `false`，因此**从未执行过**；`core/delegate` 里零工具引用。

目标：

1. 两个类整体迁进 `agent` 包，成为专席的工具轮次实现（非流式 + 坏参数一次修复，语义不变）。
2. simple 路径删掉布尔闸门与该分支；simple 的 `tools` 恒为 `{delegate}`。
3. `ToolCaller` 身份按 `DEC-1` 收口：新增 `agentActingForPlayer(moduleId, operator, tier)`，
   替代 `player(...)` 在"代玩家行动"处的两种语义混用；档位从
   `config/Tianshu/module/<moduleId>.properties` 读取，并在天枢设置页加一行（`P0-2` 天枢侧据此清零）。

## 4. 结构与门禁

### 4.1 包划分

| 包 | 内容 | 不得引用 |
|---|---|---|
| `dubhe/simple/` | 轮次编排、提示词装配、输出、语音、在办事项 | `agent` 的工具/能力类型 |
| `dubhe/agent/` | 循环、调度、工具轮次、capability 出站、票据账本 | `simple` 的输出/语音 |
| `dubhe/agent/worker/` | 专席配置与提示词资源（首批 `research` / `memory` / `fallback`，可增） | 同 `agent` |
| `dubhe/shared/` | 记忆存储、事件、票据 | 两层的行为类型 |

### 4.2 迁移映射（现包 → 新包）

| 现包 | 去向 |
|---|---|
| `core/turn`、`core/prompt`、`core/output`、`speech`、`input`、`activity` | `simple/` |
| `core/agent`、`core/delegate`、`core/tool`、`core/llm`、`core/context` | `agent/` |
| `core/maintenance`、`module/memory/*`、`module/recentdialogue` | 装配视图留 `simple/`；存储与整理进 `shared/` + `agent/worker/memory` |
| `event`、`producer`、`scope`、`storage`、`failure`、`contract`、`protocol` | `shared/`（`settings` 留顶层） |
| `module/system`、`module/currentinput`、`module/gamecontext` | `simple/`（提示词贡献者） |

### 4.3 同步的文档

| 文档 | 现状 | 改法与时机 |
|---|---|---|
| 本文 | — | P0 |
| `AX_文档索引` | 未列本文 | P0 |
| `AX_事件系统` | "41 条内置映射 / §4.3 内置表（41 条）" | **已改**：40 条，删掉已全仓删除的 `respawn` 行，名单分布同步为 5 / 14 / 21 |
| `AX_文档索引` | "44 条内置表" | **已改**：40 条 |
| `AX_Agent运行时` | A3"主对话无工具、历史无工具消息" | **P1b**（与代码同批）：重述为"simple 的工具集恒为 `{delegate}`"，并补专席模型与办事报告 |
| `AX_Agent运行时` | §4 两套预设（simple / complex） | **P1b**：改为"角色层一套 + 专席各自预算" |
| `AX_运行架构` | 只描述单层 | **P1b**：补 simple / agent 分层与依赖方向 |

### 4.4 架构门禁

1. `simple` 包不得 import `agent` 的工具/能力类型（`TianshuTool`、`ToolCaller`、`CapabilityCatalog`）。
2. `agent` 包不得 import `simple` 的输出/语音类型（`DubheChatOutput`、`DubheSpeechSink`）。
3. simple 的可用工具集恒为 `{delegate}`（行为断言，不只是类型断言）。
4. 专席目录不得出现在 simple 的 system 资源里（文本断言：`general_dubhe.*.json` 不含 worker id）。

### 4.5 已知的文档/代码漂移（本次不顺手改，先记账）

| # | 现象 | 处理 |
|---|---|---|
| 1 | `biome_change` / `chat_mentioned` / `chat_message` 有播报文案，但档位表里没有对应行（走默认档） | 在 P0 与 P5 之间决定：补档位行，还是删文案 |
| 2 | `altitude_shift` / `structure_enter` / `time_of_day` / `underwater` / `weather_change` 有档位行，但没有播报文案 | 同上；若它们是"只进历史不出声"，应在 §4 表格里写明，而不是留空 |
| 3 | `AX_事件系统` 的"41 / 44 条"与实际 40 条 | 已改（P0） |

## 5. 改名（Dubhe）

### 5.1 为什么要改

**天枢 = Dubhe**（大熊座 α）。原来的 `AX` / `auxilium` 取的是"辅"（Alcor，开阳的伴星），是另一颗星。
中文显示名保持"天枢"不变，改的只是内部标识——否则代码里一直留着一个借错名字。

### 5.2 映射表

| 类别 | 旧 | 新 |
|---|---|---|
| Gradle 模块 | `:tianshu-auxilium` | `:tianshu-dubhe` |
| 目录 | `Tianshu-features/tianshu-auxilium` | `Tianshu-features/tianshu-dubhe` |
| Java 包 | `com.rheinmetal.tianshu.function.auxilium` | `com.rheinmetal.tianshu.dubhe` |
| 包层（全局） | `com.rheinmetal.tianshu.function.*`（intake / echo / weaving / presence / lab 一起） | `com.rheinmetal.tianshu.*` |
| 类名前缀 | `AX*`（290 个文件） | `Dubhe*` |
| 模块 id | `module.ax` | `module.dubhe` |
| 模块配置 | `config/Tianshu/module/ax.properties` | `dubhe.properties` |
| 模块存储 | `ax/cache` 及其它 `ax/` 子树 | `dubhe/` 同名子树 |
| 公开能力 id | `AX.DIALOGUE_INPUT` / `AX.CONTEXT_INPUT` / `AX.EVENT_INPUT` | `DUBHE.DIALOGUE_INPUT` / `DUBHE.CONTEXT_INPUT` / `DUBHE.EVENT_INPUT` |
| 语言键 | `tianshu.gui.ax.*`、`tianshu.runtime.module.ax.*`、`ax.runtime.delegate.*` | 同上把 `ax` 换 `dubhe` |
| 资源文件 | `ax_prompt_texts.json`、`ax_memory_tasks.json`、`ax_event_tiers.json`、`general_ax.*.json` 等 | `dubhe_*` / `general_dubhe.*` |
| 文档 | `AX_*.md`（8 篇，含本文） | `Dubhe_*.md` |
| 中文显示名 | 天枢 | **不变** |

### 5.3 必须同批改的接线点

漏一个就构建或测试红，所以按清单走而不是靠记忆：

| # | 位置 | 改什么 |
|---|---|---|
| 1 | `Tianshu-features/settings.gradle` | include 与工程名 |
| 2 | 各功能模块 `build.gradle` | `project(':tianshu-auxilium')` 依赖行 |
| 3 | `Tianshu-adapter/build.gradle` | `featureModules` 列表、依赖方向表、`/com\.rheinmetal\.tianshu\.function\.(intake\|echo\|…)/` 边界正则、`packageRoot` 拼接 |
| 4 | `Tianshu-adapter/…/1.21.1-neoforge/build.gradle` | `runFeatureNames`、`devFeatureMods` 分支、sourceSet 接线 |
| 5 | `tianshu-testkit`、`tianshu-lab` | 模块依赖与 `TestLlmSupport` 等引用 |
| 6 | 测试里的路径断言 | `WorkspacePaths.require("tianshu-auxilium/src/main/…")` 一类 |
| 7 | `ModuleStatusLanguageTest` | 期望值里的 `module.ax` / `tianshu-auxilium` |
| 8 | 三份 `EXTERNAL_INTEGRATION_GUIDE.md` | 能力 id 与模块名 |
| 9 | `zh_cn.json` / `en_us.json` | 语言键同步（缺一个就是界面上裸 key） |
| 10 | 文档正文 | `AX_文档索引`、`模块架构`、`未完成事项`、`舟楫待修问题清单` 里的模块名与文件名 |

### 5.4 执行顺序（P1a）

每一步单独构建，绿了再下一步；最后用"旧名 grep 应为 0"收口。

1. **包层**：`tianshu.function.` → `tianshu.`（所有模块，含目录移动、import、正则、gradle）。
2. **模块与目录**：`tianshu-auxilium` → `tianshu-dubhe`。
3. **类前缀**：`AX*` → `Dubhe*`（290 文件 + 29 个模块外文件里的 54 处引用）。
4. **id 与持久化**：模块 id、配置文件名、存储子树、语言键、资源文件名、能力 id。
5. **文档**：8 篇改名与正文替换，索引同步。

### 5.5 验收

| 项 | 判据 |
|---|---|
| 构建 | 全仓 `build` 绿 |
| 测试 | 全量单测绿；模块边界与路径断言全过 |
| 残留 | `auxilium` / `Auxilium` / `\bAX[A-Z]` / `function\.`（包层）在源码与资源中为 0 |
| 行为 | 一轮对话的请求哈希与改名一致（改名不得改变行为） |
| 玩家面 | 设置页、日志、播报文案正常显示（语言键无裸 key） |

> 兼容：开发期未发布，**不做**旧能力 id 双注册、旧 `ax.properties` / `ax/` 存档迁移。
> 已安装的开发环境需要手动删掉旧配置目录，这一点写进提交说明。

## 6. 阶段计划

每阶段独立可交付、可回退；行为不变的结构先行，能力后行。

### P0 文档与决策

1. ~~本文落盘并在 `AX_文档索引` 中登记~~（已完成）。
2. ~~`AX_事件系统` 与 `AX_文档索引` 的 41 / 44 → 40~~（已完成；顺带删掉已全仓删除的 `respawn` 行）。
3. §8 的待定值确认（不阻塞 P1a）。
- **验收**：`doc/function/auxilium` 内不再有与代码矛盾的计数；索引能到达本文。

### P1a 改名（行为零变化）

按 §5.4 的顺序做五步，每步单独构建；`AXRunPolicy` 等类名在这一步就换掉。

- **验收**：§5.5 全表（构建、测试、残留为 0、请求哈希一致、界面无裸 key）。

### P1b 结构拆分（行为零变化）

1. 包按 §4.1 / §4.2 迁移；`core/tool` 两个类迁入 `agent`。
2. 删除 `AXRunPolicy.toolsAllowed` 布尔与 simple 的工具分支；simple 路径恒无工具。
3. `Dubhe_Agent运行时` 与 `Dubhe_运行架构` 同步（§4.3）；四条门禁落地。
- **验收**：`build` 与全部单测绿；一轮对话的请求与 P1a 结束时**请求哈希一致**。

### P2 执行器与首批专席（research / fallback）

1. 专席描述数据化：`worker/` 目录 + 注册表（纯数据、稳定排序、**接受未知 id**）。
2. dispatcher：规则路由 + 未命中记录 + 成本闸。
3. `AXDelegateService` 从"单步自包含请求"改为真正的循环（工具轮次来自 `agent`，无进展检测生效）。
4. 办事报告替换现有"结论 + 来源"。
5. 接 `research` 与 `fallback`（两者都无副作用）。
- **验收**：委托可多步；坏参数能一次修复；失败/超时/取消分别归因；票据恰好一条终态（A4）；
  路由稳定性用例（同句十次同一专席）；**加一个探针专席只改配置即可被路由到**（扩展性判据 7）。

### P3 自主度收口与首个会动手的专席（功能待定）

先做 `P0-2` 天枢侧的收口（与具体专席无关），再接**一个**会动手的专席——它的功能（世界动作还是跨模块派单）在
P2 结束后定，本节只约束它必须守的规则。

1. `ToolCaller.agentActingForPlayer(moduleId, operator, tier)`；天枢档位配置 + 设置页一行。
2. 该专席的工具面**按玩家能理解的范围**挑（首批 8–10 个，不一次铺 31 个），其余留到有真实需求再加。
3. 会改世界状态的专席遵守单写者规则；工具被拒 / 权限不足映射到"办事失败"词表。
4. 补用例："天枢 READ_ONLY 时代玩家行动仍被拒"（`P0-2` 收口条件）。
- **验收**：档位调成只读后写动作被拒且说人话；服务端上限拦截报出上限值；游戏内完成一次"说一句 → 办成 → 一句话回报"。

### P4 跨模块派单（功能待定）

前提是 P3 选定的功能里包含"让别的模组做事"；否则本节顺延到有真实需求时。

1. capability 出站调用（`CONNECTOR` / `SKILL`）+ 三关门控；**不使用 IA**。
2. 能指挥谁：以 capability 描述为准，参数 schema 由提供方声明。
3. 被指挥模块的产出并入办事报告（来源里带模块 id）。
4. 长任务先按超时失败如实上报；派单-回调机制留到 §8。
- **验收**：对验收台注册的探针模块完成一次"派单 → 报告"；被拒时玩家听到人话。

### P5 memory 并入与体验收口

1. 压缩 / 摘要 / 偏好抽取迁入 `memory` 专席，后台车道独立预算与并发。
2. `pending_work` 尾部块：在办事项每轮重建，进度可回答。
3. 播报过期规则：超过有效期只登记不出声（事件与委托结论各自定义有效期）。
4. 语气短档位（三档）；不做自由 persona。
- **验收**：压缩失败静默重试一次且不打断对话；40 秒等待中问进度能得到真话；过期结论不出声但留痕。

## 7. 进度

| 阶段 | 状态 | 记录 |
|---|---|---|
| P0 | 已完成（文档部分） | 本文 + 索引登记 + 事件计数与 `respawn` 行 |
| P1a 改名 | 未开始 | 计划见 §5 |
| P1b–P5 | 未开始 | — |

## 8. 待定项

| # | 待定 | 影响 | 建议 |
|---|---|---|---|
| 1 | 每会话每分钟派单上限 | 语音误触发的成本护栏 | 先 4 次/分钟，实机后调 |
| 2 | 长任务是否需要"派单-回调" | 跨模块专席能否做走位 / 搬运类指令 | 先不做，按超时失败上报 |
| 3 | **专席成员与各自的功能** | 路由表、能力集、预算 | 首批只 `research` / `memory` / `fallback`；其余（世界动作、跨模块派单……）等功能定义后再加，不预设 |
| 4 | 专席扩展策略（对外） | 第三方模组能否注册自己的专席 | 先只开放内部；注册表本身按开放集实现 |
| 5 | 子包名最终定稿（`simple` / `agent` / `shared`） | 改名成本 | 与 P1b 一起定，改一次到位 |
| 6 | 工具保留面（31 → 8–10） | 玩家能理解的边界 | 与首个会动手的专席一起定（P3） |
| 7 | §4.5 的两处事件表缺口 | 播报是否完整 | P0 之后决定 |

## 9. 风险与回退

| 风险 | 触发信号 | 回退 |
|---|---|---|
| 拆分引入行为变化 | P1 的请求哈希比对不一致 | 该阶段整体回退（不进入 P2） |
| 路由误判 | 诊断里 `fallback` 占比过高 | 先调 `intentHints`，不改结构 |
| 语音误识别触发写动作 | 成本闸频繁触发 | 提高闸门 / 会动手的专席默认关 |
| 并发抢世界 | 实机出现同一方块两次操作 | 世界类专席单写者强制为 1 |
| 延迟体感差 | 委派比例高于 30% | 收紧人设"能自己答就别派" |
| 文档再次漂移 | 计数 / A3 / 专席表与代码不符 | 门禁 4 + 阶段验收包含文档比对 |