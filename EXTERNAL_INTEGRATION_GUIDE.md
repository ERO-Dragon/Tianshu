# 天枢外部模组联动指南

本文说明一个独立外部模组如何可选地接入天枢。天枢只提供协议中心边界与窄接口，不托管外部模组生命周期，不保存外部模组配置，也不定义外部模组业务语义。

> 本文按当前代码核对（NeoForge 1.21.1 / api 契约位于 `tianshu-api`）。文末「已知限制」列出了**没有**提供的能力，接之前先看那一节。

## 架构边界

外部模组应保持独立运行能力。天枢存在时，外部模组可以选择接入：

- 模块声明注册
- 主干能力探测
- Trigger 指令词注册
- Dialogue / IA 参与者接入（需硬性依赖 `tianshu-intake`）
- 模块状态提交与查询
- 设置页贡献（只画界面；保存由外部模组自己做）

外部模组不应依赖：

- `TianshuClient`、`TianshuCoreManager`、`ProtocolRuntime`
- broker / executor / dead letter / trace 等内部结构
- 任何 NeoForge provider 实现类

外部模组也不应要求天枢提供：

- 服务端网络注册（天枢自带的服务端组件只为自己采集事实，不向外部模组开放通道）
- 配置保存、加载、同步或校验
- 环境、背包、玩家、聊天、实体、世界等运行时快照数据
- 外部模组业务模型

需要环境数据时请自行采集。

## 依赖入口

天枢在客户端装配完成后向 NeoForge 事件总线发一个注册事件：

```java
@SubscribeEvent
public static void onTianshuIntegration(TianshuIntegrationRegisterEvent event) {
    TianshuIntegrationApi api = event.api();
}
```

- 事件类型：`com.rheinmetal.tianshu.neoforge.integration.TianshuIntegrationRegisterEvent`（在 adapter 里，因为它是一个 NeoForge 事件）
- 窄接口：`com.rheinmetal.tianshu.integration.TianshuIntegrationApi`（在 `tianshu-core`）

也可以静态探测：

```java
if (TianshuIntegrationAccess.isAvailable()) {
    TianshuIntegrationApi api = TianshuIntegrationAccess.require();
}
```

推荐用事件。静态入口只适合延迟查询或兜底：宿主关闭时会变回 unavailable，**不要永久缓存一次 `isAvailable()` 的结果**。

外部模组对天枢的编译依赖分两段：事件 `TianshuIntegrationRegisterEvent` 在 adapter（随 `tianshu` jar 发布），
需要一个能提供它的依赖；API 与契约类型在 `tianshu-core` / `tianshu-api`。运行时这两个 jar 都可能不在，
所以清单里必须写 optional。

## 调用模块方法：硬性依赖或建桥

跨模块调用只有两条合法路线，区别只在"什么时候把对目标类型的认识加载进来"：

1. **硬性依赖**：把目标模块（如 `tianshu-auxilium`）声明为 `compileOnly`，直接引用它的能力常量、payload 与对外服务接口。没有天枢时这些类不存在，所以清单里要写软依赖，并在入口做存在性判断。
2. **联动桥**：只依赖 `tianshu-api`，把天枢侧的适配单独写成一个类，只在检测到天枢（或某模块）已加载时才实例化它；主逻辑保持零依赖。

两条路线下对外可见的面只有三类：**能力名**、**载荷记录**、**明确标注为对外契约的服务接口**。不属于契约的一律不调：`tick` / `flush` / `stop` 这类生命周期方法、内部编排、记忆与提示词实现都不能出现在外部调用里。

`tianshu-api` 只承载跨模块稳定契约，不承载某个模块的业务载荷——所以"想调某个模块的方法"的正确答案永远是"依赖那个模块"，而不是"把它的类型搬进 api"。

## 模块声明注册

```java
IntegrationModuleDeclaration declaration = new IntegrationModuleDeclaration(
        "example_radar",          // moduleId
        "Example Radar",          // 显示名
        "1.0.0",                  // 版本
        Set.of(IntegrationCapability.VOICE_TRIGGER,
               IntegrationCapability.MODULE_STATUS_PROVIDER)
);
api.registerModule(declaration);
```

注销：`api.unregisterModule("example_radar")`。

`IntegrationCapability` 目前有 7 个值：`VOICE_TRIGGER`、`DIALOGUE_PARTICIPANT`、`MODULE_STATUS_PROVIDER`、`MODULE_STATUS_CONSUMER`、`GUI_RENDER_CONTRIBUTOR`、`LIFECYCLE_LISTENER`、`RESOURCE_RELOAD_LISTENER`。

声明只表示"我支持哪些联动"，不表示天枢托管你的生命周期。**声明本身目前不会被天枢校验或消费**（见「已知限制」）。

## 能力探测

```java
CoreCapabilityProbe probe = api.probe();
boolean triggerSupported = probe.supports(IntegrationCapability.VOICE_TRIGGER);
boolean coreReady = api.isCoreReady();
```

`api.isCapabilityReady(RuntimeCapability)` / `api.capabilityStatus(RuntimeCapability)` 接收的是**天枢模块自己定义**的能力（例如 `RuntimeCapability.of("llm.request")`）。外部模组通常没有自己的 `RuntimeCapability`，一般只用 `isCoreReady()` + `probe().supports(...)`；不要用 core ready 代替"某个具体功能可用"。

## Trigger 指令词注册

```java
TriggerRegistration registration = new TriggerRegistration(
        "example_radar",
        List.of("雷达", "警戒"),      // wakeWords
        List.of("扫描", "附近敌人"),  // extraWords
        TriggerCategory.INFORMATION,
        TriggerScope.CLIENT,
        true                          // dialogueEligible
);
TriggerRegistrationResult result = api.registerTrigger(registration);
```

- 更短的构造器：`(moduleId, wakeWords, extraWords)` 与 `(moduleId, wakeWords, extraWords, category, scope, dialogueEligible)`。两种词表至少要有一个非空。
- 默认投递目标为 `TriggerDeliveryTarget.defaultFor(moduleId)`；也可以显式传 `new TriggerDeliveryTarget("example_radar", "EXAMPLE_RADAR.VOICE_TRIGGER")`，该 capability 由你自己注册和处理。
- 冲突信息：`result.conflicts()` 里每条是 `TriggerConflict(word, moduleIds)`。
- 注销某模块全部词：`api.unregisterTriggers("example_radar")`。

命中结果由 IR 通过 `IR.RESULT` 发布（`IrResultPayload`，需硬性依赖 `tianshu-intake`），外部模组不需要自己拉匹配结果。

## 模块状态

提交一条轻量状态（**注意：没有 fallback 文本参数**，展示文案由 `messageKey` 对应的语言键决定）：

```java
ModuleStatus status = ModuleStatus.keyed(
        "example_radar",                              // moduleId
        "threat_overview",                            // statusType
        "example_radar.status.threat_overview",       // messageKey（语言键）
        ModuleStatusSeverity.WARNING,
        5_000L,                                       // ttlMillis
        Map.of("hostile_count", "3")                  // tags
);
api.submitModuleStatus(status);
```

查询：

```java
ModuleStatusQuery query = new ModuleStatusQuery("example_radar", "threat_overview");
List<ModuleStatus> statuses = api.queryModuleStatuses(query);
```

`moduleId` / `statusType` 传空字符串表示不按该字段过滤；两者都空时返回缓存里全部未过期状态。状态请保持轻量。

## 设置页贡献

天枢的设置页是"模块贡献页面"，不是通用 GUI 框架。外部模组在注册事件里交一个 `TianshuSettingsContributor`：

```java
event.registerSettingsContributor((registry, context) ->
        registry.registerCategory(ModuleSettingsCategory.builder("example_radar")
                .title(UiText.key("example_radar.settings.title"))
                .description(UiText.key("example_radar.settings.description"))
                .order(200)
                .panel((panel, panelContext) -> panel
                        .enable("enabled",
                                UiText.key("example_radar.settings.enabled"),
                                config::enabled,
                                config::setEnabled)
                        .options("scan", UiText.key("example_radar.settings.scan"), options -> options
                                .slider("radius",
                                        UiText.key("example_radar.settings.radius"),
                                        config::radius, 8.0D, 64.0D, config::setRadius)))
                .build()));
```

要点：

- `registry.registerCategory(ModuleSettingsCategory)` 是唯一入口；`ModuleSettingsCategory.builder(moduleId)` 提供 `title` / `description` / `order` / `panel`。
- 面板控件通过 `ModuleSettingsPanel`：`enable` / `toggles` / `options`（`select` / `text` / `slider`）/ `status` / `actions` / `compound`，另有列表、列与文本块，完整清单看这个接口。
- 草稿与保存事务由天枢的设置会话承载：`context.settingsSessions().registerOrReplace(session)` 注册你自己的 `ModuleSettingsSession`（`save()` 里写自己的配置文件）。
- `context.showStatus(UiText, durationMillis)` 可以在页面底部提示一句话。
- **天枢不保存你的配置**：`ModuleSettingsSession.save()` 里自己做落盘与校验。

`event.unregisterSettingsContributor(contributor)` 可撤销。

## Dialogue / IA 接入

需要参与对话仲裁的外部模组，用听澜的 Dialogue 契约（硬性依赖 `tianshu-intake`）。相关类型在 `com.rheinmetal.tianshu.function.ia.contract`：

- `DialogueParticipantRegisterPayload(DialogueParticipantDescriptor descriptor, long timestampMillis)`
- `DialogueParticipantUnregisterPayload(String moduleId, String participantId, boolean allParticipants, long timestampMillis)`
- `DialogueDeliveryPayload`、`DialogueArbitrationRequestPayload`、`DialogueArbitrationResultPayload`

```java
new DialogueParticipantRegisterPayload(descriptor, System.currentTimeMillis());
```

`DialogueParticipantDescriptor` 要求提供 `icon`（伙伴标记贴图）与 `holdTier`（中标后的租期档位：20s / 10s / 5s / 0s）。完整字段与仲裁规则见 `doc/function/ia/IA_外部模组仲裁接入说明.md`。

Dialogue 接入是可选能力，不接入也应能独立运行。

## 协议 Envelope 入口

需要直接走协议中心时：

```java
api.submit(envelope);
```

这只是提交入口，不提供同步 request/response 语义，也不暴露 runtime 内部。查询型需求优先用 `api.queryModuleStatuses(query)` 这类专门窄接口。

### 提交一句世界事件（AX）

要交给辅星"说一句"，需硬性依赖 `tianshu-auxilium` 才能拿到能力名与载荷类：

```java
AXWorldEventPayload payload = new AXWorldEventPayload(
        "example_radar:threat_spotted",   // 自己的命名空间，不得占用 tianshu:
        AXEventClass.IMMEDIATE,           // IMMEDIATE / DEFERRED / SILENT
        "雷达发现三名敌人接近。"           // 自己渲染好的整句
);

api.submit(EnvelopeBuilder.commandToCapability(
        "example_radar",
        AXProtocolAdapter.EVENT_INPUT_CAPABILITY,
        PayloadType.of("AX_WORLD_EVENT"),
        payload
).build());
```

`IMMEDIATE` 到达即说、`DEFERRED` 冷却后主动说、`SILENT` 只进历史。频率档位是天枢内部概念，不进载荷。AX 不渲染这句话，文案与语言由外部模组维护。

## 推荐接入流程

```java
@SubscribeEvent
public static void onTianshuIntegration(TianshuIntegrationRegisterEvent event) {
    TianshuIntegrationApi api = event.api();

    if (!api.probe().supports(IntegrationCapability.VOICE_TRIGGER)) {
        return; // 这一版天枢不支持语音触发，安静退出
    }

    api.registerModule(new IntegrationModuleDeclaration(
            "example_radar", "Example Radar", "1.0.0",
            Set.of(IntegrationCapability.VOICE_TRIGGER)));

    api.registerTrigger(new TriggerRegistration(
            "example_radar",
            List.of("雷达"),
            List.of("扫描"),
            TriggerCategory.INFORMATION,
            TriggerScope.CLIENT,
            true));

    event.registerSettingsContributor((registry, context) ->
            registry.registerCategory(ModuleSettingsCategory.builder("example_radar")
                    .title(UiText.key("example_radar.settings.title"))
                    .panel((panel, panelContext) -> panel.enable(
                            "enabled", UiText.key("example_radar.settings.enabled"),
                            config::enabled, config::setEnabled))
                    .build()));
}
```

## 兼容性原则

1. 没有天枢时仍可独立运行（依赖写成 optional，桥类只在检测到天枢时加载）。
2. 有天枢时只通过 `TianshuIntegrationApi`、协议 capability/topic 与模块**发布出来**的契约类型联动。
3. 不依赖主干内部类（`TianshuCoreManager`、`ProtocolRuntime`、broker / executor / dead letter / trace）或 provider 实现。
4. 不调不属于契约的生命周期方法（某模块的 `tick` / `flush` / `stop`）。
5. 不把主干作为配置保存或服务端安全基座。
6. 不要求主干理解外部模组业务语义。
7. 用 capability ready 判断具体能力，不要用 core ready 代替。

## 当前可用入口

- `TianshuIntegrationRegisterEvent`（adapter）
- `TianshuIntegrationApi` / `TianshuIntegrationAccess`
- `IntegrationModuleDeclaration` / `IntegrationCapability` / `CoreCapabilityProbe`
- `TriggerRegistration` / `TriggerRegistrationResult` / `TriggerConflict` / `TriggerDeliveryTarget`
- `ModuleStatus` / `ModuleStatusQuery` / `ModuleStatusSeverity`
- `TianshuSettingsContributor` / `ModuleSettingsCategory` / `ModuleSettingsPanel`（`tianshu-api`）
- `AXProtocolAdapter.EVENT_INPUT_CAPABILITY` + `AXWorldEventPayload`（需硬性依赖 `tianshu-auxilium`）
- `IrResultPayload` / Dialogue 契约（需硬性依赖 `tianshu-intake`）

## 已知限制

- **没有生命周期 / 资源刷新事件**：天枢不发 `CoreLifecycleEventPayload`、`ResourceReloadEventPayload` 这类协议事件，也没有 GUI 描述协议（`GuiContributionDescriptor` 等类型不存在）。需要感知启停时，用 `TianshuIntegrationAccess.isAvailable()` 做延迟判断。
- **版本探测是构建注入的**：`CoreCapabilityProbe.coreVersion` 报本 jar 的 mod 版本，`apiVersion` 报由 mod 版本 MAJOR 推导的 api 纪元（1.x.y → `"1"`）。可以据此判断"这一版是不是我编译时的 api 纪元"，但不要拿它做细粒度的功能分支——能力探测请用 `supports(...)`。
- **模块声明不被校验**：`registerModule` 只记录声明，天枢不会据此限制或路由。
- **设置页只画界面**：没有任何配置读写、校验或同步能力由天枢提供。
- **没有服务端通道**：天枢服务端组件只采集自己的事实，不向外部模组开放网络通道。
