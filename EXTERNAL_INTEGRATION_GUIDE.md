# 天枢外部模组联动指南

本文档说明独立外部模组如何与天枢核心进行可选联动。天枢主干提供的是协议中心边界和窄接口，不托管外部模组生命周期，不保存外部模组配置，也不定义外部模组业务语义。

## 架构边界

外部模组应保持独立运行能力。天枢存在时，外部模组可以选择接入以下能力：

- 模块声明注册
- 主干能力探测
- VoiceTrigger 指令词注册
- Dialogue / IA 参与者接入
- 状态摘要提交与查询
- GUI / 设置页绘制贡献
- 生命周期与资源刷新事件

外部模组不应依赖：

- `TianshuClient`
- `TianshuCoreManager`
- `ProtocolRuntime`
- NeoForge provider 实现类
- broker / executor / dead letter / trace 等内部结构

外部模组也不应要求天枢提供：

- 服务端网络注册
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

注意：推荐优先使用事件注册。静态访问仅适合延迟查询或兜底判断。

## 模块声明注册

外部模组可以声明自己支持哪些天枢联动能力：

```java
IntegrationModuleDeclaration declaration = new IntegrationModuleDeclaration(
        "example_radar",
        "Example Radar",
        "1.0.0",
        Set.of(
                IntegrationCapability.VOICE_TRIGGER,
                IntegrationCapability.STATE_SUMMARY_PROVIDER,
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

## VoiceTrigger 指令词注册

启动期注册热词和指令词：

```java
VoiceTriggerRegistration registration = new VoiceTriggerRegistration(
        "example_radar",
        List.of("雷达", "警戒"),
        List.of("扫描", "附近敌人"),
        VoiceCommandCategory.INFORMATION,
        10,
        VoiceCommandScope.CLIENT,
        true
);

VoiceTriggerRegistrationResult result = api.registerVoiceTrigger(registration);
```

注销某模块全部指令词：

```java
api.unregisterVoiceTriggers("example_radar");
```

注册结果中可能包含冲突信息：

```java
for (VoiceTriggerConflict conflict : result.conflicts()) {
    String word = conflict.word();
    List<String> owners = conflict.moduleIds();
}
```

ASR / IR 命中后，主干会自动通过协议能力投递 `VoiceTriggerPayload` 到注册的 `VoiceTriggerDeliveryTarget`。外部模组不需要手动拉取匹配结果，也不应把它理解为主干直接调用 addon 方法。

默认投递目标为：

```java
VoiceTriggerDeliveryTarget.defaultFor(moduleId)
```

也可以在注册时显式声明 capability：

```java
new VoiceTriggerDeliveryTarget("example_radar", "EXAMPLE_RADAR.VOICE_TRIGGER")
```

该 capability 由外部模组自己注册和处理。

标准投递字段包括：

- `sourceText`
- `normalizedText`
- `moduleId`
- `matchedHotwords`
- `matchedCommandWords`
- `sourceChannel`
- `confidence`
- `matchedItemNames`
- `matchedItemIds`
- `matchedEntityRefs`
- `timestamp`
- `sessionId`
- `turnId`

## 状态摘要

外部模组可以提交轻量状态，供天枢、GUI 或 Dialogue/IA 使用：

```java
StateSummary summary = new StateSummary(
        "example_radar",
        "threat_overview",
        "雷达状态",
        List.of("附近敌对实体 3 个", "最近目标 12 格"),
        StateSummarySeverity.WARNING,
        System.currentTimeMillis(),
        5000L,
        true,
        true,
        StateSummaryVisibility.PUBLIC,
        Map.of("source", "radar"),
        ""
);

api.submitStateSummary(summary);
```

查询状态摘要：

```java
StateSummaryQuery query = new StateSummaryQuery("example_radar", "threat_overview", false, StateSummaryVisibility.PUBLIC);
List<StateSummary> summaries = api.queryStateSummaries(query);
```

状态摘要应保持轻量，不应承载复杂业务模型或大型数据。

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
api.queryStateSummaries(query);
```

这只是协议中心提交入口，不代表可以直接访问主干内部 runtime 实现，也不提供同步 envelope request 语义。

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
                    IntegrationCapability.STATE_SUMMARY_PROVIDER,
                    IntegrationCapability.GUI_RENDER_CONTRIBUTOR
            )
    ));

    api.registerVoiceTrigger(new VoiceTriggerRegistration(
            "example_radar",
            List.of("雷达"),
            List.of("扫描"),
            VoiceCommandCategory.INFORMATION,
            10,
            VoiceCommandScope.CLIENT,
            true
    ));

    event.registerSettingsContributor((registry, context) -> {
        registry.page("example_radar", "雷达");
    });
}
```

## 兼容性原则

外部模组应遵守：

1. 没有天枢时仍可独立运行。
2. 有天枢时只通过 `TianshuIntegrationApi` 和协议 payload 联动。
3. 不依赖主干内部类或 NeoForge provider 实现。
4. 不把主干作为配置保存或服务端安全基座。
5. 不要求主干理解外部模组业务语义。
6. 使用 capability ready 判断具体能力是否可用。

## 当前推荐的稳定入口

- `TianshuIntegrationRegisterEvent`
- `TianshuIntegrationApi`
- `TianshuIntegrationAccess`
- `IntegrationModuleDeclaration`
- `VoiceTriggerRegistration`
- `StateSummary`
- `GuiContributionDescriptor`
- `CoreLifecycleEventPayload`
- `ResourceReloadEventPayload`
