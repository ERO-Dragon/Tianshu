# 天枢外部模组联动指南

本文档说明独立外部模组如何与天枢核心进行可选联动。天枢主干提供的是协议中心边界和窄接口，不托管外部模组生命周期，不保存外部模组配置，也不定义外部模组业务语义。

## 架构边界

外部模组应保持独立运行能力。天枢存在时，外部模组可以选择接入以下能力：

- 模块声明注册
- 主干能力探测
- Trigger 指令词注册
- Dialogue / IA 参与者接入
- 模块状态提交与查询
- GUI / 设置页绘制贡献
- 生命周期与资源刷新事件

外部模组不应依赖：

- `TianshuClient`
- `TianshuCoreManager`
- `ProtocolRuntime`
- NeoForge provider 实现类
- broker / executor / dead letter / trace 等内部结构

外部模组也不应要求天枢提供：

- 服务端网络注册（天枢自带的服务端组件只为自己采集事实，不向外部模组开放通道）
- 服务端权限或零信任安全基座
- 配置保存、加载、同步或校验
- 环境、背包、玩家、聊天、实体、世界等运行时快照数据
- 外部模组业务模型，例如雷达威胁、MR 锁定目标、合成节点、垃圾物品判断

外部模组如需环境数据，应自行采集和维护；天枢内部用于 AX / dynamic RAG 的运行时数据不作为公共快照能力对外提供。

## 依赖入口

外部模组通过 NeoForge 事件获取窄接口：

```java
@SubscribeEvent
public static void onTianshuIntegration(TianshuIntegrationRegisterEvent event) {
    TianshuIntegrationApi api = event.api();
}
```

事件类型：

```java
com.rheinmetal.tianshu.client.integration.TianshuIntegrationRegisterEvent
```

窄接口类型：

```java
com.rheinmetal.tianshu.integration.TianshuIntegrationApi
```

也可以通过静态访问判断当前是否可用：

```java
if (TianshuIntegrationAccess.isAvailable()) {
    TianshuIntegrationApi api = TianshuIntegrationAccess.require();
}
```

注意：推荐优先使用事件注册。静态访问仅适合延迟查询或兜底判断。宿主关闭或重载时静态入口会再次变为 unavailable，外部模组不得永久缓存一次 `isAvailable()` 的结果；需要长期持有 API 时，应跟随宿主注册/关闭生命周期更新引用。

## 调用模块方法：硬性依赖或建桥

跨模块的方法调用只有两条合法路线。两条都要求外部模组认识被调模块的契约类型，区别只在"什么时候把这份认识加载进来"：

1. **硬性依赖**：把目标模块（例如 `tianshu-auxilium`）作为 `compileOnly` 依赖编译，直接引用它的 capability 常量、payload 记录和对外服务接口，运行时由天枢提供实现。没有天枢时这些类不存在，所以这个模组必须把依赖声明成软依赖（`mods.toml` / `fabric.mod.json` 里的 optional dependency），并在自己的入口做存在性判断。
2. **联动桥**：外部模组只依赖 `tianshu-api`，把天枢那一侧的适配写成独立类，只在检测到天枢（或某模块）已加载时才实例化它。桥类被封在"没装天枢就不会被类加载"的位置，主逻辑因此保持零依赖。

两条路线下对外可见的面只有三类：**能力名**（capability id）、**载荷记录**（payload）、以及**明确标注为对外契约的服务接口**。不属于契约的一律不调：`tick` / `flush` / `stop` 这类生命周期方法、内部编排、记忆与提示词实现都不能出现在外部调用里。

`tianshu-api` 只承载跨模块稳定契约（协议常量、对话模型记录、装配端口），不承载某个模块的业务载荷：事件描述、记忆结构、提示词模板都由拥有它的模块自己发布。所以"想调某个模块的方法"的正确答案永远是"依赖那个模块"，而不是"把它的类型搬进 api"。

## 模块声明注册

外部模组可以声明自己支持哪些天枢联动能力：

```java
IntegrationModuleDeclaration declaration = new IntegrationModuleDeclaration(
        "example_radar",
        "Example Radar",
        "1.0.0",
        Set.of(
                IntegrationCapability.VOICE_TRIGGER,
                IntegrationCapability.MODULE_STATUS_PROVIDER,
                IntegrationCapability.GUI_RENDER_CONTRIBUTOR
        )
);

api.registerModule(declaration);
```

注销：

```java
api.unregisterModule("example_radar");
```

模块声明只表示联动能力，不表示天枢托管该模组生命周期。

## 能力探测

查询主干能力：

```java
CoreCapabilityProbe probe = api.probe();
boolean supportsVoice = probe.supports(IntegrationCapability.VOICE_TRIGGER);
```

区分核心 ready 和具体能力 ready：

```java
boolean coreReady = api.isCoreReady();
boolean llmReady = api.isCapabilityReady(LlmRuntimeCapabilities.TASK);
RuntimeCapabilityStatus status = api.capabilityStatus(LlmRuntimeCapabilities.TASK);
```

外部模组不要只依赖 core ready 判断具体功能是否可用。

## Trigger 指令词注册

启动期注册热词和指令词：

```java
TriggerRegistration registration = new TriggerRegistration(
        "example_radar",
        List.of("雷达", "警戒"),
        List.of("扫描", "附近敌人"),
        TriggerCategory.INFORMATION,
        TriggerScope.CLIENT,
        true
);

TriggerRegistrationResult result = api.registerTrigger(registration);
```

注销某模块全部指令词：

```java
api.unregisterTriggers("example_radar");
```

注册结果中可能包含冲突信息：

```java
for (TriggerConflict conflict : result.conflicts()) {
    String word = conflict.word();
    List<String> moduleIds = conflict.moduleIds();
}
```

默认投递目标为：

```java
TriggerDeliveryTarget.defaultFor(moduleId)
```

也可以在注册时显式声明 capability：

```java
new TriggerDeliveryTarget("example_radar", "EXAMPLE_RADAR.VOICE_TRIGGER")
```

该 capability 由外部模组自己注册和处理。

命中结果的行为以 IR 发布的证据为准，外部模组不需要自己拉取匹配结果。`IR.RESULT` 上的 `IrResultPayload` 携带：

- `repairedText` / `normalizedText` / `rawText`
- `objectMatches`：每个命中具名对象一条（`spokenWord`、`registryId`、`registryKind`、`languageKey`）
- `moduleMatches`：每个命中模块触发词一条（`moduleId`、`spokenWord`、`registeredWord`）
- `turnId` / `sessionId` / `timestampMillis`

对话场景下，IA 选出的 owner 会通过 `DialogueDeliveryPayload` 收到同一份 `objectMatches` / `moduleMatches`，以及世界侧的 `displayNames`（维度、手持、实体）。

## 模块状态

外部模组可以提交轻量状态，供天枢、GUI 或 Dialogue/IA 使用。玩家可见语言内容优先提供资源键；`fallbackMessage` 只是资源缺失时的降级文本：

```java
ModuleStatus status = ModuleStatus.keyed(
        "example_radar",
        "threat_overview",
        "example_radar.status.threat_overview",
        "Radar warning",
        ModuleStatusSeverity.WARNING,
        5000L,
        Map.of("hostile_count", "3", "nearest_distance", "12")
);

api.submitModuleStatus(status);
```

查询模块状态：

```java
ModuleStatusQuery query = new ModuleStatusQuery("example_radar", "threat_overview");
List<ModuleStatus> statuses = api.queryModuleStatuses(query);
```

`moduleId` 或 `statusType` 传空字符串表示不按该字段过滤；两者都为空时查询当前缓存中的全部未过期状态。模块状态应保持轻量，不应承载复杂业务模型或大型数据。

## 设置页贡献

外部模组可以通过注册事件贡献设置页绘制内容：

```java
event.registerSettingsContributor((registry, context) -> {
    registry.page("example_radar", "雷达")
            .section("general", "基础设置")
            .toggle("enabled", "启用雷达", true)
            .slider("radius", "扫描半径", 8, 64, 32);
});
```

主干只负责绘制框架与交互回传，不负责保存配置。外部模组需要自己保存、加载、校验和同步配置。

如果需要协议级 GUI 描述，可使用：

- `GuiContributionDescriptor`
- `GuiElementDescriptor`
- `GuiContributionPayload`
- `GuiActionEventPayload`

GUI action 类型包括：

- `CLICK`
- `TOGGLE_CHANGED`
- `SLIDER_CHANGED`
- `LIST_SELECTION_CHANGED`
- `TEXT_SUBMITTED`
- `OPEN_REQUEST`
- `CLOSE_REQUEST`

## Dialogue / IA 接入

需要参与对话仲裁的外部模组可以通过 Dialogue payload 契约注册参与者。相关类型包括：

- `DialogueParticipantRegisterPayload`
- `DialogueParticipantUnregisterPayload`
- `DialogueDeliveryPayload`
- `DialogueArbitrationRequestPayload`
- `DialogueArbitrationResultPayload`

参与者注册 payload 支持携带能力摘要：

```java
new DialogueParticipantRegisterPayload(descriptor, "处理雷达威胁查询", System.currentTimeMillis());
```

`DialogueParticipantDescriptor` 要求提供 `icon`（伙伴标记贴图）与 `holdTier`（中标后的租期档位：20s / 10s / 5s / 0s），完整字段与仲裁规则见 `doc/function/ia/IA_外部模组仲裁接入说明.md`。

Dialogue / IA 接入是可选能力。外部模组不接入也应能独立运行。

## 生命周期与资源刷新事件

生命周期 payload：

- `CoreLifecycleEventPayload`
- `CoreLifecycleEventType`

资源刷新 payload：

- `ResourceReloadEventPayload`
- `ResourceReloadEventType`

常见生命周期事件：

- `CORE_READY`
- `CORE_SHUTDOWN`
- `MODULE_REGISTRATION_OPEN`
- `MODULE_REGISTRATION_CLOSED`
- `CLIENT_WORLD_ENTER`
- `CLIENT_WORLD_LEAVE`
- `PLAYER_LOGIN`
- `PLAYER_LOGOUT`
- `CAPABILITY_CHANGED`

常见资源刷新事件：

- `RESOURCE_PACK_RELOAD`
- `LANGUAGE_RELOAD`
- `RECIPE_RELOAD`
- `TAG_RELOAD`
- `CLIENT_CONFIG_RELOAD`

## 协议 Envelope 入口

需要进行协议中心通信时，外部模组可以通过窄接口提交 envelope：

```java
api.submit(envelope);
```

查询型能力应优先使用主干提供的专门窄接口，例如：

```java
api.queryModuleStatuses(query);
```

这只是协议中心提交入口，不代表可以直接访问主干内部 runtime 实现，也不提供同步 envelope request 语义。

### 提交一句世界事件（AX）

外部模组可以把自己世界里刚发生的事交给 AX 说，或写进它的历史。这条路径要硬性依赖 `tianshu-auxilium`，才能拿到能力名与载荷类（桥的写法见"调用模块方法：硬性依赖或建桥"）：

```java
AXWorldEventPayload payload = new AXWorldEventPayload(
        "example_radar:threat_spotted",   // 自己的命名空间；不得占用 tianshu:
        AXEventClass.IMMEDIATE,           // IMMEDIATE / DEFERRED / SILENT 三选一
        "雷达发现三名敌人接近。"           // 自己渲染好的整句
);

api.submit(EnvelopeBuilder.commandToCapability(
        "example_radar",
        AXProtocolAdapter.EVENT_INPUT_CAPABILITY,
        PayloadType.AX_WORLD_EVENT,
        payload
).build());
```

行为决定落地方式：`IMMEDIATE` 到达即说、`DEFERRED` 冷却后主动说一句、`SILENT` 只进历史。频率（`RARE`/`NORMAL`/`FREQUENT`）是天枢内部概念，不进载荷；同一 kind 短时间反复发生仍会被内部降档。AX 不渲染这句话，文案与语言由外部模组自己维护。契约细节见《AX 协议中心》§4。

## 推荐接入流程

```java
@SubscribeEvent
public static void onTianshuIntegration(TianshuIntegrationRegisterEvent event) {
    TianshuIntegrationApi api = event.api();

    api.registerModule(new IntegrationModuleDeclaration(
            "example_radar",
            "Example Radar",
            "1.0.0",
            Set.of(
                    IntegrationCapability.VOICE_TRIGGER,
                    IntegrationCapability.MODULE_STATUS_PROVIDER,
                    IntegrationCapability.GUI_RENDER_CONTRIBUTOR
            )
    ));

    api.registerTrigger(new TriggerRegistration(
            "example_radar",
            List.of("雷达"),
            List.of("扫描"),
            TriggerCategory.INFORMATION,
            TriggerScope.CLIENT,
            true
    ));

    event.registerSettingsContributor((registry, context) -> {
        registry.page("example_radar", "雷达");
    });
}
```

## 兼容性原则

外部模组应遵守：

1. 没有天枢时仍可独立运行（硬性依赖写成软依赖，桥类只在检测到天枢时加载）。
2. 有天枢时只通过 `TianshuIntegrationApi`、协议 capability/topic 与模块**发布出来**的契约类型联动。
3. 不依赖主干内部类（`TianshuCoreManager`、`ProtocolRuntime`、broker / executor / dead letter / trace）或 NeoForge provider 实现。
4. 不调不属于契约的生命周期方法，例如某个模块的 `tick` / `flush` / `stop`。
5. 不把主干作为配置保存或服务端安全基座。
6. 不要求主干理解外部模组业务语义。
7. 使用 capability ready 判断具体能力是否可用，不要用 core ready 代替。

## 当前推荐的稳定入口

- `TianshuIntegrationRegisterEvent`
- `TianshuIntegrationApi`
- `TianshuIntegrationAccess`
- `IntegrationModuleDeclaration`
- `TriggerRegistration`
- `ModuleStatus`
- `ModuleStatusQuery`
- `GuiContributionDescriptor`
- `CoreLifecycleEventPayload`
- `ResourceReloadEventPayload`
- `AXProtocolAdapter.EVENT_INPUT_CAPABILITY` + `AXWorldEventPayload`（AX 事件型入口，需硬性依赖 `tianshu-auxilium`）
