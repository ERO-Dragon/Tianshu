# 天枢设置 GUI 框架 v0.1：通用架构契约

## 1. 核心定位：模块设置声明、布局与渲染基础设施

天枢设置 GUI 是模块设置界面的基础设施，不是某个业务模块的专属界面。

它不理解 ASR、LLM、TTS、UI 桥、聊天助手等具体业务，不直接保存模块配置，不直接调用模块内部实现，也不直接承担协议通信职责。它的职责是收集模块声明的设置分类，构建统一的设置模板模型，负责原版风格布局、滚动、渲染、交互控件创建，并为后续 Mixin 接入保留稳定边界。

### 1.1 五项基本原则

1. **GUI 不理解业务，但理解设置模板。**  
   GUI 不知道某个开关是否代表语音、模型、雷达或聊天助手，但理解 `Enable`、`ToggleGroup`、`OptionGroup`、`StatusGroup`、`ActionGroup`、`ListGroup`、`TextBlock`、`Separator` 等模板语义。

2. **GUI 不拥有模块配置，但编排设置交互。**  
   模块拥有自己的配置来源、默认值、校验规则、保存方式和运行时副作用。GUI 只负责展示、编辑、脏状态提示、统一保存入口和结果展示。

3. **模块不绘制界面，只声明设置结构。**  
   模块不应该直接操作 `Screen`、`GuiGraphics`、坐标、滚动条和原版 widget 布局。模块只声明分类、标题、描述、模板和数据绑定。

4. **框架负责布局，模板负责语义。**  
   模板只表达“这里有一个开关组”“这里有一个选项组”“这里有一个状态行”。坐标、间距、滚动可见性、控件宽度、禁用样式由框架统一处理。

5. **原版渲染是默认实现，不是唯一实现。**  
   当前默认 renderer 使用原版 Minecraft GUI 组件。未来可以通过 `ModuleSettingsRendererProvider` 替换或包装 renderer，让 Mixin 或其他渲染路径接管部分绘制，而不改变模块声明 API。

## 2. 整体结构

当前 GUI 框架主要位于：

`tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/gui/settings`

包结构按职责分层：

| 包 | 职责 |
|---|---|
| `api` | 模块接入 API，包括 context、contributor、panel 和各类模板接口。 |
| `model` | 设置分类、面板工厂和模板模型。 |
| `session` | 设置值、模块设置会话、保存结果、校验结果和设置协调器。 |
| `registry` | 设置分类注册表和注册源组合。 |
| `render` | renderer 抽象、原版 renderer、原版控件封装和渲染结果。 |
| `layout` | screen chrome、布局结果、右侧 viewport、滚动状态和行布局工具。 |
| `protocol` | 设置中心事件发布抽象和协议适配器。 |
| `screen` | Minecraft Screen、screen context 和左侧导航 widget。 |
| `module` | 设置中心模块入口，负责组装 coordinator、registry source、renderer provider 和 screen。 |

核心链路如下：

```text
TianshuClient
  ↓
TianshuSettingsModule
  ├─ SettingsCoordinator
  │   ├─ SettingsSessionRegistry
  │   └─ SettingsEventPublisher
  │       └─ SettingsProtocolAdapter
  ├─ TianshuSettingsRegistrySource
  │   ├─ ModuleSettingsRegistrySource(coreManager::managedModules)
  │   └─ BuiltinSettingsRegistrySource（仅显式示例模式启用）
  └─ VanillaModuleSettingsRendererProvider
  ↓
TianshuSettingsScreen
  ↓
TianshuSettingsRegistry
  ↓
ModuleSettingsCategory
  ↓
ModuleSettingsPanelFactory
  ↓
ModuleSettingsPanelModel
  ↓
SettingsTemplateModel
  ↓
ModuleSettingsRendererProvider
  ↓
VanillaModuleSettingsRenderer
  ↓
Minecraft 原版 Widget / GuiGraphics
```

这条链路把“设置中心模块入口”“模块注册”“设置声明”“保存事务”“协议事件”“模板模型”“原版渲染”拆成了独立层级，避免设置界面退化成一个巨大的业务 Screen。

## 3. 分类注册模型

### 3.1 `TianshuSettingsRegistry`

`TianshuSettingsRegistry` 是设置分类注册表。

它保存多个 `ModuleSettingsCategory`，并按 `order` 排序。

每个分类通常对应一个模块。

```text
一个模块 = 左侧一个分类
一个分类 = 右侧一个设置面板
一个设置面板 = 多个通用模板
```

### 3.2 `ModuleSettingsCategory`

`ModuleSettingsCategory` 表示左侧列表中的一个模块分类。

它包含：

| 字段 | 含义 |
|---|---|
| `moduleId` | 模块或分类 ID，必须稳定唯一。 |
| `title` | 左侧分类显示名称，也用于右侧标题。 |
| `description` | 右侧标题下方的说明文字。 |
| `order` | 分类排序值。 |
| `panelFactory` | 右侧面板构建函数。 |

模块不直接提供 widget，而是通过 `panelFactory` 往 `ModuleSettingsPanel` 里声明模板。

## 4. 注册源模型

### 4.1 `TianshuSettingsRegistrySource`

`TianshuSettingsRegistrySource` 是注册源抽象。

```java
public interface TianshuSettingsRegistrySource {
    void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context);
}
```

它的职责是向 registry 填充分类。

框架不要求所有分类来自同一个地方，可以组合多个 source。

### 4.2 `CompositeSettingsRegistrySource`

`CompositeSettingsRegistrySource` 负责按顺序调用多个 source。

它用于显式组合多个注册来源，例如：

```text
ModuleSettingsRegistrySource
BuiltinSettingsRegistrySource
```

当前正式设置中心入口默认只使用真实模块注册源：

```text
ModuleSettingsRegistrySource(coreManager::managedModules)
```

内置示例源只在显式示例模式或调试入口中挂载。这样可以保留模板展示能力，同时避免生产设置界面无条件出现“系统示例设置”。

### 4.3 `ModuleSettingsRegistrySource`

`ModuleSettingsRegistrySource` 从模块列表中收集 GUI contribution。

它接收：

```java
Supplier<List<TianshuManagedModule>>
```

然后筛选：

```java
module instanceof TianshuSettingsContributor
```

满足条件的模块会被调用：

```java
contributor.contributeSettings(registry, context);
```

这让模块注册体系自然成为 GUI 分类来源，同时避免 GUI 直接依赖具体模块类。

### 4.4 `BuiltinSettingsRegistrySource`

`BuiltinSettingsRegistrySource` 是内置示例和开发期 source。

它展示了模板能力，包括：

- 文本块
- 总开关
- 开关组
- 选项组
- 状态组
- 操作组
- 列表组
- 禁用项
- 滚动内容

它不承载真实业务配置，正式设置中心入口也不会默认挂载它。需要展示模板或调试 GUI 时，可以通过 `TianshuSettingsModule(coreManager, true)` 或 `TianshuSettingsScreen.createDefault()` 显式启用。真实模块应通过 `TianshuSettingsContributor` 注册自己的分类。

## 5. 模块贡献接口

### 5.1 `TianshuSettingsContributor`

模块如果希望向设置界面贡献分类，应实现：

```java
public interface TianshuSettingsContributor {
    void contributeSettings(TianshuSettingsRegistry registry, ModuleSettingsContext context);
}
```

它是 GUI 设置声明入口，不是渲染入口，也不是协议适配器。

推荐含义：

```text
TianshuManagedModule 负责生命周期
TianshuSettingsContributor 负责声明设置分类
AbstractProtocolAdapter 负责协议通信
```

一个模块可以同时实现这些角色，但它们职责不同。

## 6. 上下文模型

### 6.1 `ModuleSettingsContext`

`ModuleSettingsContext` 是模块构建设置面板时能看到的最小上下文。

当前包含：

```java
Minecraft minecraft();

SettingsCoordinator settingsCoordinator();

default SettingsSessionRegistry settingsSessions();

void showStatus(Component message, long durationMillis);
```

其中：

- `minecraft()` 只提供必要的客户端上下文。
- `settingsCoordinator()` 提供设置中心协调入口，用于保存当前、保存全部、重置当前和访问 session registry。
- `settingsSessions()` 是保留给模块注册设置会话的便捷入口，默认转发到 `settingsCoordinator().sessions()`。
- `showStatus(...)` 用于底部短时状态提示。

`ModuleSettingsContext` 不提供：
- `ClientConfig`
- `NeoForgeNativeLibBridge`
- 任意模块实现类
- 任意全局 service locator

这是故意的。

模块自己的配置、服务和协议适配器应由模块自己持有，而不是从 GUI context 反向索取。

### 6.2 `TianshuSettingsContext`

`TianshuSettingsContext` 是当前 NeoForge 客户端默认实现。

它持有 `SettingsCoordinator`，并提供：

- `Minecraft.getInstance()`
- `SettingsCoordinator`
- 通过 coordinator 暴露的 `SettingsSessionRegistry`
- 短时状态提示消息

`TianshuSettingsScreen` 会在底部显示当前 status message，并把保存当前、保存全部、重置当前等底部操作交给 `SettingsCoordinator`。

## 7. 模板模型

### 7.1 `ModuleSettingsPanel`

`ModuleSettingsPanel` 是模块声明设置模板的 fluent API。

模块通过它声明：

```text
启用开关
开关组
选项组
状态组
操作组
列表组
文本块
分隔符
```

模块不需要关心控件坐标，也不需要手动创建 `Button`、`CycleButton`、`EditBox` 或 slider。

### 7.2 `ModuleSettingsPanelModel`

`ModuleSettingsPanelModel` 是 `ModuleSettingsPanel` 的默认模型实现。

它收集模块声明的模板，并生成不可变的：

```java
List<SettingsTemplateModel>
```

`TianshuSettingsScreen` 每次重建当前页面时会：

1. 创建新的 `ModuleSettingsPanelModel`。
2. 调用选中分类的 `panelFactory`。
3. 把得到的 template list 交给 renderer。

### 7.3 `SettingsTemplateModel`

`SettingsTemplateModel` 是 renderer 消费的稳定数据模型。

当前支持：

| 模板 | 用途 |
|---|---|
| `Enable` | 单个总开关。 |
| `ToggleGroup` | 多个布尔开关。 |
| `OptionGroup` | 下拉、文本、滑条等选项。 |
| `StatusGroup` | 只读状态行。 |
| `ActionGroup` | 一个或多个操作按钮。 |
| `ListGroup` | 可选列表，可为每个 item 声明右侧操作按钮。 |
| `TextBlock` | 信息、警告、错误文本。 |
| `Separator` | 分隔空间。 |

内部 entry 包括：

- `ToggleEntry`
- `SelectEntry`
- `TextEntry`
- `SliderEntry`
- `StatusEntry`
- `ActionEntry`
- `ItemActionEntry`

`ItemActionEntry<T>` 属于 `ListGroup<T>` 的列表项级操作模型。它不是下载页专用结构，而是表达“某个列表项右侧可以有若干操作按钮”，例如下载、暂停、取消、删除、设为默认等。按钮的 action、enabled、visible 都可以基于当前 item 判断，renderer 负责把它布局成：

```text
[卡片主体信息............................] [操作] [操作] [操作]
```

### 7.4 `enabled` 与 `visible`

模板和 entry 都区分：

```text
enabled = 显示但不可交互 / 弱化显示
visible = 是否参与布局和渲染
```

因此：

```text
enabled=false：控件仍可见，但不可点击，颜色弱化
visible=false：控件不可见，也不占布局空间
```

当前公开 API 已经提供组级和条目级 `visible` 参数，例如：

```java
panel.options("advanced", title, this::settingsEditable, this::advancedVisible, group -> group
        .text("path", label, pathValue, this::settingsEditable, this::advancedVisible));
```

列表模板还提供链式：

```java
group.visible(this::hasItems);
```

这是为了区分三种常见情况：

```text
模块关闭，子选项显示但禁用
当前环境不支持，选项显示为不可用
当前平台完全不适用，选项隐藏
```

## 8. 渲染模型

### 8.1 `ModuleSettingsRendererProvider`

`ModuleSettingsRendererProvider` 是 renderer 工厂。

```java
public interface ModuleSettingsRendererProvider {
    ModuleSettingsRenderer createRenderer(ModuleSettingsContext context);
}
```

它是未来接入 Mixin 或替换渲染实现的重要边界。

### 8.2 `ModuleSettingsRenderer`

`ModuleSettingsRenderer` 消费模板模型并创建实际控件。

```java
SettingsRenderResult render(
    TianshuSettingsScreen screen,
    Font font,
    int x,
    int y,
    int width,
    SettingsViewport viewport,
    List<SettingsTemplateModel> templates
);
```

它返回 `SettingsRenderResult`，当前主要包含内容总高度，用于右侧滚动条计算。

### 8.3 `VanillaModuleSettingsRenderer`

`VanillaModuleSettingsRenderer` 是当前默认原版风格 renderer。

它负责：

- 按模板创建原版 widget。
- 绘制只读文本。
- 为不可见区域跳过 widget 创建。
- 根据 `enabled` 控制控件 active 状态。
- 根据 `visible` 决定是否渲染和占位。
- 返回内容总高度。

它不应该读取具体模块类型，也不应该保存模块配置。

### 8.4 `VanillaModuleSettingsRendererProvider`

默认 provider 返回新的 `VanillaModuleSettingsRenderer`。

未来如果 Mixin 需要注入、替换或包装原版渲染，可以优先从 provider 边界切入。

## 9. 布局与滚动模型

### 9.1 `SettingsScreenChrome`

`SettingsScreenChrome` 负责屏幕外壳绘制和主区域布局计算。

包括：

- 左右面板边框
- 面板背景
- 标题文字
- 当前分类说明
- 底部状态提示
- 右侧滚动条
- 主区域坐标计算

它让 `TianshuSettingsScreen` 不再直接堆积大量绘制和坐标代码。

### 9.2 `SettingsScreenLayout`

`SettingsScreenLayout` 是主屏幕布局结果。

它包含：

- `leftX`
- `rightX`
- `contentTop`
- `contentBottom`
- `leftWidth`
- `rightWidth`

并派生：

- `panelHeight()`
- `viewportTop()`
- `viewportBottom()`
- `containsRightPanel(...)`

### 9.3 `SettingsLayout`

`SettingsLayout` 是右侧模板布局 cursor。

renderer 每请求一行，layout 会分配：

```java
SettingsLayoutItem
```

并推进内部 `cursorY`。

这样模板只按顺序声明，renderer 不需要手写一堆散乱的 y 坐标。

### 9.4 `SettingsViewport`

`SettingsViewport` 描述右侧滚动视口。

它负责：

- 把内容坐标转换为屏幕坐标。
- 判断某个 row 是否与视口相交。
- 提供 viewport 高度。

不可见区域的控件不会被添加到 screen 中，从而避免屏幕外控件仍然响应交互。

### 9.5 `ScrollState`

`ScrollState` 保存右侧滚动状态。

包括：

- `offset`
- `contentHeight`
- `viewportHeight`

并提供：

- `maxOffset()`
- `withOffset(...)`
- `withMetrics(...)`
- `canScroll()`

## 10. 设置保存体系

当前框架已经具备 session / draft / save 基础设施。

推荐职责划分：

```text
模块拥有设置，GUI 编排设置。
```

模块负责：

- 设置项定义
- 默认值
- 当前值读取
- 草稿值管理
- 参数校验
- 写回真实配置
- 持久化
- 保存后的运行时副作用

GUI 框架负责：

- 展示设置项
- 接收用户编辑
- 管理脏状态
- 提供重置当前
- 提供保存全部
- 汇总保存结果
- 统一提示错误、成功、重启或重载需求

当前核心类型：

| 类型 | 职责 |
|---|---|
| `SettingsValue<T>` | 设置值抽象，提供 `get`、`set`、`dirty`、`valid`、`reset`、`save`。 |
| `MutableSettingsValue<T>` | 默认草稿值实现，保存后会更新 dirty 基线。 |
| `SettingsValidationResult` | 保存前校验结果。 |
| `SettingsSaveResult` | 保存结果，包含成功状态、消息、是否真的发生保存/重置变更、是否需要重启或重载，以及失败类型。 |
| `ModuleSettingsSession` | 单模块设置会话。 |
| `ModuleSettingsSessionBuilder` | 基于多个 `SettingsValue<?>` 快速构建 session。 |
| `SettingsSessionRegistry` | 聚合所有模块 session，提供 `saveAll()`、`save(moduleId)`、`reset(moduleId)`、`registerOrReplace(...)`。 |
| `SettingsCoordinator` | 设置中心协调器，负责调用 session registry、汇总结果，并在保存/重置/校验失败后通知 `SettingsEventPublisher`。 |

`SettingsCoordinator` 还提供 `dirty()`、`dirty(moduleId)`、`canSave(moduleId)` 和 `canReset(moduleId)` 查询。主屏幕会据此启用或禁用底部“保存当前”“保存全部”“重置当前”按钮，并在左侧模块列表中用黄色标记提示存在未保存修改。

`SettingsSaveResult.changed()` 表示本次操作是否真的提交或重置了修改；`FailureType` 用于区分校验失败、保存失败、缺失 session 和未知错误。协议事件中的 `savedAny` 使用这个变更语义，而不是保存完成后的 dirty 状态反推。

模块不应在每个控件 setter 里立即落盘。更推荐使用 draft/session 模式，让 GUI 能支持统一应用、取消和保存全部。

## 11. 与 coreManager 的关系

当前设置中心的组装入口是 `TianshuSettingsModule`。

它负责：

- 创建 `SettingsCoordinator`。
- 创建 `SettingsProtocolAdapter` 或 no-op publisher。
- 默认挂载 `ModuleSettingsRegistrySource`，并可通过显式参数加入 `BuiltinSettingsRegistrySource`。
- 提供 `createScreen()` 与 `openScreen()`。

`TianshuCoreManager` 在这条链路中只提供：

```java
List<TianshuManagedModule> managedModules()

ProtocolRuntime protocolRuntime()
```

GUI 通过 `ModuleSettingsRegistrySource` 读取模块快照并筛选 contributor；通过 `TianshuSettingsModule` 在组装边界读取 protocol runtime，用于构造 settings protocol adapter。

这表示：

```text
coreManager 不画 GUI
coreManager 不构建设置模板
coreManager 不保存 GUI 状态
coreManager 只提供模块注册结果和协议 runtime 的组装入口
```

这种边界可以避免 coreManager 在解耦后重新膨胀。后续如果 client 侧模块系统完成，`TianshuSettingsModule` 可以迁入新的客户端模块生命周期，`TianshuSettingsScreen` 不需要因此改变。

## 12. 与协议中心和适配器的关系

设置 GUI 注册不走协议中心。

原因是：

```text
GUI 分类和模板声明是静态/半静态结构，不是跨模块业务消息。
```

保存和重置也不靠广播驱动。设置中心先通过 `SettingsCoordinator` 同步执行 session 事务，获得明确的 `SettingsSaveResult` 后，再发布事件。

当前协议边界如下：

| 类型 | 职责 |
|---|---|
| `SettingsEventPublisher` | 设置事件发布抽象，默认可以是 no-op。 |
| `SettingsProtocolAdapter` | GUI 设置中心的协议适配器，模块 ID 为 `client.settings`。 |
| `SettingsSaveEvent` | GUI 内部保存事件模型。 |
| `SettingsResetEvent` | GUI 内部重置事件模型。 |
| `SettingsValidationFailureEvent` | GUI 内部校验失败事件模型。 |
| `SettingsEventPayload` | common 层协议 payload，实现 `ITianshuPayload`。 |
| `ProtocolTopics.SETTINGS_EVENT` | 设置中心发布的统一事件 topic。 |

当前事件链路：

```text
TianshuSettingsScreen
  ↓
SettingsCoordinator
  ↓
SettingsSessionRegistry / ModuleSettingsSession
  ↓
SettingsEventPublisher
  ↓
SettingsProtocolAdapter
  ↓
ProtocolTopics.SETTINGS_EVENT
```

推荐边界：

```text
GUI contribution：模块直接声明，不走协议
GUI save/reset：同步 session 事务，不走广播命令
GUI event：保存、重置、校验失败之后通过 adapter 发布 topic
模块业务 action：根据业务性质，可以调用模块服务或通过模块自己的 adapter 发协议信封
```

这能避免“保存按钮广播一个命令，然后希望所有模块自己响应”的不确定性，同时保留协议中心对运行时事件观察、联动和调试的价值。

## 13. 未来演进方向

优先级较高的演进包括：

1. 让真实模块逐步实现 `TianshuSettingsContributor` 并接入 `SettingsValue<T>` / `ModuleSettingsSessionBuilder`。
2. 引入更稳定的 renderer provider / chrome provider，以便 Mixin 接管部分原版绘制。
3. 增加保存结果错误定位和模块级提示聚合。
4. 通过模块自己的协议适配器发布保存后事件或运行时 reload 事件。
5. 继续打磨左侧列表、右侧分组、低分辨率布局和键盘导航。

## 14. 禁止事项

为了保持框架健壮，禁止以下做法：

1. 在 renderer 中判断具体业务模块类型。
2. 在 `TianshuSettingsScreen` 中硬编码 ASR、LLM、TTS 等模块设置。
3. 模块直接创建原版 widget 并交给 Screen。
4. 模块直接依赖右侧面板坐标和滚动状态。
5. 把 `coreManager`、`ClientConfig`、native bridge 注入 `ModuleSettingsContext` 变成全局 service locator。
6. 每个控件 setter 里直接保存配置，导致无法统一取消、应用和保存全部。
7. 把静态 GUI 注册强行包装成协议消息。
8. 为某个已知模块硬造不可复用模板。
