# 工作工具清单

工具 id 形如 `tianshu:<name>`；参数与返回值以 `tianshu:tool.catalog` 的 schema 为准，不要凭记忆猜。

## 只读

| 工具 | 用途 |
|---|---|
| `x` | 占位，见下 |
| `recipe.query` | 查某个物品/配方的合成方式与所需材料 |
| `loot.query` | 查方块/实体的掉落表 |
| `tag.query` | 查物品/方块标签（例如"哪些木头算木板"） |
| `inventory.inspect` | 看玩家背包的槽位与物品 |
| `container.inspect` | 看当前打开的容器布局与内容 |
| `capability.read` | 读其他模块注册的能力及描述 |

## 保守

| 工具 | 用途 |
|---|---|
| `craft.recipe` | 用玩家背包的原料合成 |
| `block.machine` | 操作熔炉类工作方块：加燃料、放原料、取产物 |
| `container.transfer` | 在容器与背包之间移动物品 |
| `block.interact` | 右键交互：按钮、门、拉杆等 |
| `block.place` | 放置一个方块；`block.place.line` 放一排 |
| `server.say` | 以玩家身份在聊天频道说一句 |

## 全权

`server.*`：传送、天气、改时间、给物品、生成实体、广播、经验、游戏模式、状态效果、难度、游戏规则等。
每一项都需要 operator、各自的开关与档位上允许；不可逆的先用一句话确认。

## 不要碰

`body.*`（身体动作：移动、挖、攻击、跟随、装备……）属于有身体的模组，不是天枢的能力。