# Dubhe 技能包规范（SKILL.md + references）

> 本文件定义天枢技能的**文件形态**。代码在 `agent/skill/definition`，运行时抽象在 `DubheSkillSpec` / `DubheSkillRegistry`。
> 遵循 [Agent Skills 规范](https://agentskills.io/specification.md) 的目录与渐进式披露模型。

## 1. 目录形状

```
skill-name/
├── SKILL.md          # 必需：frontmatter + 正文
└── references/       # 可选：按需读取的参考文档（一层 .md）
```

- 技能名 = 目录名（必须一致）；
- 正文只写"什么时候用、怎么一步步做、边界与反例"；
- 细节（源的用法、字段表、长示例）放 `references/`，主文件保持短。

## 2. SKILL.md

```markdown
---
name: search
description: 联网技能族入口：按问题类型去对应的源检索。遇到模组问题去 mcmod；功能需求去 mcmod/CF/Modrinth；游戏机制去 wiki。
allowed-tools: web.search web.fetch
metadata:
  owner: dubhe
  version: "1"
---
正文：路由表、步骤、边界。
更多细节见 references/routing.md。
```

### frontmatter 字段

| 字段 | 必需 | 规则 |
|---|---|---|
| `name` | 是 | 小写字母/数字/连字符，1–64，不能以连字符开头/结尾、不能连续连字符；必须等于目录名 |
| `description` | 是 | 1–1024，写"做什么 + 什么时候用" |
| `license` | 否 | 许可证名或文件 |
| `compatibility` | 否 | 环境要求 |
| `allowed-tools` | 否 | 空格分隔的预允许工具 |
| `metadata` | 否 | 一层 key: value |

**解析是严格子集**：只支持上述标量、`metadata` 下的一层缩进；未知顶层键、缺失必需字段、名字不合规都会在加载时报错。不支持多行折叠/锚点/列表。

## 3. 渐进式披露（三级）

| 级 | 内容 | 何时加载 | 代码 |
|---|---|---|---|
| 1 | `name` + `description` | 目录常驻（约百 token） | `DubheSkillDefinitionLoader.loadMetadata(dir)` |
| 2 | `SKILL.md` 正文 | 模型决定激活该技能时 | `DubheSkillDefinitionLoader.load(dir).body()` |
| 3 | `references/*.md` | 需要具体细节时按相对路径读 | `definition.reference("references/xxx.md")` |

`loadMetadata` 不读 references，也不要求它存在；`reference` 拒绝 `..` 与绝对路径；references 只扫一层，不递归。

## 4. 与内部技能抽象的区别

| | 技能包（本规范） | `DubheSkillSpec`（运行时） |
|---|---|---|
| 形态 | 文件目录 | Java 记录 |
| 面向 | 写技能的人/模型 | 代理目录与执行器 |
| 内容 | 说明 + references | id / schema / capabilities / budget / reportSchema |
| 执行 | 由代理按说明调用工具 | `DubheSkillExecutor` |

两者不是替代关系：技能包定义"怎么做"，`DubheSkillSpec` 定义"代理怎么发现、预算多少、报告要什么"。`DubheSkillPackageAdapter` 把包接成技能：目录只给元数据，调用才返回正文，带 `reference` 参数再读参考。模块启动时按 `skills/index.json` 注册内置包；`work` 已按此接入。

## 5. 计划中的技能（内容另定）

| 技能 | 状态 | 说明 |
|---|---|---|
| `search` | 对外已发布 SKILL（内容待路由表细化） | 联网技能族入口 |
| `work` | **已建**（SKILL.md + 1 篇 reference） | 天枢的工作策略：顺序与边界；工具清单与三档细节引用平台技能 `tianshu:tianshu-tools`，不重复 |
| `memory` | 待建 | 记忆检索与压缩（当前压缩是内部维护服务） |
| `diplomacy` | 已实现（代码形态，待迁包） | 对外投递与按需查询；`relay` 是它旗下的一类 reference |

## 6. 与平台实现的重复（待清理）\n\n舟楫已经在 `tianshu-client` 里实现了同一份规范（`SkillFileProvider`：目录/classpath 扫描、`references` 按需读、注册为 SKILL）。本目录下的 `DubheSkillDefinitionLoader` 与它功能重叠。正确的收口是把 `SkillFileProvider`（及其 `FrontMatter`/`TextPages`）移到 `tianshu-api` 或 `tianshu-core` 共享层，天枢改为复用，删除本目录的重复加载器。\n\n## 7. 验收

- 7 个加载器用例：元数据/正文/references、无 references 的元数据读取、只扫一层、路径穿越拒绝、frontmatter 非法规避、名字必须等于目录、文本形态校验；
- 新增技能包不改变代理循环代码（目录扫描 + 注册表），这是 G7 的判据。