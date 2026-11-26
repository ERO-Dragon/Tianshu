---
name: entity-tools
description: 通过模组申请的 Minecraft 身体读取状态、感知周围环境并驱动该 NPC 行动。
metadata:
  share: all
  references: tools
---

# 实体操作

模组调用动作时，`body` 填自己申请的身体 ID。身体调用自身动作时可省略。用 `entity.list` 查看身体和能力位，用 `entity.scan` 获取附近目标的实体 ID 或坐标。

所有步骤的参数都已确定时可用 `entity.sequence`。中途失败时，先核对已完成的动作，再继续剩余步骤。

舟楫自带阅读器 `minecraft:capability.read`，无需单独订阅。省略 `id` 可列出当前可见能力，传技能 ID 读入口正文，传已订阅工具 ID 读调用说明。正文在 `result.text` 中。长技能文档可用 `page` 从第 1 页起读取。

```json
{"id":"minecraft:capability.read","args":{"id":"minecraft:entity.move"}}
```
