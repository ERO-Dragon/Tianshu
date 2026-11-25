---
name: minecraft-tools
description: Minecraft 工具调用说明：一次调用长什么样、返回怎么读、失败之后怎么判断；档位与开关是工具表上的属性，不是文档的分类。模型读完应能直接写出工具调用。
---
# Minecraft 工具（公共）

本技能讲**怎么调用**舟楫提供的 Minecraft 工具；"什么时候该做什么"由各模组自己的业务技能写，
单个工具自己的用法见 `tianshu:body-tools` 的 `references/<工具名>.md`。

## 1. 一次调用长什么样

- 工具 id 形如 `tianshu:recipe.query`；
- 参数是一个 JSON 对象，字段名必须与工具 schema 一致；
- 返回：成功 `{ "ok": true, "result": { ... }, "durationMs": 12 }`，失败 `{ "ok": false, "code": "...", "message": "..." }`；
- **不要用自然语言代替调用**：说"搬好了"之前，必须真的调过 `container.transfer`；
- 参数不确定：读该工具自己的文档（`capability.read` 带工具 id），或调 `tianshu:tool.catalog` 取 schema，不要猜字段名。

## 2. 档位是工具的属性，不是文档的分类

每个工具自己声明两件事：**需要哪一档**（只读 / 保守 / 全权）和**默认是否开启**。
这不是"三篇文档分三组"——以前那样分过，结果是文档和工具各说各的。

你实际能看到的工具表已经按档位过滤过：

- **只读**：只读事实，不需要确认；
- **保守**：普通生存玩家手工能做的事（合成、搬物品、放方块、以玩家身份说话）；不可逆或消耗资源时先问一句；
- **全权**：作弊类动作（传送、时间、天气、给物品、生成、广播、经验……），需要 operator 与服务端开关，**每次调用前都要确认**。

下面这句是硬规矩：**档位不够就说做不了，不要绕过权限。** 工具不在表里就是不能用，重试同一个 id 不会让它出现。

工具表里每格带 `enabled`：`enabled=false` 的工具调了会被拒（`DISABLED`）。服务端开关状态以工具表为准，不要按记忆判断。

## 3. 最小例子

**只读（查配方）**

```json
{"id":"tianshu:recipe.query","args":{"outputItemId":"minecraft:iron_pickaxe","limit":4}}
```

**保守（搬物品，先看容器）**

```json
{"id":"tianshu:container.inspect","args":{}}
```
```json
{"id":"tianshu:container.transfer","args":{"x":120,"y":64,"z":-300,"containerSlot":0,"playerSlot":9,"count":8,"toPlayer":false}}
```

**全权**：先确认再调用，`{"id":"tianshu:server.time","args":{"time":"night"}}`。

## 4. 结果与失败

- 先看 `ok`；`ok=false` 时读 `code`/`message`，**对玩家只说游戏原因**，不念错误码；
- 写操作看 `placed` / `moved` / `crafted` / `interacted`；为 false 或 0 时读 `message` 的原因；
- 失败后先回读状态（背包、容器、交互结果），再决定是否换做法；
- 不要重复执行会写入世界的调用。

## 5. 边界

- `body` 参数只给"有身体"的调用方；没有身体就不要传，传了会被拒；
- 没有"通用写世界"入口；`server.*` 之外不要期望任意世界写入；
- 每次调用只做一件事：组合多个动作的是工作流，不是把参数塞进一个调用。