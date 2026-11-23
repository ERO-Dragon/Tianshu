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

## 3. 入口内容要求（否则模型读完不会用）

技能的 `SKILL.md` 必须让模型**读完就能写出一次调用**，至少包含：

1. **调用格式**：工具 id + 参数对象，成功/失败的返回形状；
2. **决策表**：玩家这句话 → 先调哪个工具 → 看返回的哪个字段；
3. **至少一个完整示例**：真实参数 JSON + 返回字段 + 该说什么；
4. **失败与确认**：写操作何时先确认、失败怎么说；
5. **按需参考**：哪类细节去 `references/` 的哪一篇。**references 按稳定轴分**：平台工具按档位（read-only / conservative / full），不要按工具逐个拆。

只写"有哪些工具/有哪些档位"不够——那是目录，不是可执行的说明。

## 4. 渐进式披露（三级）

| 级 | 内容 | 何时加载 | 代码 |
|---|---|---|---|
| 1 | `name` + `description` | 目录常驻（约百 token） | `DubheSkillDefinitionLoader.loadMetadata(dir)` |
| 2 | `SKILL.md` 正文 | 模型决定激活该技能时 | `DubheSkillDefinitionLoader.load(dir).body()` |
| 3 | `references/*.md` | 需要具体细节时按相对路径读 | `definition.reference("references/xxx.md")` |

平台实现是 `SkillFileProvider`（共享层 `tianshu-api`）：目录只收元数据，正文与 reference 都按需读；天枢不再有第二份解析器。

## 5. 与内部技能抽象的区别

| | 技能包（本规范） | `DubheSkillSpec`（运行时） |
|---|---|---|
| 形态 | 文件目录 | Java 记录 |
| 面向 | 写技能的人/模型 | 代理目录与执行器 |
| 内容 | 说明 + references | id / schema / capabilities / budget / reportSchema |
| 执行 | 由代理按说明调用工具 | `DubheSkillExecutor` |

两者不是替代关系：技能包定义"怎么做"，`DubheSkillSpec` 定义"代理怎么发现、预算多少、报告要什么"。`DubheSkillPackageAdapter` 把包接成技能：目录只给元数据，调用才返回正文，带 `reference` 参数再读参考。模块启动时按 `skills/index.json` 注册内置包；`work` 已按此接入。

## 6. 计划中的技能（内容另定）

| 技能 | 状态 | 说明 |
|---|---|---|
| `search` | 对外已发布 SKILL（内容待路由表细化） | 联网技能族入口 |
| `work` | **已建**（SKILL.md + 1 篇 reference） | 天枢的工作策略：顺序与边界；工具清单与三档细节引用平台技能 `tianshu:minecraft-tools`，不重复 |
| `memory` | 待建 | 记忆检索与压缩（当前压缩是内部维护服务） |
| `diplomacy` | 已实现（代码形态，待迁包） | 对外投递与按需查询；`relay` 是它旗下的一类 reference |

## 7. 与平台实现的合并（已完成）

`SkillFileProvider`、`FrontMatter`、`TextPages` 已移到共享层 `tianshu-api`（`com.rheinmetal.tianshu.api.capability.skill`），`tianshu-client` 与天枢共用同一份实现。天枢删除了自己的 `DubheSkillFrontmatter` / `DubheSkillDefinitionLoader`，只保留：`skills/index.json` 清单、资源读取、以及 `DubheSkillPackageAdapter`（把包接成代理技能）。

## 8. 共享技能：平台注册、模组运行时读取

三档与工具清单属于**平台公共知识**：舟楫把它注册成 SKILL（`tianshu:minecraft-tools`），任何模组都能读。
模组不保存副本，运行时通过保留工具 `tianshu:capability.read` 去舟楫取：

- 列可用技能：不带 `id`；
- 读正文：带 `id`；
- 读参考：带 `id` + `file`（如 `references/read-only.md`）；长文用 `page`。

天枢把它包成内部技能 `read-shared-skill`；`work` 技能第 0 步先读 `tianshu:minecraft-tools`，再按其中的三档与工具清单操作。平台改了说明，下一次读取即生效。

## 9. 验收

- 7 个加载器用例：元数据/正文/references、无 references 的元数据读取、只扫一层、路径穿越拒绝、frontmatter 非法规避、名字必须等于目录、文本形态校验；
- 新增技能包不改变代理循环代码（目录扫描 + 注册表），这是 G7 的判据。