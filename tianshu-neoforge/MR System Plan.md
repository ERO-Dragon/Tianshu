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
- Tron-style 出现/消失动效，帧率无关

### MR 系统不负责什么（本次迭代）
- ❌ TTS 语音播报（由雷达模块独立负责）
- ❌ LLM 深度解析（仅预留 FOCUSING 触发接口，本次不实现）
- ❌ 悬停检测 / 自动进入 FOCUSING（本次不实现，需手动触发）
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
    boolean isAlive;                // 实体是否存活（死亡切灰度）
    boolean isHostile;              // 敌对 = 橙色，中立 = 绿色
    boolean isLineOfSight;          // 是否可见（影响卡片显隐）
    boolean isFocused;              // 是否为聚焦主角
    boolean isBackground;           // 是否为 FOCUSING 时的背景卡片
    boolean shouldKill;             // 超硬边框/超鞭子阈值，标记强杀
    boolean isGrayscale;            // 灰度滤镜（死亡实体）

    // ─── 实体数据（极简） ───
    String displayName;             // 实体显示名
    float health, maxHealth;        // 血量
    float distance;                 // 3D 欧氏距离
    float attackDamage;             // 攻击力
    float armorValue;               // 护甲值
    String mainHandItemId;          // 主手物品 ID（渲染用，如 "minecraft:iron_sword"）

    // ─── 颜色 ───
    int accentColor;                // 主题色：敌对橙 / 中立绿
}
```

### 3.2 MrProjector — 3D→2D 投影工具

```java
// 纯静态数学类，无状态
public final class MrProjector {

    /**
     * 将世界坐标投影为屏幕坐标
     * @param worldX, worldY, worldZ  实体的世界坐标（头顶 = entityY + height + 0.2）
     * @param playerX, playerY, playerZ  玩家位置
     * @param yaw, pitch  玩家视角
     * @param projMatrix  16-element column-major 投影矩阵
     * @param mvMatrix    16-element column-major 模型视图矩阵
     * @param screenWidth, screenHeight  屏幕像素尺寸
     * @return float[2] {screenX, screenY}，如果 behind camera 返回 null
     */
    public static float[] project(
        double worldX, double worldY, double worldZ,
        double playerX, double playerY, double playerZ,
        float yaw, float pitch,
        float[] projMatrix, float[] mvMatrix,
        int screenWidth, int screenHeight
    );

    /**
     * 判断投影点是否在屏幕安全区（软边框）内
     * @param sx, sy  屏幕坐标
     * @param sw, sh  屏幕尺寸
     * @param marginPercent  边距百分比（如 0.03 = 3%）
     * @return true = 在安全区
     */
    public static boolean isInSoftBounds(float sx, float sy, int sw, int sh, float marginPercent);

    /**
     * 判断投影点是否在屏幕硬边框内
     */
    public static boolean isInHardBounds(float sx, float sy, int sw, int sh);
}
```

**投影算法核心**：
1. 构造实体的模型视图坐标：`relX = worldX - playerX`（同理 Y/Z）
2. 应用 yaw/pitch 旋转矩阵（绕 Y 轴旋转 -yaw，绕 X 轴旋转 -pitch）
3. 应用投影矩阵透视除法
4. NDC → 屏幕坐标映射

### 3.3 MrStateMachine — 三态状态机

```
    ┌────────┐  语音"打开扫描"   ┌──────────┐  悬停超时/手动触发  ┌──────────┐
    │ SILENT │ ──────────────▶  │ SCANNING │ ─────────────────▶  │ FOCUSING │
    └────────┘                  └──────────┘                      └──────────┘
         ▲                           │                                  │
         │         语音"关闭扫描"      │                                  │
         │◀──────────────────────────┘                                  │
         │                                                               │
         └───────────────────────────────────────────────────────────────┘
                              语音"关闭扫描" / 取消聚焦
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
2. 从 IEnvironmentAwarenessProvider 获取 getNearbyHostiles(mrRange)
3. 对每个实体：
   a. MrProjector.project() → 得到 A 点屏幕坐标
   b. 计算距离缩放 scale = base_dist / dist
   c. 计算纵深透明 alpha = 1.0 - (dist / 32) * 0.2
   d. 计算方向导向 B 点（带死区力场算法）
   e. MrWhipLayout 计算带阻尼的 C 点
4. 群体防撞布局推挤
5. MrAnimationController 驱动 appearProgress / disappearProgress
6. 异常熔断检查（硬边框/鞭子超限/遮挡反转/死亡灰度）
7. FOCUSING 特殊处理（主角放大，群演缩小）
8. 打包为 MrCardSnapshot，塞入 outputQueue
```

### 3.5 MrWhipLayout — 鞭子效应布局引擎

```
三级结构：
  A（实体头顶） ─── 0阻尼刚性 ──→ B（线段拐点） ─── 0.15f阻尼 ──→ C（卡片锚点）

关键参数：
- DAMPING_FACTOR = 0.15f          ← B→C 阻尼系数
- WHIP_KILL_THRESHOLD = 300.0f    ← 鞭子拉断阈值（像素）
- RIGID_SEGMENT_LENGTH = 40.0f    ← A→B 刚性线段长度（像素）

B 点计算（带死区力场导向）：
  - 实体在屏幕上半部 → B 在 A 的正上方
  - 实体在屏幕下半部 → B 在 A 的正下方
  - 中间过渡区 → Smoothstep 插值旋转方向
  - 穿越正中心时依赖上一帧方向记忆划弧线

C 点计算：
  - 每帧：cardX += (targetCardX - cardX) * DAMPING_FACTOR * dt
  - cardY 同理
  - 若 |C - target| > WHIP_KILL_THRESHOLD → 标记 shouldKill，重新实例化

群体防撞：
  - 横向防撞：穿过每个 C 点画无限长水平辅助线，其他卡片的 top/bottom 刚性吸附
  - 纵向防撞：单向强制推挤，遇阻死锁
  - 禁止弹簧力（防抖动死循环）
```

### 3.6 MrAnimationController — Tron 动效驱动

```
出现动画（appearProgress: 0.0 → 1.0）：
  [0.0 ~ 0.3] 竖线从 A 点窜出到 B 点长度
  [0.3 ~ 0.5] 斜线从 B 点拽向 C 点
  [0.5 ~ 1.0] 从 C 点裂变拉伸成完整卡片框
  > 1.0 后允许文字浮现

消失动画（disappearProgress: 1.0 → 0.0，镜像反转）：
  文字 Glitch 挤压 → 横线回缩 → 斜线弹回 → 竖线缩回

帧率无关：
  progress += SPEED * deltaTime
  所有阻尼乘以 deltaTime

错峰调度：
  同一秒内最多 10 张卡，按距离排序，每张延迟 0.1s 实例化

颜色系统：
  敌对 = accentColor(0xFF, 0x66, 0x00)  橙色
  中立 = accentColor(0x00, 0xFF, 0x88)  绿色
```

### 3.7 MrRenderer — 主线程无脑绘制器

```java
// 注册在 RegisterGuiLayersEvent 中
// 核心原则：poll snapshot，绘制，不允许任何计算逻辑

void onRender(GuiGraphics g, DeltaTracker dt) {
    while (!outputQueue.isEmpty()) {
        MrCardSnapshot snap = outputQueue.poll();
        drawCard(g, snap);
    }
}

void drawCard(GuiGraphics g, MrCardSnapshot s) {
    // 1. 根据appearProgress绘制牵引线（竖线→斜线→框展开）
    // 2. 根据appearProgress绘制卡片背景（切角矩形）
    // 3. 如果appearProgress > 1.0，绘制内容：
    //    a. 实体名称（Font.drawString + 赛博阴影色）
    //    b. 血条（双层 fill 叠加）
    //    c. 距离数值
    //    d. 攻击力图标（剑） + 数值
    //    e. 护甲图标（盾） + 数值
    // 4. 如果 isGrayscale → 应用灰度着色
    // 5. 如果 isBackground → 叠加深半透明遮罩
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
| DAMPING_FACTOR | 0.15f | B→C 鞭子阻尼系数 |
| WHIP_KILL_THRESHOLD | 300.0f | 鞭子拉断阈值（像素） |
| RIGID_SEGMENT_LENGTH | 40.0f | A→B 刚性线段长度（像素） |
| DISTANCE_ALPHA_FACTOR | 0.2f | 纵深透明衰减系数 |
| BASE_DISTANCE | 8.0 | 伪 3D 基准距离（scale=1.0 的距离） |
| SOFT_MARGIN_PERCENT | 0.03f | 软边框百分比 |
| HARD_MARGIN_PERCENT | 0.01f | 硬边框百分比 |
| APPEAR_SPEED | 2.0f | 出现动画速度（进度/秒） |
| DISAPPEAR_SPEED | 3.0f | 消失动画速度（更快） |
| CARD_BASE_WIDTH | 120.0f | 卡片基础宽度（像素） |
| CARD_BASE_HEIGHT | 50.0f | 卡片基础高度（像素） |
| CUT_CORNER_SIZE | 8 | 切角矩形切角大小（像素） |
| NEON_WIDTH_INNER | 1 | 霓虹亮线宽度 |
| NEON_WIDTH_OUTER | 3 | 霓虹底线宽度 |
| STAGGER_MAX_PER_SECOND | 10 | 每秒最大新实例化卡片数 |
| STAGGER_DELAY | 0.1f | 错峰延迟间隔（秒） |
| COLOR_HOSTILE | 0xFF6600 | 敌对主题色（橙） |
| COLOR_NEUTRAL | 0x00FF88 | 中立主题色（绿） |
| COLOR_BACKGROUND_MASK | 0x99000000 | 背景卡片遮罩色 |
| LOS_FOLLOW_GRACE_PERIOD | 1.0f | 遮挡反转跟随宽限期（秒） |

---

## 七、异常熔断规则汇总

| 异常场景 | 触发条件 | 处理方式 |
|---------|---------|---------|
| 超硬边框 | 投影坐标越出屏幕 1% | 瞬间强杀（不播消失动画） |
| 超软边框 | 投影坐标越出屏幕 3% | Alpha 阻尼平滑淡出 |
| 鞭子拉断 | B→C 距离 > 300px | 强杀旧卡片，新位置重播出现动画 |
| 遮挡反转 | lineOfSight == false | A 点跟随 1 秒后播消失；中途恢复则目标进度切 1.0 阻尼重展 |
| 实体死亡 | !isAlive | 瞬间切灰度，等 deathTime 后播倒放消失 |
