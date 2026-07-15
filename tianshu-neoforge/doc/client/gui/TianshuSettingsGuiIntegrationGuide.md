# 天枢设置 GUI 接入指南

## 1. 依赖与边界

设置声明依赖 `tianshu-client`，业务能力和协议依赖 `tianshu-common`。不要在 contributor 中导入 `net.minecraft.*` 或 `net.neoforged.*`。

外部 NeoForge 模组在集成事件中注册 contributor：

```java
@SubscribeEvent
public static void onTianshuIntegration(TianshuIntegrationRegisterEvent event) {
    event.registerSettingsContributor(new ExampleSettingsContributor(settingsAccess));
}
```

Fabric 或其他版本只需要提供等价注册入口和 renderer，不需要修改 contributor。

## 2. 声明分类

```java
public final class ExampleSettingsContributor implements TianshuSettingsContributor {
    private final ExampleSettingsAccess settings;

    public ExampleSettingsContributor(ExampleSettingsAccess settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public void contributeSettings(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        ExampleSession session = new ExampleSession(settings);
        context.settingsSessions().registerOrReplace(session);
        registry.registerCategory(ModuleSettingsCategory.builder("example")
                .title(UiText.key("yourmod.gui.example.title"))
                .description(UiText.key("yourmod.gui.example.description"))
                .order(100)
                .panel((panel, ignored) -> buildPanel(panel, session))
                .build());
    }
}
```

分类 ID 和模板 ID 必须稳定。显示文字使用资源 key，不把中文或英文写进 Java。

## 3. 声明模板

```java
private void buildPanel(ModuleSettingsPanel panel, ExampleSession session) {
    panel.enable("example.enabled", UiText.key("yourmod.gui.example.enabled"), session.enabled)
            .options("example.main", UiText.key("yourmod.gui.example.section.main"), options -> options
                    .text("example.name", UiText.key("yourmod.gui.example.option.name"), session.name)
                    .select(
                            "example.mode",
                            UiText.key("yourmod.gui.example.option.mode"),
                            List.of(Mode.values()),
                            session.mode,
                            value -> UiText.key("yourmod.gui.example.mode." + value.name().toLowerCase(Locale.ROOT))
                    ))
            .status("example.status", UiText.key("yourmod.gui.example.section.status"), status -> status
                    .row("example.state", UiText.key("yourmod.gui.example.row.state"), session::stateText));
}
```

固定标签使用 `UiText.key`。`UiText.literal` 只用于动态名称、路径、数值或模块返回的运行时文本。

## 4. 草稿与保存

```java
final class ExampleSession implements ModuleSettingsSession {
    private final ExampleSettingsAccess settings;
    final MutableSettingsValue<Boolean> enabled;
    final MutableSettingsValue<String> name;
    final MutableSettingsValue<Mode> mode;

    ExampleSession(ExampleSettingsAccess settings) {
        this.settings = settings;
        enabled = new MutableSettingsValue<>(settings::enabled, settings::setEnabled);
        name = new MutableSettingsValue<>(settings::name, settings::setName,
                value -> value != null && !value.isBlank());
        mode = new MutableSettingsValue<>(settings::mode, settings::setMode, Objects::nonNull);
    }

    @Override public String moduleId() { return "example"; }

    @Override
    public boolean dirty() {
        return enabled.dirty() || name.dirty() || mode.dirty();
    }

    @Override
    public SettingsValidationResult validate() {
        return name.valid()
                ? SettingsValidationResult.successful()
                : SettingsValidationResult.failure(UiText.key("yourmod.gui.example.validation.name"));
    }

    @Override
    public SettingsSaveResult save() {
        boolean changed = dirty();
        enabled.save();
        name.save();
        mode.save();
        settings.save();
        return SettingsSaveResult.success(
                UiText.key("yourmod.gui.example.message.saved"), changed, false, false);
    }

    @Override
    public void reset() {
        enabled.reset();
        name.reset();
        mode.reset();
    }
}
```

校验必须在 setter 和 `settings.save()` 之前完成。保存失败时，模块需要恢复已写入的配置值，或让设置端口提供原子提交。

## 5. 运行时动作

按钮动作可以调用本模块公开 service，或通过协议中心发送能力请求。不得直接访问其他模块实现类。

```java
panel.actions("example.actions", UiText.key("yourmod.gui.example.section.actions"), actions -> actions
        .button(
                "example.refresh",
                UiText.key("yourmod.gui.example.action.refresh"),
                () -> refreshThroughProtocol(context)
        ));
```

耗时工作必须在模块自己的有界执行 lane 或协议 lane 中运行。完成后再通过 `ClientScheduler`/`ClientUiHost` 请求页面刷新；禁止在按钮回调、tick 或 render 中同步下载、扫描目录、索引、推理或写盘。

## 6. 外部配置端口

contributor 依赖自己定义的窄接口：

```java
public interface ExampleSettingsAccess {
    boolean enabled();
    void setEnabled(boolean enabled);
    String name();
    void setName(String name);
    Mode mode();
    void setMode(Mode mode);
    void save();
}
```

NeoForge、Fabric 或其他宿主分别实现该端口。不要把加载器配置类暴露给 contributor，也不要建立包含全部模块设置的总接口。

## 7. 生命周期

- 页面打开时创建新 session。
- 页面重建可以复用当前 session，但重新注册同一模块会替换旧 session。
- 世界退出不会修改已经保存到 `tianshu-client.toml` 的配置。
- 模块 runtime stop 后，状态 supplier 必须能返回不可用状态，不能继续引用旧世界对象。
- 外部模组卸载或关闭集成时调用 `unregisterSettingsContributor`。

## 8. 稳定性要求

- 不依赖 Screen、Widget 或 renderer 实现类。
- 不缓存 Player、Level、Entity、ItemStack 等 Minecraft 活对象。
- 不在 UI 回调中阻塞 Minecraft 主线程。
- 下载进度和高频状态更新必须合并刷新。
- 所有固定显示文本通过资源文件提供。
- 配置仍由宿主唯一文件管理；天枢 NeoForge 当前使用 `config/tianshu-client.toml`。
