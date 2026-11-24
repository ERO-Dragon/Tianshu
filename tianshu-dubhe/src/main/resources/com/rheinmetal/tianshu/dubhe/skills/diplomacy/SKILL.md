---
name: diplomacy
description: 外交：把一件事交给别的模组办，或按需查进度。教你怎么发现对方的入口、投递只收受理回执、不轮询；细节按需读 references。
---
# 外交

你要让别人办事时用本技能。你没有实体，也不直接构造协议消息——你只做四件事：**发现、看参数、投递、查进度**，都通过 `relay` 工具完成。

## 四步

| 步 | `relay` 的 action | 参数 | 拿到什么 |
|---|---|---|---|
| 1 | `list` | 无 | `targets`（可读材料）与 `taskEntries`（可投递的任务入口） |
| 2 | `describe` | `target`（`namespace:name`） | 该目标的 `kind`、`description`、`attributes` |
| 3 | `submit` | `target`（任务入口）+ `task` | 受理回执 `taskId`；**不等执行完** |
| 4 | `progress` | `target`（对方读工具）+ `taskId` | `RUNNING` / `DONE` / `UNKNOWN` |

## 硬规则

1. **投递只回收执**：拿到 `taskId` 是"对方接了"，不是"办完了"。
2. **不轮询**：只在玩家问起、或对方主动回报时才查。
3. **不编进度**：`UNKNOWN` 就如实说对方没说进展。
4. **不承诺没投出去的事**：只有拿到 `taskId` 才能说"我让人去办了"。
5. **不伪造身份**：用你自己的身份与你被允许的档位；被拒就是被拒。
6. **不驱动身体**：天枢没有实体；需要动手时交给有身体的模组。

## 两个清单别混

- `targets`：可**读**的材料（技能/资源/提示词）——读它，**不能**投任务；
- `taskEntries`：可**投**的任务入口（`kind=connector` 且带 `taskEntry`）——`submit` 只认这个清单。

把"说明书"当投递口，任务会被当文字丢掉。细节见 `references/discovery.md`。

## 按需参考

- 发现两个清单：`references/discovery.md`
- **投递（relay）**：`references/relay.md`
- 查进度与三态：`references/progress.md`
- 权限与边界：`references/permissions.md`
- 失败怎么说：`references/failures.md`
- 一次完整往返：`references/examples.md`

读法：带参数 `{"reference": "references/relay.md"}` 再调用一次本技能；不要一次全读。