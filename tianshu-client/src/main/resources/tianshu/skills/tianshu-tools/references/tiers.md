# 三档边界

## READ_ONLY

只读，不改变世界与玩家状态：

- `recipe.query`：配方与材料；
- `loot.query`：方块/实体掉落；
- `tag.query`：物品/方块标签；
- `inventory.inspect`：玩家背包；
- `container.inspect`：当前打开的容器；
- `capability.read`：读其他模块注册的能力/技能。
- `client.*`：客户端本地读数（音频、材质包开关）。

## CONSERVATIVE

普通生存玩家手工能做的操作，不需要 op：

- `craft.recipe`：用背包材料合成；
- `block.machine`：熔炉类工作方块投料/燃料/取产物；
- `container.transfer`：容器与背包之间移动物品；
- `block.interact`：按钮、拉杆、门、活板门、栅栏门；
- `block.place` / `block.place.line`：放置一个/一排方块；
- `server.say`：以玩家身份在聊天频道说一句（不是系统公告）。

## FULL

作弊类动作，需要 operator、各自开关与服务端上限；每次使用前确认：

- 传送、天气、时间、给物品、生成实体、广播、经验、游戏模式、状态效果、难度、游戏规则等；
- 具体 id 与参数以 `tianshu:tool.catalog` 为准。

## 不属于工具层

- `body.*`：身体动作（属于有身体的模组，不是舟楫的工具）；
- 服务器六个作弊动作之外的世界写入：没有"通用写世界"入口。