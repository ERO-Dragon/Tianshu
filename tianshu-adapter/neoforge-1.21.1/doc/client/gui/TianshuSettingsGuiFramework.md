# 天枢设置 GUI 框架

## 快速摘要

1. **作用**
   - 为 ASR、LLM、TTS、AX 和映迹提供统一的设置页面；IR、IA 属于内部工作模块，不进入玩家设置导航。
   - 玩家在页面中修改的是当前草稿，点击保存后才真正改变游戏配置。
2. **设置流程**
   - 打开页面：读取当前配置并建立本次页面的草稿。
   - 编辑设置：只修改草稿，不影响正在运行的模块。
   - 保存设置：先检查所有修改，再统一写入配置并通知相关模块重新应用。
   - 关闭页面：未保存的修改随本次页面结束而放弃。
3. **页面体验**
   - 每个模块拥有自己的设置分类和状态信息。
   - 页面右下角提供一个全局调试开关，统一控制所有模块诊断和映迹调试流水线，不在模块面板中重复出现。
   - 模型下载、设备刷新等耗时操作在后台进行，页面只展示进度和结果。
   - 映迹、模块状态和外部扩展可以通过统一设置入口提供状态或操作反馈。
   - 模型文件状态在后台检查；检查完成前页面显示未知状态，下载和删除操作按钮保持不可用，检查完成后自动刷新。

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

每次打开页面都会创建独立的 `SettingsCoordinator` 和各模块 `ModuleSettingsSession`。`MutableSettingsValue` 在内存中保存草稿；保存顺序是：

```text
validate -> 暂存设置端口写入 -> config.save -> 清除 dirty -> runtime side effect
```

校验失败不得修改真实配置。批量保存先校验全部 dirty session，全部通过后才按注册顺序写入；这样一个模块的校验失败不会让前面的模块先被写入。模块自己的 `save()` 仍负责写入对应设置端口、保存统一配置并触发运行时副作用。

内置页面使用 `SettingsSaveTransaction`：保存前捕获当前配置，写盘失败时按逆序恢复已尝试写入的值，保留草稿和 dirty，不触发运行时副作用。设置注册表把保存异常转换为失败结果，页面可以重试。TTS 模型参数由其服务写入，写盘异常必须传回会话层，不能吞掉。批量保存仍是各模块依次提交，不承诺跨模块、跨文件的原子事务；先前已经成功提交的模块保持已保存状态。

每个模块 ID 同时只允许一个 session。`register` 和 `registerOrReplace` 都遵守这一约束，后注册的会话替换旧会话；重新打开页面不会继承未保存草稿。

会话层提供 `reset(moduleId)` 作为草稿恢复能力，当前 NeoForge 页面不额外展示 reset 按钮；宿主是否提供该操作由产品页面另行决定。

`SettingsSaveResult` 明确表示成功、失败、是否改变、是否需要 reload/restart。renderer 只展示结果，不推断模块行为。

## 5. 配置端口

ASR、LLM、TTS、AX、Presence 和全局调试分别拥有窄设置端口。NeoForge `ClientConfig` 同时实现这些端口，最终仍只保存到 `config/tianshu-client.toml`。全局调试只使用 `debug.enabled`，不存在模块级隐藏开关。

禁止重新引入全能配置接口，也禁止设置源直接持有 `ClientConfig`。端口只包含对应页面实际使用的 getter、setter 和 `save()`。

## 6. 宿主端口

- `ClientScheduler`：只提供异步主线程投递和线程判断，不提供同步等待。
- `ClientUiHost`：打开设置、请求重建当前页和显示短状态。
- `ClientTextProvider`：把 `UiText` 解析为宿主文本。
- `ClientFilePicker`：异步返回文件选择结果，取消 future 可关闭待选窗口。结果由设置模块投递回 `ClientScheduler` 后再更新草稿或启动 TTS IO 导入；取消、失败均解除忙状态，重复点击不会产生重复窗口或导入。

模型下载、索引、网络请求、推理和诊断写盘不能经这些端口放到 Minecraft 主线程。模型目录与音色目录由对应模块在 IO lane 中生成快照；音色导入的文件复制也在 TTS IO lane 中完成。下载进度刷新必须合并，当前 ASR/LLM/TTS 使用单个 pending 标记避免每个进度事件重建页面。

GPU 探测由 `GpuInfo.requestRefresh` 显式发起并在单线程有界执行器中运行。设置页打开和 LLM 性能采样可以请求后台刷新；普通状态读取只返回最后一次完整快照。首次没有快照时显示检测中，已有快照时后台刷新不会覆盖稳定展示，也不会因渲染或状态读取每秒启动 `nvidia-smi` 或 PowerShell。

## 7. 模块接入

内部模块可实现 `TianshuSettingsContributor`，或提供 `TianshuSettingsRegistrySource`。外部 NeoForge 模组通过 `TianshuIntegrationRegisterEvent.registerSettingsContributor` 注册。

contributor 只声明：

- 分类 ID、资源 key 和顺序。
- panel 模板。
- session 草稿和保存行为。
- 通过协议中心或模块公开 service 发起的显式动作。

contributor 不绘制、不访问 Screen、不查找当前 Minecraft 实例，也不跨模块直连实现类。

## 8. 页面生命周期

- 页面打开时创建新的 coordinator 和各模块 session；旧页面的 session 不会进入新页面的保存集合。
- 页面重建复用当前 session，不重新读取或写入真实配置。
- 全局调试拥有独立草稿 session，但不注册成模块分类；它和模块设置一起由页面保存。
- 关闭页面通过 `SettingsSessionRegistry.close()` 释放会话，再丢弃未保存草稿，不隐式保存。`ModuleSettingsSession.close()` 默认为空；拥有异步文件选择的会话负责取消选择并拒绝迟到完成回调。替换同 ID 的会话也关闭旧实例；页面重建不关闭会话。
- 当前选择的模块 ID 无法在 registry 中找到时，Screen 显式回到第一个可用分类；registry 本身不会静默返回其他分类。

## 9. 渲染边界

NeoForge renderer 负责坐标、字体、裁剪、滚动、Widget 状态和 `UiText` 转换。空文本、空列表、长文本和不可见模板必须产生稳定布局，不得让动态内容改变工具栏或固定控件尺寸。

Mixin 只能用于暴露或复用原版渲染能力，不能持有设置草稿、配置或模块业务状态。

## 10. 稳定契约

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
