# Dubhe 搜索（`search`）

> **要点**
> - 只答**描述性知识**：这个物品/方块干什么、这个模组怎么玩、这个原版机制怎么运作。配方归舟楫、版本与依赖本地已知、崩溃不做。
> - 三层源按序降级：**本地手册（Patchouli）→ 中文站 → 英文站**。
> - 不引入任何需要 API key 的通道。
> - 对外只有 `dubhe:search` 一个可读技能，契约见《对外接口》§11，本文只管内部怎么查。

## 1. 边界：不管什么

| 问法 | 归谁 | 为什么 |
|---|---|---|
| 配方 / 合成表 | 舟楫 `tianshu:recipe.query` | 存档里的真实配方，含整合包魔改；网页必然可能错 |
| 支不支持我这版本、依赖什么 | 本地 `ModList` | 启动时已知，联网是绕远路 |
| 报错了 / 崩溃 | 不做 | 客户端日志不在我们手里 |
| 整合包推荐、下载站浏览 | 不做 | 不是游戏内会发生的事 |
| 世界动作 | 不做（A1） | 天枢无实体 |

剩下只有三问：**这是什么、怎么玩、原版机制怎么运作**。

## 2. 两个轴

- **轴一：问什么** → 决定去哪个源。唯一的轴。
- **轴二：怎么取**（API / HTML / jar 资源）→ 源的属性，模型不用管。

## 3. 三层源与降级

| 层 | 源 | 前提 |
|---|---|---|
| L1 本地 | 已装模组的 Patchouli 手册 | 模组用了 Patchouli |
| L2 中文 | MC百科、中文 Wiki、BWIKI | 网络可用 |
| L3 英文 | CurseForge、Modrinth、`minecraft.wiki` | 网络可用 |

- 上层无命中 → 下层；任一源超时/报错/零结果 → 跳过。
- **跨语言降级必须在回答里注明**「来自英文源」，不许伪装成中文源。
- **不做国产判断**：`ModList` 给不出可靠产地；让 L3 查不到自然落到 L2，机制等价且不会误判。

## 4. 站点清单

### 中文

| 站点 | 覆盖 | 取法 | 状态 |
|---|---|---|---|
| MC百科 `mcmod.cn` | 模组词条（中文侧唯一） | HTML + 站内搜索 | robots 允许正文；无官方 API |
| 中文 Wiki `zh.minecraft.wiki` | 原版 | MediaWiki API | 实测可用 |
| BWIKI `wiki.biligame.com/mc` | **只有原版** | MediaWiki API | 实测可用；搜「机械动力」0 条 |

### 英文

| 站点 | 覆盖 | 取法 | 状态 |
|---|---|---|---|
| CurseForge | 作者原话（描述、文件） | `api.cfwidget.com` 公开镜像 | 实测可用；官方 API 需 key，不用 |
| Modrinth | 作者原话（描述、版本） | 官方 API | 实测可用，免 key |
| `minecraft.wiki` | 原版 | MediaWiki API | 实测可用 |

**不收录**：9Minecraft（转载站）、Planet Minecraft（地图/皮肤）、Minecraft Forum（已衰退）、GitHub（开发视角）、B站/YouTube（视频读不了）。

**官方 wiki 不设计成链路上的一环**：CF 的 `links` 实测为空（Create 就是空），Modrinth 的 `wiki_url` 实测常为 null（Botania 就是 null），「从发布站跳到官方 wiki」不可靠。已有 URL 时只作事后补充。

## 5. 工具（4 个）

| 工具 | 覆盖 |
|---|---|
| `mc_wiki(site: en\|zh\|bwiki, action: search\|page, q)` | 三个 MediaWiki 站，同协议合一个工具 |
| `mc_modrinth(action: search\|project, q)` | Modrinth 官方 API |
| `mc_cf(action: search\|project, q)` | CurseForge 公开镜像 |
| `mcmod(action: search\|page, q)` | MC百科 HTML |

- L1 本地手册走内部读取，不占工具名额（见 §6）。
- **`search` 不进 simple 工具表**（A3）：simple 恒为 `{delegate}`，结构上没有这条路。
- 前三个是 JSON、第四个是 HTML，失败模式不同，不合并——CF 镜像挂掉要能单独报，不拖累 Modrinth。

## 6. 本地手册（L1）：Patchouli

数据在**模组自己的 jar**（`assets/<ns>/patchouli_books/<book>/`），不在 Patchouli 的 jar 里——**没装 Patchouli 也能读**，天枢已有读 jar 资源的能力。

两级检索，不整本进模型：

| 步 | 做什么 |
|---|---|
| 索引 | 只取全部条目的 `name` + `category`，启动时扫一遍常驻内存 |
| 匹配 | 玩家问法 → 索引命中 1–3 条 |
| 取正文 | 只读命中条目的 `text` 页 |

解析要点：

- 文本常是**翻译键**（`"text": "botania.page.apothecary0"`），正文在同 jar 的 `lang/*.json`；也支持直写文本，两种都要认。
- 只取 `text` 页；`crafting` / `spotlight` / `image` 跳过。
- 语言优先 `zh_cn`，回落 `en_us`。
- 宏与模板不展开；坏 JSON 跳过并记日志，不拖垮整体。

## 7. 频控与失败

| 项 | 规则 |
|---|---|
| MC百科 | 单轮合计 ≤2 次（搜索 1 + 页面 1）；同域名 60s 冷却；不重试 |
| 其余站 | 单轮 ≤3 次/站；不重试 |
| 同 URL | 进程内缓存，只取一次 |
| 失败 | 降级 + 回答里说明；**三层都无命中就说查不到**，给已知信息并标置信度，不许编 |

## 8. 输出

结论 + 依据（每条带来源站点）+ 不确定处，嵌进报告三字段的 `result`。

## 9. 待测与未定

| # | 事项 | 现状 |
|---|---|---|
| 1 | Patchouli 三个数：条目总数 / 单条目平均字数 / 中文覆盖率 | 未测；决定 L1 是否值得投入 |
| 2 | 官方 wiki 白名单（几十条）是否补、由谁维护 | 未定；等 L1 测完 |
| 3 | 白名单半自动生成脚本 | 未定 |