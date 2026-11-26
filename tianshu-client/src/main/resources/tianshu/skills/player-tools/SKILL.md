---
name: player-tools
description: Minecraft 玩家侧工具：以玩家本人身份的读写调用（方块、容器、合成、实体、物品、客户端设置、世界信息、查询、全权动作）。当你要以玩家身份调用某个工具、不确定参数或返回字段时读它——一个工具一份 reference，文件名就是工具 id。
metadata:
  share: all
  references: tools
---
# 玩家侧工具

这一组以**玩家本人**的身份行动：读世界、读方块与容器、合成与放置、查配方与掉落、调客户端设置，
以及需要 operator 的全权动作。

## 与 entity-tools 互斥

通过**身体**（实体化身）行动的那一套是 `tianshu:entity-tools`。两者**互斥**：

- 本组每个工具都以**你（玩家）**的身份执行，参数里没有 `body`；
- 同一个动作在那边有另一个 id：`block.interact` ↔ `entity.interact`、`container.transfer` ↔ `entity.transfer`、
  `craft.recipe` ↔ `entity.craft`……让身体去做，就用那边的 id，别在这里想办法；
- **放方块、拆方块、用手里的物品这里没有**：那是你自己的手在做的事，本组不替你决定。要让**身体**去做，
  用那边的 `entity.place` / `entity.place.line` / `entity.mine` / `entity.item.use`；
- 一个模组要么以玩家身份行事，要么派身体行事，不会同时需要两套；
- 你拿不到的那一套工具不会出现在工具表里——不是坏了，是不适用。

## 一次调用长什么样

- 工具 id 形如 `minecraft:recipe.query`；参数是 JSON 对象，字段名必须与 schema 一致；
- 返回：成功 `{ "ok": true, "result": { ... }, "durationMs": 12 }`，失败 `{ "ok": false, "code": "...", "message": "..." }`；
- **不要用自然语言代替调用**：说"搬好了"之前，必须真的调过 `container.transfer`；
- 参数不确定：读该工具自己的 reference，或调 `minecraft:tool.catalog` 取 schema，不要猜字段名。

## 档位与开关是工具的属性

每个工具自己声明**需要哪一档**（只读 / 保守 / 全权）和**默认是否开启**。档位不是文档的分类，
你实际看到的工具表已经按档位过滤过：

- **只读**：只读事实，不需要确认；
- **保守**：普通生存玩家手工能做的事；不可逆或消耗资源时先问一句；
- **全权**：作弊类动作，需要 operator 与服务端开关，**每次调用前都要确认**。

工具表里每格带 `enabled`：`false` 的工具调了会被拒（`DISABLED`）。**档位不够就说做不了，不要绕过权限。**

## 最小例子

```json
{"id":"minecraft:recipe.query","args":{"outputItemId":"minecraft:iron_pickaxe","limit":4}}
```
```json
{"id":"minecraft:container.inspect","args":{}}
```
```json
{"id":"minecraft:server.time","args":{"time":"night"}}
```

## 结果与失败

- 先看 `ok`；`ok=false` 时读 `code`/`message`，**对玩家只说游戏原因**，不念错误码；
- 写操作看 `placed` / `moved` / `crafted` / `interacted`；为 false 或 0 时读 `message` 的原因；
- 失败后先回读状态（背包、容器、交互结果），再决定是否换做法；不要重复执行会写入世界的调用。

## 参考资料

`references/` 下每个工具一份，文件名就是去掉命名空间的工具 id（`container.transfer.md`）。
订阅本技能即可读这份入口说明。每个工具单独订阅，订阅后可用舟楫统一的 `minecraft:capability.read` 读取该工具的 reference，例如

```json
{"id":"minecraft:capability.read","args":{"id":"minecraft:container.transfer"}}
```