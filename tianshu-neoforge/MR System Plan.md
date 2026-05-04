# 🌐 天枢 MR 全息战术系统 — 工程实施计划书

> 本文档基于《MR Sysfem.md 终极开发宪法 v3.0》，结合现有雷达模块架构，输出可直接编码的工程蓝图。
> 与雷达模块的关系：**复用 NearbyEntityData 数据源，独立拥有自己的 32 格感知半径，服务端扫描取两者最大值。**

---

## 一、模块职责边界

### MR 系统负责什么
- 在 SCANNING 状态下，为 32 格内最多 10 个敌对实体生成 2D 全息牵引卡片
- 卡片包含：实体名称、血条、距离、攻击力(剑图标)、防御力(盾图标)
- 卡片遵循伪 3D（近大远小）、绿/橙二分色（中立/敌对）
- 被物理遮挡的实体卡片隐没（非透视）
- FOCUSING 状态下主角卡片放大到屏幕上半区锚点，其他卡片缩小退场
- 凝视聚焦：SCANNING 预热期（≈1.5s）后，准星对准敌对实体持续凝视 3 秒自动进入 FOCUSING；准星移开时凝视计时器以相同速率反向衰减
- Tron-style 出现/消失动效，帧率无关

### MR 系统不负责什么（本次迭代）
- ❌ TTS 语音播报（由雷达模块独立负责）
- ❌ LLM 深度解析（仅预留 FOCUSING 触发接口，本次不实现）
- ❌ 与雷达模块的状态联动（各自独立状态机）
- ❌ 服务端网络包（MR 纯客户端模块）

---

## 二、文件结构与类职责

### 2.1 tianshu-common 层（纯 Java，零 MC 依赖）

```
tianshu-common/src/main/java/com/rheinmetal/tianshu/function/MR/
├── MrCardSnapshot.java          ← 纯 POJO：卡片渲染所需的所有 float/boolean/int
├── MrProjector.java             ← 纯数学：3D 世界坐标 → 2D 屏幕坐标投影
├── MrStateMachine.java          ← 状态机：SILENT / SCANNING / FOCUSING
├── MrEngine.java                ← 主引擎：统筹所有计算逻辑
├── MrWhipLayout.java            ← 布局引擎：鞭子效应 + 群体防撞
├── MrAnimationController.java   ← 动效控制器：Tron-style 进度条驱动
└── MrConstants.java             ← 常量表：所有魔术数字集中管理
```

### 2.2 tianshu-neoforge 层（MC 渲染适配）

```
tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/
└── MrRenderer.java              ← 主线程绘制器：从队列 poll Snapshot 后调 MC 渲染 API
```

---

## 三、核心类详细设计

### 3.1 MrCardSnapshot — 线程安全影子快照

```java
// 设计红线：仅含基本类型，绝对禁止持有 ItemStack/Vec3 等 MC 对象
// 产出方：子线程 MrEngine
// 消费方：主线程 MrRenderer
public final class MrCardSnapshot {
    // ─── 锚点坐标（屏幕像素） ───
    float anchorX, anchorY;         // A 点：实体头顶 2D 投影
    float jointX, jointY;           // B 点：线段拐弯点
    float cardX, cardY;             // C 点：卡片左上角

    // ─── 卡片尺寸 ───
    float cardWidth, cardHeight;
    float scale;                    // 伪 3D 缩放比 base_dist / dist

    // ─── 透明度 ───
    float alpha;                    // 最终透明度（含纵深衰减 + 遮挡 + 状态叠加）
    float distanceFadeAlpha;        // 距离衰减分量（独立保存供渲染判断）

    // ─── 动画进度 ───
    float appearProgress;           // 出现动画 0.0 → 1.0（>1.0 标志动画完毕）
    float disappearProgress;        // 消失动画 1.0 → 0.0

    // ─── 状态标志 ───
    boolean isAlive;                // 实体是否存活
    boolean isHostile;              // 敌对 = 橙红色，中立 = 亮蓝色
    boolean isLineOfSight;          // 是否可见（LOS 丢失时 A 点冻结并触发消失动画）
    boolean isFocused;              // 是否为聚焦主角
    boolean isBackground;           // 是否为 FOCUSING 时的背景卡片
    boolean hasMainHandItem;        // 是否有主手物品

    // ─── 实体数据（极简） ───
    String displayName;             // 实体显示名
    float health, maxHealth;        // 血量
    float distance;                 // 3D 欧氏距离
    float attackDamage;             // 攻击力
    float armorValue;               // 护甲值
    String mainHandItemId;          // 主手物品 ID（渲染用，如 "minecraft:iron_sword"）

    // ─── 颜色（预计算，渲染器直取） ───
    int accentColor;                // 主题色：敌对橙 / 中立绿
    int accentR, accentG, accentB;  // 主题色 RGB 分量（灰度时被替换为灰值）
    int textAlphaColor;             // 白色文字（含 alpha 通道）
    int accentTextColor;            // 主题色文字（含 alpha 通道）
    int healthBarBgColor;           // 血条背景色（含 alpha）
    int healthBarColor;             // 血条填充色（含 alpha，红→绿插值）
    float healthBarFillWidth;       // 血条填充像素宽度
    float healthBarFullWidth;       // 血条总像素宽度
    int glitchOffset;               // Glitch 横向偏移（基于 UUID 哈希）

    // ─── 预格式化文本 ───
    String distanceText;            // "12m"
    String attackText;              // "5.0" 或 null
    String armorText;               // "⛨ 3.0" 或 null

    // ─── 预计算布局坐标（相对于卡片左上角的偏移） ───
    float contentStartX, contentStartY;
    float statsStartX;
    float contentNameEndY;
    float contentBarEndY;
    float contentStatsY;
    float weaponIconX, weaponIconY;
    float atkTextX, defTextX;
}
```

### 3.2 MrProjector — 3D→2D 投影工具

```java
// 纯静态数学类，无状态
public final class MrProjector {

    /**
     * 标准 MVP 链投影：世界坐标 → 模型视图矩阵 → 投影矩阵 → NDC → 屏幕坐标
     * @param worldX, worldY, worldZ  实体的世界坐标（头顶 = entityY + 1.8 + 0.2）
     * @param modelViewMatrix  16-element column-major 模型视图矩阵
     * @param projectionMatrix  16-element column-major 投影矩阵
     * @param screenWidth, screenHeight  屏幕像素尺寸
     * @return float[2] {screenX, screenY}，如果 behind camera 返回 null
     */
    public static float[] project(
        double worldX, double worldY, double worldZ,
        float[] modelViewMatrix, float[] projectionMatrix,
        int screenWidth, int screenHeight
    );
}
```

**投影算法核心（标准 MVP 链）**：
1. 世界坐标 × modelViewMatrix → 视图空间坐标
2. 视图空间坐标 × projectionMatrix → 裁剪空间坐标
3. 透视除法（w 分量） → NDC 归一化坐标
4. NDC → 屏幕坐标映射（翻转 Y 轴）

### 3.3 MrStateMachine — 三态状态机

```
    ┌────────┐  语音"打开扫描"   ┌──────────┐  准星对准实体：1s aim warmup + 3s gaze  ┌──────────┐
    │ SILENT │ ──────────────▶  │ SCANNING │ ─────────────────────────────────────▶  │ FOCUSING │
    └────────┘                  └──────────┘  (需先经过 2s scanning warmup)            └──────────┘
         ▲                           │                                  │
         │         语音"关闭扫描"      │                                  │
         │◀──────────────────────────┘                                  │
         │                                                               │
         └───────────────────────────────────────────────────────────────┘
                              语音"关闭扫描" / 取消聚焦 / 目标消失
```

```java
public class MrStateMachine {
    enum State { SILENT, SCANNING, FOCUSING }

    private State state = State.SILENT;
    private String focusedEntityUuid = null;  // FOCUSING 锁定的实体 UUID

    void transitionToScanning();
    void transitionToFocusing(String entityUuid);
    void transitionToSilent();
    boolean isScanning();
    boolean isFocusing();
    String getFocusedEntityUuid();
}
```

**红线**：状态机只管状态流转，**绝对不碰坐标、不碰渲染、不碰 ItemStack**。

### 3.4 MrEngine — 主引擎（子线程计算核心）

```
职责：每 tick 接收 NearbyEntityData 列表 + 投影矩阵，产出 List<MrCardSnapshot>
运行频率：每 2 tick（与雷达一致）
线程模型：在 ClientTickEvent 中调用，所有重度数学在此完成

核心字段：
- MrStateMachine stateMachine
- MrProjector projector（静态调用）
- MrWhipLayout whipLayout
- MrAnimationController animationController
- Map<String, TrackedCard> activeCards    ← 活跃卡片追踪表
- ConcurrentLinkedQueue<MrCardSnapshot> outputQueue  ← 线程安全输出队列
- int maxCards = 10                       ← 最大卡片数
- double mrRange = 32.0                   ← MR 感知半径（独立于雷达）
```

**每 tick 执行流程**：
```
1. if stateMachine == SILENT → 清空 outputQueue，return
2. 从 IEnvironmentAwarenessProvider 获取 MR 范围内实体快照
3. 对每个实体：
   a. MrProjector.project() → 得到 A 点屏幕坐标；LOS 丢失时使用 lastSeenAnchor 冻结 A 点
   b. 计算距离缩放 scale = base_dist / dist，并平滑叠加聚焦/背景缩放因子
   c. 计算基础透明度与距离透明度，聚焦背景卡片通过 alphaFactor 平滑降低
   d. 根据屏幕分区计算 A→B 刚性段，AB 长度随 scale 透视缩放
   e. 根据统一几何结果计算 B→C 目标方向、C 点、连接边、卡片目标矩形
4. 进行预布局方向搜索：按聚焦、敌对、距离排序；先判断 C 点占用，再用 AABB 判断矩形重叠；八方向失败后沿原始 A→C 方向延长；最后才选择最不坏候选
5. MrWhipLayout 使用动态阻尼让当前卡片位置追向目标卡片矩形，保留鞭子滞后效果
6. MrAnimationController 驱动 appearProgress / disappearProgress；LOS 丢失、出屏、死亡、离开范围均走正常消失/恢复动画，不做硬杀
7. P2 预计算：所有颜色/文本/布局像素值填入 Snapshot（渲染器零计算）
8. 凝视聚焦逻辑（仅 SCANNING 状态）：
   a. scanningTimer 累积时间
   b. scanningTimer >= SCANNING_WARMUP 后进入 aim warmup
   c. aim warmup 完成后，获取 getCrosshairTargetEntityUuid() 并比对当前帧实体
   d. 同一目标持续对准 → gazeTimer += deltaTime
   e. 准星移开或目标不在列表 → gazeTimer -= deltaTime（反向衰减，不低于 0）
   f. 目标切换 → 立即进入新实体自己的凝视计时过程
   g. gazeTimer >= GAZE_FOCUS_DURATION → transitionToFocusing(uuid)
9. FOCUSING 特殊处理：主角卡片平滑放大，背景卡片平滑缩小/降透明；聚焦文本逐字输出，输出完成后才开始退出倒计时
10. 打包为 MrCardSnapshot，塞入 outputQueue
```

### 3.5 MrWhipLayout — 鞭子效应布局引擎

```
三级结构：
  A（实体头顶） ─── 0阻尼刚性 ──→ B（线段拐点） ─── 动态阻尼 ──→ C（卡片连接点）

关键参数：
- DAMPING_FACTOR = 0.15f                    ← B→C 基础阻尼系数
- DAMPING_DISTANCE_REFERENCE = 200.0f       ← 动态阻尼距离参考
- DAMPING_MAX_FACTOR = 0.75f                ← 动态阻尼上限
- RIGID_SEGMENT_LENGTH = 40.0f              ← A→B 基础刚性线段长度（会乘 scale）
- BC_REST_ANGLE_DEGREES = 40.0f             ← B→C 静息目标角

B 点计算：
  - 实体在屏幕上方 1/5 → B 在 A 下方
  - 实体在屏幕下方 1/5 → B 在 A 上方
  - 实体在屏幕中间区域 → 根据左右半屏向外水平延伸
  - AB 段无阻尼，直接跟随 A 点；长度为 RIGID_SEGMENT_LENGTH × scale

C 点与卡片目标：
  - 静息态下 B→C 长度与 A→B 等长
  - B→C 目标方向由屏幕分区与 40° 静息角统一推导
  - C 点落在卡片上边或下边，C 点横向比例由实体屏幕横向比例映射到 CONNECTOR_EDGE_MIN/MAX_RATIO
  - 卡片目标矩形由 C 点、连接边、横向比例统一反推，不单独平移拼凑

当前卡片位置：
  - currentCardX/Y 通过动态阻尼追向目标 cardX/Y
  - 距离越远，阻尼越大；接近目标后阻尼自然降低
  - 不再存在鞭子拉断强杀阈值，快速转头时靠动态阻尼追赶

群体布局：
  - 不在 MrWhipLayout 内做事后 resolveCollisions 推挤
  - MrEngine 在生成目标位置前进行预布局方向搜索
  - 预布局只给出目标 C 点与目标卡片矩形，实际显示仍由鞭子阻尼平滑追向目标
```

### 3.6 MrAnimationController — Tron 动效驱动

```
出现动画（appearProgress: 0.0 → 1.0）：
  [0.0 ~ 0.3] 竖线从 A 点窜出到 B 点长度
  [0.3 ~ 0.5] 斜线从 B 点拽向 C 点
  [0.5 ~ 1.0] 从 C 点裂变拉伸成完整卡片框
  > 1.0 后允许文字浮现

消失动画（disappearProgress: 1.0 → 0.0，镜像反转）：
  卡片框与内容一起压缩 → B→C 线回缩 → A→B 线回缩

帧率无关：
  progress += SPEED * deltaTime
  所有阻尼乘以 deltaTime

错峰调度：
  仅 MR 刚开启时对初始实体按距离排序并分配 0.1s 间隔；启动阶段结束后，新实体立即播放出现动画

颜色系统：
  敌对 = accentColor(0xFF, 0x55, 0x33)  橙红色
  中立 = accentColor(0x33, 0xAA, 0xFF)  亮蓝色
```

### 3.7 MrRenderer — 主线程无脑绘制器（P2 预计算架构）

```java
// 注册在 RegisterGuiLayersEvent 中
// P2 原则：poll snapshot → 直取字段绘制，零计算逻辑
// 所有颜色、文本、布局像素值均由 MrEngine 预计算填入 Snapshot
// 渲染器只做 fill/drawString/renderItem 调用

void onRender(GuiGraphics g, DeltaTracker dt) {
    // 使用 lastFrameSnapshots 缓存防止 MR tick 间隙闪烁
    while (!outputQueue.isEmpty()) {
        MrCardSnapshot snap = outputQueue.poll();
        lastFrameSnapshots.add(snap);
    }
    for (MrCardSnapshot cachedSnap : lastFrameSnapshots) {
        drawCard(g, cachedSnap, font, mc);
    }
}

void drawCard(GuiGraphics g, MrCardSnapshot s) {
    // 1. disappearProgress < 1.0 → 绘制消失动画（卡片框与内容一起压缩 → B→C → A→B）
    // 2. 根据 appearProgress 绘制牵引线（A→B → B→C）
    // 3. 根据 appearProgress 绘制卡片背景（按 C 所在上/下边单边展开）
    // 4. appearProgress 完成后绘制内容（所有值从 snapshot 直取）：
    //    a. 实体名称（s.accentTextColor 直取）
    //    b. 血条（s.healthBarBgColor / s.healthBarColor / s.healthBarFillWidth 直取）
    //    c. 距离图标 + s.distanceText
    //    d. 铁剑图标 + 攻击力文本
    //    e. 铁胸甲图标 + 护甲文本
    // 5. isBackground → 不盖黑色蒙版，由 Engine 预先平滑降低 alpha 并缩小 scale
    // 唯一保留的 NeoForge 层解析：resolveItemStack（ID → ItemStack 缓存映射）
}
```

**切角矩形绘制规范**：
- 背景：先画完整暗色半透明矩形，再用背景色在四角画 4 个小三角形盖掉直角
- 线框：画 1px 宽矩形拼出四条边，四角位置留空
- 霓虹发光线：先画 3px 宽低透明度底线，再叠加 1px 宽高亮亮线
- 切角大小：8px（常量统一管理）

---

## 四、与现有系统的对接点

### 4.1 数据复用（已就绪）

| 数据需求 | 数据来源 | 状态 |
|---------|---------|------|
| 32 格内敌对实体列表 | `IEnvironmentAwarenessProvider.getNearbyHostiles(32)` | ✅ 已有 |
| 实体坐标/血量/攻击力/护甲/主手物品 | `NearbyEntityData` 字段 | ✅ 已有 |
| 投影矩阵/模型视图矩阵 | `IRenderContextProvider.getProjectionMatrix()` | ✅ 已有 |
| 屏幕尺寸 | `IRenderContextProvider.getScreenWidth/Height()` | ✅ 已有 |
| 玩家位置/视角 | `PositionData` | ✅ 已有 |
| 准星瞄准实体 UUID | `IEnvironmentAwarenessProvider.getCrosshairTargetEntityUuid()` | ✅ 已有 |

### 4.2 扫描半径统筹（需修改 1 处）

在 `TianshuClient.tickAcousticRadar()` 中，已有注释预留了 MR 系统的扩展点：

```java
// 当前代码（雷达独占）：
requiredRadius -> {
    environmentProvider.setActiveScanRadius(requiredRadius);
}

// 需改为（取两者最大值）：
requiredRadius -> {
    double mrRadius = (mrEngine != null && mrEngine.isRunning()) ? mrEngine.getRequiredRadius() : 0.0;
    environmentProvider.setActiveScanRadius(Math.max(requiredRadius, mrRadius));
}
```

### 4.3 GUI Overlay 注册（需新增 1 处）

在 `TianshuClient.registerOverlays()` 中追加注册 `MrRenderer` 的图层。

### 4.4 FeatureManager 开关（需新增 1 处）

在 `FeatureManager` 中新增 `tacticalMrEnabled` 客户端偏好开关。

---

## 五、实施阶段（按依赖顺序）

### Phase 1：基建层（无 MC 依赖，可独立编译测试）
1. `MrConstants.java` — 所有常量集中定义
2. `MrCardSnapshot.java` — 纯 POJO
3. `MrProjector.java` — 3D→2D 数学投影
4. `MrStateMachine.java` — 三态状态机

### Phase 2：逻辑层（依赖 Phase 1）
5. `MrAnimationController.java` — Tron 动效进度驱动
6. `MrWhipLayout.java` — 鞭子效应 + 防撞布局
7. `MrEngine.java` — 主引擎，串联以上所有组件

### Phase 3：渲染层（依赖 Phase 1 + 2 + MC API）
8. `MrRenderer.java` — NeoForge GUI 渲染器

### Phase 4：集成层（依赖 Phase 1-3）
9. 修改 `FeatureManager` — 新增 MR 开关
10. 修改 `TianshuClient` — 注册 overlay、tick 驱动、扫描半径统筹

---

## 六、关键常量一览表（MrConstants.java）

| 常量 | 值 | 说明 |
|------|---|------|
| MR_RANGE | 32.0 | MR 感知半径（格） |
| MAX_CARDS | 10 | 最大同时显示卡片数 |
| DAMPING_FACTOR | 0.15f | B→C 基础阻尼系数 |
| DAMPING_DISTANCE_REFERENCE | 200.0f | 动态阻尼距离参考 |
| DAMPING_MAX_FACTOR | 0.75f | 动态阻尼上限 |
| RIGID_SEGMENT_LENGTH | 40.0f | A→B 基础刚性线段长度（会乘 scale） |
| BC_REST_ANGLE_DEGREES | 40.0f | B→C 静息目标角 |
| CONNECTOR_EDGE_MIN_RATIO | 0.2f | C 点在连接边上的最小横向比例 |
| CONNECTOR_EDGE_MAX_RATIO | 0.8f | C 点在连接边上的最大横向比例 |
| BASE_ALPHA | 0.8f | 卡片基础透明度 |
| MIN_DISTANCE_ALPHA | 0.5f | 距离透明度最低值 |
| DISTANCE_ALPHA_FACTOR | 0.5f | 纵深透明衰减系数 |
| BASE_DISTANCE | 8.0 | 伪 3D 基准距离（scale=1.0 的距离） |
| APPEAR_SPEED | 1.0f | 出现动画速度（1 秒完成） |
| DISAPPEAR_SPEED | 1.0f | 消失动画速度（与出现对称） |
| CARD_BASE_WIDTH_RATIO | 0.0625f | 卡片基础宽度相对屏幕宽度比例 |
| CARD_BASE_HEIGHT_RATIO | 0.046f | 卡片基础高度相对屏幕高度比例 |
| CARD_MIN_BASE_WIDTH / HEIGHT | 96 / 40 | 基础卡片最小尺寸 |
| CARD_MAX_BASE_WIDTH / HEIGHT | 160 / 72 | 基础卡片最大尺寸 |
| CARD_MAX_FOCUSED_WIDTH_RATIO | 0.25f | 聚焦卡片最大宽度比例 |
| CARD_MAX_FOCUSED_AREA_RATIO | 0.08f | 聚焦卡片基础放大面积比例上限；只约束基础聚焦框，不限制 LLM 文本继续撑高卡片 |
| CUT_CORNER_HEIGHT_RATIO | 0.18f | 切角大小相对卡片高度比例 |
| NEON_OUTER/INNER_* | 见 MrConstants | 霓虹边框宽度按卡片高度缩放 |
| ORIGIN_MARKER_* | 见 MrConstants | A 点原点标记尺寸按卡片高度缩放 |
| STAGGER_DELAY | 0.1f | 初始错峰间隔 |
| COLOR_HOSTILE | 0xFF5533 | 敌对主题色（橙红） |
| COLOR_NEUTRAL | 0x33AAFF | 非敌对主题色（亮蓝） |
| BACKGROUND_SCALE | 0.75f | FOCUSING 背景卡片缩放比 |
| FOCUS_SCALE | 2.0f | FOCUSING 主角卡片额外放大倍数 |
| UI_TRANSITION_SPEED | 6.0f | 聚焦/背景视觉过渡速度 |
| BACKGROUND_ALPHA_FACTOR | 0.4f | 背景卡片透明度倍率 |
| TICK_INTERVAL | 2 | MR tick 间隔（MC tick） |
| TICK_DURATION | 0.05f | 单 MC tick 时长（秒） |
| FOCUS_AIM_WARMUP_SECONDS | 1.0f | 准星进入目标后的瞄准预热 |
| GAZE_FOCUS_DURATION | 3.0f | 准星对准目标触发聚焦的凝视时长 |
| FOCUS_EXIT_COUNTDOWN_SECONDS | 5.0f | 聚焦文本输出完成后的退出倒计时 |
| FOCUS_TEXT_CHARS_PER_SECOND | 32.0f | 聚焦详情文本逐字显示速度 |
| APPEAR_ANIM_DURATION | 1.0f | 出现动画总时长 |
| SCANNING_WARMUP | 2.0f | 扫描预热期，之后才允许进入 aim warmup |
| FONT_LINE_HEIGHT | 9 | MC 默认字体行高（像素） |
| CONTENT_* / STATS_* | 见 MrConstants | 内容排版与图标间距 |

---

## 七、异常与恢复规则汇总

| 异常场景 | 触发条件 | 处理方式 |
|---------|---------|---------|
| 投影点出屏 | A 点投影在屏幕外但仍可得到坐标 | 不硬杀，卡片继续由阻尼跟随，同时进入正常消失动画；恢复时按动画恢复逻辑接回 |
| LOS 丢失 | lineOfSight == false | A 点冻结在最后可见位置，立即播放消失动画；实体恢复可见时取消消失并平滑恢复 |
| 实体离开范围 | 不再出现在 MR 范围实体列表 | 使用最后快照继续驱动消失动画，不瞬移、不冻结卡片布局 |
| 实体死亡 | !isAlive | 直接播放正常消失动画，不灰度、不停留 |
| 快速转头导致 B→C 拉长 | 当前卡片位置距离目标很远 | 不拉断、不重播出现动画；动态阻尼提高追赶速度 |
| 消失过程中目标恢复 | disappearProgress 尚未归零时目标重新可用 | 根据当前消失阶段恢复出现动画，避免闪烁和瞬移 |

---

## 八、更新日志备份

### 2026-05-02：原版低成本渲染、正交折线与热路径优化

本次变更目标：保留 MR 的 A 点、牵引关系和战术信息表达，但将渲染成本从科技感渐变/头像/斜线逐像素路径，转向 Minecraft 原版 GUI 风格的低成本绘制方式。

- 渲染风格调整：
  - `MrRenderer` 仍使用现有 GUI overlay 入口，暂不引入 Mixin 注入原版 HUD。
  - 卡片背景从切角渐变逐行填充改为普通半透明矩形 `fill` + 1px 边框，降低 draw call 和逐行计算成本。
  - 删除怪物头像渲染路径，不再解析实体 renderer/texture，也不再创建临时实体获取贴图。
  - 保留 A 点原点标记。
  - 保留卡片内距离指南针、攻击剑、护甲图标和对应文本。
  - 日夜透明度继续作用于文字、图标、线条和卡片背景整体。

- 折线与连接方式调整：
  - 牵引线改为横平竖直的正交折线，不再使用斜线逐像素绘制。
  - 渲染端根据 A 点与 C 点动态选择折线中转点：横向差更大时先水平再垂直，纵向差更大时先垂直再水平。
  - `MrCardSnapshot` 新增 `connectorEdge` 字段，用于表达 C 点连接卡片四边中的哪一边：`0=顶边`、`1=底边`、`2=左边`、`3=右边`。
  - `MrEngine` 默认布局会从卡片四边中选择距离 A 点最近的连接边，并把 C 点限制在安全比例范围内。

- 象限保持与避障策略：
  - `TrackedCard` 增加象限锁定状态，卡片默认尽量保持在已有象限内。
  - 只有当前象限导致卡片投放距离明显离屏/过远时，才切换到新的默认象限。
  - 原有预布局避障流程保留：按聚焦、敌对、距离排序；先尝试默认位置，再做局部候选搜索；候选可接受时直接使用，不可接受时保留最低惩罚 fallback。
  - 当前版本默认几何支持四边连接；旧二层候选仍主要沿顶/底连接逻辑工作，后续如需要可继续扩展为完整四边候选。

- 非渲染热路径优化：
  - `MrRenderer` 每帧只遍历一次 `entitiesForRendering()` 构建 `visibleEntityCache`。
  - 实时投影时按 UUID 从 `visibleEntityCache` O(1) 获取实体，避免每张卡片重复遍历实体列表。
  - `incomingSnapshots`、`targetByUuid` 改为成员集合复用，减少每帧临时对象分配。
  - 删除头像相关贴图解析后，减少了每帧资源查找、临时实体创建和 renderer 访问成本。

- 已检查：
  - `MrCardSnapshot.java` 无 IDE diagnostics。
  - `MrEngine.java` 无 IDE diagnostics。
  - `MrRenderer.java` 无 IDE diagnostics。
