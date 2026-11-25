---
name: minecraft-tools
description: Minecraft 工具调用说明：一次调用输出什么参数、返回怎么读；按只读/保守/全权三档决定先读哪篇参考。模型读完应能直接写出工具调用。
---
# Minecraft 工具（公共）

本技能讲**怎么调用**舟楫提供的 Minecraft 工具；"什么时候该做什么"由各模组自己的业务技能写。

## 1. 一次调用长什么样

- 工具 id 形如 `tianshu:recipe.query`；
- 参数是一个 JSON 对象，字段名必须与工具 schema 一致；
- 返回：成功 `{ "ok": true, "result": { ... } }`，失败 `{ "ok": false, "code": "...", "message": "..." }`；
- **不要用自然语言代替调用**：说"搬好了"之前，必须真的调过 `container.transfer`；
- 参数不确定：先调保留工具 `tianshu:tool.catalog` 取 schema，不要猜字段名；
- 读文档：`tianshu:capability.read`（不带 `id` 列目录；带**工具 id** 读这个工具自己的文档；带技能 id 读技能正文；带 `file` 读参考、长文带 `page`）。

## 2. 先判断档位，再读对应参考

| 玩家要的事 | 档位 | 先读 |
|---|---|---|
| 查配方、掉落、标签、背包、容器内容 | `READ_ONLY` | `references/read-only.md` |
| 合成、熔炉、搬容器、按按钮/开门、放方块、玩家身份说话 | `CONSERVATIVE` | `references/conservative.md` |
| 传送、天气、时间、给物品、生成实体、广播、经验等 | `FULL` | `references/full.md` |

判断完档位只读那一篇；不要一次把三篇都读进来。

## 3. 最小例子

**只读（查配方）**

```
调用 tianshu:recipe.query
参数 {"outputItemId":"minecraft:iron_pickaxe","limit":4}
返回 {"ok":true,"result":{"recipes":[{"id":"...","ingredients":[...]}]}}
```

**保守（搬物品，先看容器）**

```
先 tianshu:container.inspect 参数 {}        → result.open / 槽位
再 tianshu:container.transfer
参数 {"x":120,"y":64,"z":-300,"containerSlot":0,"playerSlot":9,"count":8,"toPlayer":false}
返回 {"ok":true,"result":{"itemId":"minecraft:oak_log","moved":8,"message":"..."}}
```

**写操作**：不可逆或消耗资源时先问一句；全权档每次都要确认。

## 4. 结果与失败

- 先看 `ok`；`ok=false` 时读 `code`/`message`，**对玩家只说游戏原因**，不念错误码；
- 写操作看 `placed` / `moved` / `interacted`；为 false/0 时看 `message` 的原因；
- 失败后先回读状态（背包、容器、交互结果），再决定是否换做法；
- 不要重复执行会写入世界的调用。

## 5. 边界

- `body` 参数只给"有身体"的调用方；天枢没有实体，**不要传**；
- 没有"通用写世界"入口；`server.*` 之外不要期望任意世界写入；
- 档位不够就说做不了，不要绕过权限。