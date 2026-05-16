# 天枢设置 GUI 接入说明：每个模块自己的设置面板

这份文档放在 GUI 框架文档旁边，是给各个模块接入设置界面时看的。

先记住一个比喻：

- **GUI 框架**：天枢设置界面的装修队。它负责房间布局、墙面、滚动条、按钮样式、列表样式、标题和状态提示。
- **模块**：住在不同房间的人。比如 ASR、LLM、TTS、UI 桥、聊天助手。
- **设置贡献者**：每个模块告诉装修队“我这个房间要摆哪些通用家具”。它不自己砌墙，不自己画按钮。
- **模板**：通用家具。比如开关组、选项组、状态组、操作组、列表组、文本块。
- **设置会话**：用于暂存修改和统一保存的草稿本。模块拥有真实设置，GUI 负责协调保存、重置、校验和结果提示。

模块接入设置 GUI 的目标不是自己绘制界面，而是把自己的设置声明成框架认识的通用模板。这样以后 GUI 风格、布局、滚动、Mixin 接入变化时，模块代码不用跟着重写。

## 1. 模块什么时候需要接入 GUI

如果模块有下面任意需求，就可以接入：

- 需要在设置界面中显示自己的启用状态。
- 需要提供开关、选项、文本、滑条、列表。
- 需要显示当前运行状态。
- 需要提供刷新、应用、重载、中断等按钮。
- 需要让用户修改模块配置。
- 需要在未来统一保存体系中参与“保存全部”。

如果模块完全没有可配置项，也可以暂时不接入。

## 2. 接入入口在哪里

模块需要实现：

```java
TianshuSettingsContributor
```

接口位置：

`tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/gui/settings/api/TianshuSettingsContributor.java`

常用导入：

```java
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.api.TextBlockLevel;
import com.rheinmetal.tianshu.client.gui.settings.api.TianshuSettingsContributor;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
```

当前接口：

```java
public interface TianshuSettingsContributor {
    void contributeSettings(TianshuSettingsRegistry registry, ModuleSettingsContext context);
}
```

一般模块会同时实现：

```java
TianshuManagedModule
TianshuSettingsContributor
```

示意：

```java
public final class MyModule implements TianshuManagedModule, TianshuSettingsContributor {
    @Override
    public String moduleId() {
        return "module.my_module";
    }

    @Override
    public void contributeSettings(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        registry.registerCategory(ModuleSettingsCategory.builder(moduleId())
                .title(Component.literal("我的模块"))
                .description(Component.literal("这里是我的模块设置"))
                .order(100)
                .panel((panel, panelContext) -> panel
                        .text("intro", Component.literal("这是一个模块设置面板。"), TextBlockLevel.INFO))
                .build());
    }
}
```

只要模块实例已经注册到 `TianshuModuleHost`，设置界面打开时就会通过 `ModuleSettingsRegistrySource` 自动收集实现了 `TianshuSettingsContributor` 的模块。

当前设置中心本身由 `TianshuSettingsModule` 组装并打开。普通业务模块不需要实例化 `TianshuSettingsModule`，也不需要知道 Screen 的创建细节。后续 client 模块生命周期继续重构时，业务模块仍只保留 contributor 声明即可。

## 3. 不需要自己调用 Screen

模块不要这样做：

```java
Minecraft.getInstance().setScreen(...);
```

也不要在模块里创建：

```java
Button
CycleButton
EditBox
GuiGraphics
Screen
```

模块应该只声明模板。

GUI 框架负责：

- 左侧分类列表
- 右侧面板布局
- 滚动条
- 控件坐标
- 控件宽度
- 可见区域判断
- 原版控件创建
- 禁用态显示
- 状态提示

## 4. 注册一个分类

每个模块通常注册一个分类。

```java
registry.registerCategory(ModuleSettingsCategory.builder("module.my_module")
        .title(Component.literal("我的模块"))
        .description(Component.literal("模块设置说明"))
        .order(100)
        .panel(this::buildSettingsPanel)
        .build());
```

推荐规则：

| 项 | 建议 |
|---|---|
| `moduleId` | 使用模块自身稳定 ID，例如 `module.llm`、`module.tts`。 |
| `title` | 给用户看的短标题。 |
| `description` | 一句话说明这个模块的设置用途。 |
| `order` | 用于左侧排序。核心模块可以靠前，扩展模块靠后。 |
| `panel` | 只声明模板，不做复杂业务流程。 |

`panel` 可以写成单独方法：

```java
private void buildSettingsPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
    panel.text("intro", Component.literal("这里是设置内容。"), TextBlockLevel.INFO);
}
```

## 5. 模板怎么用

### 5.1 文本块

文本块用于说明、警告或错误提示。

```java
panel.text("intro", Component.literal("这个模块负责处理某项能力。"), TextBlockLevel.INFO);
```

级别包括：

```text
INFO
WARNING
ERROR
```

适合放：

- 模块说明
- 风险提示
- 不可用原因
- 调试信息

### 5.2 总开关

```java
panel.enable(
        "enabled",
        Component.literal("启用模块"),
        this::isEnabled,
        this::setEnabled
);
```

含义：

- getter 读取当前值。
- setter 接收用户选择的新值。

当前 setter 会立即被控件调用。推荐绑定 `MutableSettingsValue` 这类草稿值，而不是直接落盘。这样底部“保存当前”“保存全部”“重置当前”才能准确启用，左侧模块列表也能显示未保存修改标记。

### 5.3 开关组

```java
panel.toggles("features", Component.literal("功能开关"), group -> group
        .toggle("feature.a", Component.literal("功能 A"), this::isFeatureAEnabled, this::setFeatureAEnabled)
        .toggle("feature.b", Component.literal("功能 B"), this::isFeatureBEnabled, this::setFeatureBEnabled)
);
```

如果整个组需要禁用：

```java
panel.toggles("features", Component.literal("功能开关"), this::isModuleEnabled, group -> group
        .toggle("feature.a", Component.literal("功能 A"), this::isFeatureAEnabled, this::setFeatureAEnabled)
);
```

如果单项需要禁用：

```java
group.toggle(
        "locked",
        Component.literal("锁定开关"),
        this::isLockedEnabled,
        this::setLockedEnabled,
        () -> false
);
```

### 5.4 选项组

选项组包含下拉、文本输入、滑条。

```java
panel.options("options", Component.literal("选项"), group -> group
        .select(
                "mode",
                Component.literal("模式"),
                List.of("默认", "紧凑", "详细"),
                this::mode,
                Component::literal,
                this::setMode
        )
        .text(
                "name",
                Component.literal("名称"),
                this::name,
                this::setName
        )
        .slider(
                "ratio",
                Component.literal("比例"),
                this::ratio,
                0.0D,
                1.0D,
                this::setRatio
        )
);
```

注意：

- `select` 的 values 应该是稳定的小集合。
- `text` 不适合承载超长文本。
- `slider` 当前使用 `double`，模块自己负责解释精度和单位。

### 5.5 状态组

状态组用于只读展示。

```java
panel.status("runtime", Component.literal("运行状态"), group -> group
        .row("state", Component.literal("状态"), () -> Component.literal(isReady() ? "就绪" : "未就绪"))
        .row("phase", Component.literal("阶段"), () -> Component.literal(currentPhase()))
);
```

适合显示：

- 模块是否 ready
- 当前模型名
- 当前运行阶段
- 队列数量
- 最后错误摘要

状态组不应该承担复杂交互。

### 5.6 操作组

操作组用于按钮。

```java
panel.actions("actions", Component.literal("操作"), group -> group
        .button("refresh", Component.literal("刷新"), this::refresh)
        .button("apply", Component.literal("应用"), SettingsButtonStyle.PRIMARY, this::apply)
        .button("danger", Component.literal("危险操作"), SettingsButtonStyle.DANGER, this::dangerousAction)
);
```

按钮风格：

```text
NORMAL
PRIMARY
DANGER
```

建议：

- 普通刷新用 `NORMAL`。
- 推荐主操作用 `PRIMARY`。
- 破坏性、打断、清理类操作用 `DANGER`。

如果按钮对应跨模块业务，按钮 action 可以调用模块自己的协议适配器，由适配器发信给协议中心。

### 5.7 列表组

```java
panel.list("models", Component.literal("模型列表"), group -> group
        .items(this::availableModels)
        .label(Component::literal)
        .selected(this::selectedModel)
        .onSelect(this::selectModel)
        .emptyText(Component.literal("没有可用模型"))
);
```

适合：

- 模型列表
- 设备列表
- 配置预设
- 语音资源列表
- 可选后端列表

列表内容应尽量来自模块自己的快照，不要在 getter 里做重 IO。

列表组也支持每个 item 右侧声明操作按钮，适合下载、暂停、取消、删除、选择默认项等“基于当前列表项”的动作：

```java
panel.list("models", Component.literal("模型列表"), group -> group
        .items(this::availableModels)
        .label(model -> Component.literal(model.displayName()))
        .selected(this::selectedModel)
        .onSelect(this::selectModel)
        .itemActions((model, actions) -> actions
                .button("download", Component.literal("下载"), this::downloadModel, modelItem -> !isDownloaded(modelItem))
                .button("pause", Component.literal("暂停"), this::pauseDownload, this::isDownloading)
                .button("cancel", Component.literal("取消"), SettingsButtonStyle.DANGER, this::cancelDownload, this::isDownloading)
                .button("delete", Component.literal("删除"), SettingsButtonStyle.DANGER, this::deleteModel, this::isDownloaded))
        .emptyText(Component.literal("没有可用模型"))
);
```

`itemActions` 的按钮 action、enabled、visible 都基于当前 item 判断。它不是下载页专用能力，而是通用“列表项操作”能力，ASR、TTS 或其他模块都可以复用。

## 6. enabled 和 visible 怎么理解

框架内部区分：

```text
enabled = 显示但不可操作
visible = 完全隐藏，不占布局空间
```

当前公开 API 已经同时支持 `enabled` 和 `visible`。

例如：

```java
panel.options("advanced", Component.literal("高级选项"), this::isAdvancedEditable, group -> ...);
```

当 `enabled=false` 时，用户仍能看到这些选项，但不能编辑。

适合：

- 模块未启用时，子选项灰掉。
- 引擎未启动时，运行时操作灰掉。
- 某项由服务器或配置文件锁定时，显示但不可改。

`visible` 用于：

- 平台不支持时隐藏。
- 开发模式下才显示。
- 某功能包不存在时不显示。

组级隐藏示例：

```java
panel.options("advanced", Component.literal("高级选项"), this::isEditable, this::isAdvancedVisible, group -> group
        .text("path", Component.literal("路径"), pathValue));
```

条目级隐藏示例：

```java
group.toggle("debug", Component.literal("调试模式"), debugValue, this::isEditable, this::isDebugVisible);
```

列表级隐藏示例：

```java
panel.<String>list("models", Component.literal("模型列表"), group -> group
        .items(this::availableModels)
        .label(Component::literal)
        .visible(() -> !availableModels().isEmpty()));
```

## 7. 状态提示怎么用

`ModuleSettingsContext` 提供：

```java
Minecraft minecraft();

SettingsCoordinator settingsCoordinator();

default SettingsSessionRegistry settingsSessions();

void showStatus(Component message, long durationMillis);
```

模块通常只需要：

- 用 `settingsSessions().registerOrReplace(...)` 注册自己的设置会话。
- 用 `showStatus(...)` 显示轻量操作后的底部提示。

`settingsSessions()` 是兼容和便捷入口，内部来自 `settingsCoordinator().sessions()`。普通模块不应绕过 GUI 框架自己保存全部或重置其他模块。

```java
context.showStatus(Component.literal("已刷新"), 3000);
```

注意：

- 这是 GUI 底部短提示，不是日志系统。
- 不要用它刷高频状态。
- 长时间任务应该通过状态组展示状态，或通过协议/事件更新模块状态。

## 8. 配置保存应该怎么做

当前推荐采用 draft/session 模式：

```text
真实配置 → 打开界面时复制成 SettingsValue 草稿 → 用户编辑草稿 → 点击保存 → 模块提交草稿
```

最小示例：

```java
private final MutableSettingsValue<Boolean> enabledValue = new MutableSettingsValue<>(this::isEnabled, this::setEnabled);
private final MutableSettingsValue<String> modeValue = new MutableSettingsValue<>(this::mode, this::setMode);

private void buildSettingsPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
    ModuleSettingsSession session = new ModuleSettingsSessionBuilder(moduleId())
            .values(enabledValue, modeValue)
            .successMessage(Component.literal("模块设置已保存"))
            .validationFailureMessage(Component.literal("模块设置存在无效值"))
            .build();

    context.settingsSessions().registerOrReplace(session);

    panel.enable("enabled", Component.literal("启用模块"), enabledValue)
            .options("options", Component.literal("基础设置"), group -> group
                    .select("mode", Component.literal("模式"), List.of("默认", "紧凑", "详细"), modeValue, Component::literal));
}
```

如果某些设置保存后需要重载运行时：

```java
ModuleSettingsSession session = new ModuleSettingsSessionBuilder(moduleId())
        .values(enabledValue, modeValue)
        .requiresReload(true)
        .build();
```

如果需要重启游戏：

```java
ModuleSettingsSession session = new ModuleSettingsSessionBuilder(moduleId())
        .values(nativeBackendValue)
        .requiresRestart(true)
        .build();
```

`SettingsValue<T>` 负责：

- `get()` 提供当前草稿值。
- `set(...)` 接收控件修改。
- `dirty()` 判断是否有未保存修改。
- `valid()` 提供保存前校验。
- `reset()` 丢弃草稿修改。
- `save()` 写回真实配置并更新 dirty 基线。

GUI 负责：

- 保存当前。
- 保存全部。
- 重置当前。
- 汇总保存结果。
- 显示成功、失败、重启或重载提示。
- 根据 dirty 状态启用或禁用底部保存/重置按钮。
- 在左侧模块列表中标记有未保存修改的模块。
- 在保存、重置或校验失败后发布设置事件。

`SettingsSaveResult.changed()` 表示这次保存或重置是否真的处理了修改；没有 dirty 内容时，保存会返回成功但 unchanged 的结果。`SettingsSaveResult.FailureType` 会区分校验失败、保存失败、缺失 session 和未知错误。设置中心只把校验失败发布为校验失败事件，避免把所有失败都混成同一种协议语义。

不推荐在控件 setter 里直接保存文件：

```java
private void setEnabled(boolean enabled) {
    config.setEnabled(enabled);
    config.save();
}
```

这种写法会导致：

- 无法取消修改。
- 无法保存全部。
- 无法统一校验。
- 无法汇总保存错误。
- 滑条拖动可能频繁落盘。

职责划分：

```text
模块拥有设置，GUI 编排设置。
```

模块负责：

- 配置字段
- 默认值
- 草稿值
- 校验规则
- 保存到文件
- 是否需要重启/重载

GUI 负责：

- 保存全部
- 重置当前
- 脏状态显示
- 保存结果汇总

## 9. 操作按钮和协议中心的关系

GUI 分类注册不走协议中心。

因为分类和模板是静态/半静态结构，不是业务消息。

保存和重置也不靠广播驱动。点击“保存当前”“保存全部”“重置当前”时，设置中心先通过 `SettingsCoordinator` 同步调用对应模块的 `ModuleSettingsSession`，得到明确的成功、失败、重启或重载结果，然后再发布设置事件。

当前设置中心会通过 `SettingsProtocolAdapter` 向 `ProtocolTopics.SETTINGS_EVENT` 发布：

- 保存事件。
- 重置事件。
- 校验失败事件。

模块作者应该把这个 topic 理解为“保存之后发生了什么”的事件流，而不是“请你开始保存”的命令流。

按钮 action 可以根据业务需要走模块自己的协议适配器。例如：

```java
panel.actions("runtime", Component.literal("运行时"), group -> group
        .button("interrupt", Component.literal("中断当前任务"), SettingsButtonStyle.DANGER, () -> adapter.sendInterrupt(...))
);
```

推荐边界：

```text
声明设置界面：实现 TianshuSettingsContributor
注册设置会话：使用 context.settingsSessions().registerOrReplace(...)
模块内部配置保存：模块自己的 SettingsValue / ModuleSettingsSession
保存后事件观察：监听 SETTINGS_EVENT，但不要把它当保存命令
跨模块业务通信：使用模块自己的 AbstractProtocolAdapter
```

不要为了注册 GUI 分类去发协议信封，也不要让模块等待设置中心广播后才保存自己的配置。

## 10. 一个完整示例

```java
public final class ExampleModule implements TianshuManagedModule, TianshuSettingsContributor {
    private boolean enabled = true;
    private String mode = "默认";
    private double ratio = 0.5D;
    private final MutableSettingsValue<Boolean> enabledValue = new MutableSettingsValue<>(this::isEnabled, this::setEnabled);
    private final MutableSettingsValue<String> modeValue = new MutableSettingsValue<>(this::mode, this::setMode);
    private final MutableSettingsValue<Double> ratioValue = new MutableSettingsValue<>(this::ratio, this::setRatio, value -> value != null && value >= 0.0D && value <= 1.0D);

    @Override
    public String moduleId() {
        return "module.example";
    }

    @Override
    public void contributeSettings(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        registry.registerCategory(ModuleSettingsCategory.builder(moduleId())
                .title(Component.literal("示例模块"))
                .description(Component.literal("展示模块如何接入设置 GUI"))
                .order(100)
                .panel(this::buildSettingsPanel)
                .build());
    }

    private void buildSettingsPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
        ModuleSettingsSession session = new ModuleSettingsSessionBuilder(moduleId())
                .values(enabledValue, modeValue, ratioValue)
                .successMessage(Component.literal("示例模块设置已保存"))
                .validationFailureMessage(Component.literal("示例模块设置存在无效值"))
                .build();

        context.settingsSessions().registerOrReplace(session);

        panel.text("intro", Component.literal("模块只声明模板，框架负责绘制。"), TextBlockLevel.INFO)
                .enable("enabled", Component.literal("启用模块"), enabledValue)
                .options("options", Component.literal("基础选项"), enabledValue::get, group -> group
                        .select("mode", Component.literal("模式"), List.of("默认", "紧凑", "详细"), modeValue, Component::literal)
                        .slider("ratio", Component.literal("比例"), ratioValue, 0.0D, 1.0D))
                .status("status", Component.literal("状态"), group -> group
                        .row("enabled", Component.literal("启用状态"), () -> Component.literal(enabledValue.get() ? "已启用" : "已关闭"))
                        .row("dirty", Component.literal("修改状态"), () -> Component.literal(session.dirty() ? "有未保存修改" : "无修改")))
                .actions("actions", Component.literal("操作"), group -> group
                        .button("refresh", Component.literal("刷新"), () -> context.showStatus(Component.literal("已刷新示例模块"), 3000)));
    }

    private boolean isEnabled() {
        return enabled;
    }

    private void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private String mode() {
        return mode;
    }

    private void setMode(String mode) {
        this.mode = mode;
    }

    private double ratio() {
        return ratio;
    }

    private void setRatio(double ratio) {
        this.ratio = ratio;
    }
}
```

这个示例展示了：

- 一个模块注册一个分类。
- 模块声明模板，而不是绘制 widget。
- 子选项可以随总开关禁用。
- 状态组可以读取模块当前状态。
- 操作按钮可以显示状态提示。

## 11. 常见错误

### 11.1 在模块里手动创建 widget

不要这样：

```java
new Button(...)
new EditBox(...)
```

应该用模板：

```java
panel.actions(...)
panel.options(...)
```

### 11.2 在 setter 里频繁保存文件

不要这样：

```java
value -> {
    config.setRatio(value);
    config.save();
}
```

短期可写入内存字段，长期应接入 settings session。

### 11.3 从 GUI context 里索取核心对象

不要期待：

```java
context.coreManager()
context.clientConfig()
context.nativeLibBridge()
```

模块需要的依赖应该由模块构造或生命周期注入，而不是从 GUI 反向获取。

### 11.4 为某个模块硬造模板

如果一个需求只能服务一个模块，优先考虑用已有模板组合表达。

只有多个模块都需要，并且语义稳定时，才新增模板类型。

### 11.5 把 GUI 注册走协议中心

不要这样设计：

```text
模块发信封告诉 GUI 我要注册分类
```

GUI 注册是静态声明，直接通过 contributor 更清晰。

## 12. 推荐接入顺序

一个模块接入时，建议按这个顺序：

1. 实现 `TianshuSettingsContributor`。
2. 注册一个 `ModuleSettingsCategory`。
3. 先加 `TextBlock` 和 `StatusGroup`，确认分类能显示。
4. 再加 `Enable`、`ToggleGroup`、`OptionGroup`。
5. 操作按钮先做轻量 `showStatus`。
6. 如果需要跨模块动作，再通过自己的协议适配器发信。
7. 等 settings session 完成后，再把真实配置保存迁入统一保存体系。

这样可以避免一开始就把 GUI、配置保存、协议通信、运行时重载全部揉在一起。

## 13. 当前限制

当前 GUI 框架仍在打磨阶段，需要注意：

1. 内置示例 source 已从正式设置中心默认入口移除；只有显式示例模式或调试入口会挂载。
2. 列表组还没有 per-item enabled / visible。
3. renderer 是默认 vanilla 实现，Mixin 接入边界已经预留但尚未正式实现。
4. renderer 已对常见 null 数据源和动态 supplier 异常做基础防御，但模块仍应避免在 getter、labeler、items supplier 中执行重 IO 或抛出业务异常。
5. `TianshuSettingsModule` 目前作为设置中心组装入口存在，后续可以迁入新的 client 模块生命周期。
6. Gradle wrapper 当前不可用时，可能无法通过命令行完整编译，只能依赖 IDE 诊断。

## 14. 最重要的一句话

模块接入设置 GUI 时，请记住：

```text
模块声明自己需要什么设置，GUI 决定这些设置怎么显示。
```

设置中心现在再补上一句：

```text
模块拥有设置，GUI 编排保存。
```

保存完成后的事实可以发布到协议中心，但保存动作本身仍由模块 session 明确执行。
