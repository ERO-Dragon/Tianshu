# 天枢设置 GUI 框架

## 1. 定位

设置框架负责模块设置声明、草稿会话、校验、保存结果、布局模型和宿主渲染，不拥有任何功能模块配置，也不负责跨模块通信。

依赖方向固定为：

```text
模块设置源 -> tianshu-client 设置 API/model/session -> NeoForge Screen/renderer
```

设置源不得导入 Minecraft、NeoForge、LWJGL 或原版 GUI 类型。NeoForge renderer 是当前宿主实现，不是框架 API 的一部分。

## 2. 目录边界

`tianshu-client` 持有：

- `client/gui/settings/api`：面板、模板和 contributor API。
- `client/gui/settings/model`：分类、模板和布局数据。
- `client/gui/settings/registry`：设置源与外部 contributor registry。
- `client/gui/settings/session`：草稿、校验、保存和 reset。
- `client/gui/settings/layout`：与像素渲染无关的布局结果。
- `client/gui/asr|llm|tts|auxilium|presence`：各模块设置声明。

`tianshu-neoforge` 只持有：

- `TianshuSettingsScreen` 和原版 Widget。
- `VanillaModuleSettingsRenderer`。
- `NeoForgeUiText`。
- `ClientConfig` 的设置端口实现。
- 文件选择、语言解析、页面刷新和主线程调度 adapter。

## 3. 文本模型

平台无关层统一使用 `UiText`：

```java
UiText.key("yourmod.gui.option.enabled")
UiText.key("yourmod.gui.status.progress", percent)
UiText.literal(dynamicModelName)
UiText.join(", ", labels)
```

玩家可见的固定文字必须使用资源 key。`literal` 只用于模型名、文件名、识别结果等运行时动态内容。只有 `NeoForgeUiText` 可以把 `UiText` 转换成原版文本组件。

## 4. 设置会话

每个模块打开页面时创建独立 `ModuleSettingsSession`。`MutableSettingsValue` 在内存中保存草稿；保存顺序是：

```text
validate -> 写入模块设置端口 -> config.save -> runtime side effect -> 清除 dirty
```

校验失败不得修改真实配置。`registerOrReplace` 会丢弃旧页面 session，重新打开页面不会继承未保存草稿。

`SettingsSaveResult` 明确表示成功、失败、是否改变、是否需要 reload/restart。renderer 只展示结果，不推断模块行为。

## 5. 配置端口

ASR、LLM、TTS、AX、Presence 和诊断分别拥有窄设置端口。NeoForge `ClientConfig` 同时实现这些端口，最终仍只保存到 `config/tianshu-client.toml`。

禁止重新引入全能配置接口，也禁止设置源直接持有 `ClientConfig`。端口只包含对应页面实际使用的 getter、setter 和 `save()`。

## 6. 宿主端口

- `ClientScheduler`：只提供异步主线程投递和线程判断，不提供同步等待。
- `ClientUiHost`：打开设置、请求重建当前页和显示短状态。
- `ClientTextProvider`：把 `UiText` 解析为宿主文本。
- `ClientFilePicker`：执行宿主文件选择。

模型下载、索引、网络请求、推理和诊断写盘不能经这些端口放到 Minecraft 主线程。下载进度刷新必须合并，当前 ASR/LLM/TTS 使用单个 pending 标记避免每个进度事件重建页面。

## 7. 模块接入

内部模块可实现 `TianshuSettingsContributor`，或提供 `TianshuSettingsRegistrySource`。外部 NeoForge 模组通过 `TianshuIntegrationRegisterEvent.registerSettingsContributor` 注册。

contributor 只声明：

- 分类 ID、资源 key 和顺序。
- panel 模板。
- session 草稿和保存行为。
- 通过协议中心或模块公开 service 发起的显式动作。

contributor 不绘制、不访问 Screen、不查找当前 Minecraft 实例，也不跨模块直连实现类。

## 8. 渲染边界

NeoForge renderer 负责坐标、字体、裁剪、滚动、Widget 状态和 `UiText` 转换。空文本、空列表、长文本和不可见模板必须产生稳定布局，不得让动态内容改变工具栏或固定控件尺寸。

Mixin 只能用于暴露或复用原版渲染能力，不能持有设置草稿、配置或模块业务状态。

## 9. 稳定契约

以下接口是其他模块可依赖的设置层契约：

- `TianshuSettingsContributor`
- `TianshuSettingsRegistry`
- `ModuleSettingsContext`
- `ModuleSettingsPanel`
- `ModuleSettingsSession`
- `MutableSettingsValue`
- `SettingsSaveResult`
- `UiText`

原版 Screen、Widget、renderer 类和 `ClientConfig` 均不是跨平台稳定 API。
