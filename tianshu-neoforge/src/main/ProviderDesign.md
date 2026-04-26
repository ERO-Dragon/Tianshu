# 天枢世界状态数据提供者（WorldStateProvider）设计文档

> 本文档是底层"纯净数据快照模块"的详细设计，与 `Design.md` 中描述的上层功能对应。
> 本模块 **严禁** 引入任何 AI / 网络 / UI 渲染相关代码，仅封装 Minecraft Client 端数据读取。

---

## 一、模块放置策略

遵循项目已有的 `api/` 桥接模式（`common` 定义接口 + `neoforge` 提供实现）：

| 层级 | 模块 | 包路径 | 内容 |
|------|------|--------|------|
| 数据快照类 | `tianshu-common` | `com.rheinmetal.tianshu.snapshot` | 所有 POJO 数据载体 |
| Provider 接口 | `tianshu-common` | `com.rheinmetal.tianshu.provider` | 5 个 `IXxxProvider` 接口 |
| NeoForge 实现 | `tianshu-neoforge` | `com.rheinmetal.tianshu.platform.provider` | 5 个实现类 |

---

## 二、数据快照类清单（`snapshot` 包）

### 2.1 通用枚举

| 类名 | 值 | 用途 |
|------|---|------|
| `TargetType` | `BLOCK`, `ENTITY`, `BIOME`, `VOID` | 准星扫描目标分类 |

### 2.2 准星扫描（对应功能 1, 19）

| 类名 | 关键字段 | 说明 |
|------|----------|------|
| `CrosshairTargetData` | `TargetType type`, `String registryId`, `String displayName`, `Map<String, String> blockStateProperties`, `String mainHandItemId`, `String offHandItemId`, `float entityHealth`, `boolean hasBlockEntity` | 统一准星目标，多态字段按 type 取值 |

> **多态策略**：采用单 POJO + 可 null 字段方案。原因：类型安全固然好，但此场景下游消费者（AI prompt 组装）只需一次 switch 就够了，sealed interface 反而增加序列化/事件传递复杂度。同时降低对外暴露的类型数量，更简洁。

### 2.3 物品栏（对应功能 2, 8, 9, 10, 11）

| 类名 | 关键字段 | 说明 |
|------|----------|------|
| `ItemSnapshot` | `int slotIndex`, `String itemId`, `String displayName`, `int count`, `int maxDamage`, `int damage`, `float durabilityPercent`, `List<String> enchantments`, `Map<String, String> attributes` | 单个物品快照 |
| `InventorySnapshot` | `List<ItemSnapshot> items`, `int mainHandSlot`, `ItemSnapshot mainHand`, `ItemSnapshot offHand`, `List<ItemSnapshot> armor` | 完整背包快照 |
| `MatchedSlotData` | `int slotIndex`, `String itemId`, `String displayName`, `int count` | 搜索匹配结果 |

> **NBT 策略**：`attributes` 字段不做白名单限制，尽可能全面地暴露有用信息。提取的 key 包括但不限于：`display.Name`、`display.Lore`、`AttributeModifiers`、`CustomPotionEffects`、`Enchantments`（已单独提取为列表）、`Damage`、`Unbreakable`、`HideFlags`、以及任何模组自定义 key。使用递归展平策略（key.subKey.subSubKey → "key.subKey.subSubKey"），将嵌套 NBT 打平为 `Map<String, String>`。遇到无法解析的 NBT 类型统一 toString() 输出。

### 2.4 环境感知（对应功能 5, 6, 15, 17, 20）

| 类名 | 关键字段 | 说明 |
|------|----------|------|
| `NearbyEntityData` | `String entityId`, `String displayName`, `double relativeX`, `double relativeY`, `double relativeZ`, `double horizontalAngle`, `double distance`, `boolean hostile` | 周围实体快照（含水平方位角，用于声场定位） |
| `PotionEffectData` | `String effectId`, `String displayName`, `int durationTicks`, `int amplifier`, `boolean beneficial` | 药水效果 |
| `WorldEnvironmentData` | `boolean raining`, `boolean thundering`, `long dayTimeTicks`, `long totalTicks`, `float secondsUntilNight`, `float secondsUntilDay`, `String biomeId` | 世界环境 |

> **时间推算逻辑**：MC `dayTime` 范围 0~24000 tick。正午=6000，黄昏开始=12000，午夜=18000，黎明=0/24000。1 game tick = 0.05 real second。

### 2.5 玩家状态（对应功能 3, 12, 14）

| 类名 | 关键字段 | 说明 |
|------|----------|------|
| `PositionData` | `double x`, `double y`, `double z`, `float yaw`, `float pitch`, `String dimension` | 3D 坐标 + 朝向 |
| `NavigationInfo` | `PositionData current`, `PositionData lastDeathPoint`, `PositionData spawnPoint` | 导航总览（含死亡点和床点） |
| `GameSettingsData` | `float gamma`, `float masterVolume`, `int renderDistance`, `String language` | 客户端设置（只读快照） |
| `DeathContextData` | `String damageSourceId`, `String deathMessage`, `double x`, `double y`, `double z`, `String dimension`, `String killerEntityId` | 死亡上下文 |

> **死亡事件缓存**：在 Provider 实例内部用 `volatile` 字段缓存，通过 `LivingDeathEvent` 监听器写入。原因：数据量极小（一次仅一条），不值得走 EventBus 系统增加事件类型的膨胀。如果未来有其他模块也需感知死亡事件，再升级为 EventBus 事件。

### 2.6 配方（对应功能 4）

| 类名 | 关键字段 | 说明 |
|------|----------|------|
| `IngredientData` | `String itemId`, `String displayName`, `int count` | 单个原料/产物 |
| `RecipeData` | `String recipeId`, `String recipeType`, `IngredientData result`, `List<IngredientData> ingredients` | 单条配方 |
| `RecipeTreeData` | `String targetItemId`, `List<RecipeData> recipes` | 目标物品的配方集 |

---

## 三、Provider 接口清单

| 接口 | 方法 | 返回类型 |
|------|------|----------|
| `ITargetScannerProvider` | `getCrosshairTarget()` | `CrosshairTargetData` |
| `IInventoryDataProvider` | `getMainHandItemData()` | `ItemSnapshot` |
| | `getAllInventoryItemsData()` | `InventorySnapshot` |
| | `findItemSlotsByName(String name)` | `List<MatchedSlotData>` |
| `IEnvironmentAwarenessProvider` | `getNearbyThreats(double radius)` | `List<NearbyEntityData>` |
| | `getActivePotionEffects()` | `List<PotionEffectData>` |
| | `getWorldEnvironmentInfo()` | `WorldEnvironmentData` |
| `IPlayerStateProvider` | `getPlayerNavigationInfo()` | `NavigationInfo` |
| | `getClientGameSettings()` | `GameSettingsData` |
| | `getLastDeathContext()` | `DeathContextData` |
| `IRecipeDataProvider` | `getRecipeTree(String itemId)` | `RecipeTreeData` |

---

## 四、NeoForge 实现类清单

| 实现类 | 依赖的 MC API | 技术要点 |
|--------|--------------|----------|
| `NeoForgeTargetScanner` | `Minecraft.hitResult`, `BlockHitResult`, `EntityHitResult`, `level.getBlockState()`, `level.getBiome()` | BlockState 属性遍历转 Map；NBT 解析加 try-catch |
| `NeoForgeInventoryProvider` | `player.getInventory()`, `ItemStack`, `EnchantmentHelper` | 耐久百分比计算；附魔列表提取；NBT 递归展平 |
| `NeoForgeEnvironmentProvider` | `level.entities()`, `player.position()`, `player.getActiveEffects()`, `level.getDayTime()` | hostile 判断用 `Enemy` 接口；水平方位角计算；时间推算 |
| `NeoForgePlayerStateProvider` | `player.getLastDeathLocation()`, `player.getRespawnPosition()`, `Minecraft.options`, `LivingDeathEvent` | 死亡事件需在构造时注册监听器；内部 volatile 缓存 |
| `NeoForgeRecipeProvider` | `level.getRecipeManager()`, `RecipeHolder`, `Ingredient` | 按 output itemId 过滤；Ingredient 展平为具体 itemId |

---

## 五、防御性编程要求

1. 所有 `BlockEntity`、`Entity` 的 NBT 读取必须 try-catch，防止恶意模组/服务器传入畸形数据
2. 所有从 `Minecraft.getInstance()` 获取的对象在调用前做 null 检查（player、level 均可能为 null）
3. 所有方法必须兼容在非主线程调用（内部自行 `executeOnMainThread` 或做线程安全处理）
4. `Ingredient` 展平时可能匹配到空槽位（`Ingredient.EMPTY`），需过滤

---

## 六、执行顺序

```
Step 1: 创建所有 Snapshot 类（tianshu-common/snapshot/）
Step 2: 创建所有 Provider 接口（tianshu-common/provider/）
Step 3: 创建 NeoForge 实现类（tianshu-neoforge/platform/provider/）
Step 4: 在 TianshuClient 中组装 Provider 实例（可选，待上层消费者确定后接入）
```
