---
name: player-tools
description: 以玩家所在世界、背包或客户端为对象，查询信息、执行生存操作或使用已开放的管理工具。
metadata:
  share: all
  references: tools
---

# 玩家辅助

背包、已打开的容器和客户端设置从当前客户端读取。合成、搬运和机器操作由服务端使用玩家的位置、物品和权限执行。普通辅助中的挖方块、放方块和使用手持物品由玩家亲自操作。

只查已生成区块用 `world.locate`，需要搜索未生成地形用 `world.survey`。按会话目录检查工具档位和开关。管理动作还需操作员权限。

舟楫自带阅读器 `minecraft:capability.read`，无需单独订阅。省略 `id` 可列出当前可见能力，传技能 ID 读入口正文，传已订阅工具 ID 读调用说明。正文在 `result.text` 中。长技能文档可用 `page` 从第 1 页起读取。

```json
{"id":"minecraft:capability.read","args":{"id":"minecraft:container.transfer"}}
```
