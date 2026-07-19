# NeoForge 客户端架构说明

## 1. 项目结构

天枢客户端采用单向依赖：

```text
tianshu-neoforge -> tianshu-client -> tianshu-common
```

- `tianshu-common`：Core、Protocol 和 ASR/IR/IA/AX/LLM/TTS 功能模块。
- `tianshu-client`：客户端 runtime、Presence、设置声明、IR 索引、音频、诊断和展示模型。
- `tianshu-neoforge`：NeoForge 事件、Minecraft 对象读取、TOML 配置、原版 GUI/HUD 和平台 adapter。

`tianshu-client` 有自动门禁，禁止导入 Minecraft、NeoForge、LWJGL 和 Blaze3D。未来 Fabric 或其他 MC 版本只替换第三层。

## 2. 生命周期

`TianshuCoreManager` 仍是所有功能模块的生命周期宿主。`TianshuClientRuntime` 只编排客户端级与世界级资源，不接管模块内部生命周期。

```text
client init
  -> TianshuClientRuntime.startClient
  -> IR index async initialize

world login
  -> NeoForgeClientLifecycleAdapter.onWorldLogin
  -> TianshuClientRuntime.startWorldSession
  -> CoreManager.startRuntimeSession
  -> Presence starts a new world session after Core is running

world logout
  -> onWorldLogout
  -> Presence immediately rejects/clears old-world context work
  -> CoreManager.stopRuntimeSession
  -> release microphone capture

client shutdown
  -> CoreManager.destroy
  -> audio shutdown
  -> diagnostics flush/close
  -> IR index close
  -> GPU detector close
```

runtime 使用 generation 丢弃退出世界后到达的旧启动回调。Presence 同步使用独立的世界代次清空快照、状态和排队查询，旧世界请求不会在重进后继续。重复 login、logout 和 shutdown 均幂等；启动失败会回到 `CLIENT_READY`，允许下次进入世界重试。

## 3. 线程边界

Minecraft 主线程只执行：

- NeoForge 事件接收。
- 原版 GUI/HUD 绘制。
- 读取当前 Minecraft 对象并立即转换为不可变快照。
- 必要的 Screen 切换和 Widget 更新。

禁止在主线程执行模型下载、文件解包、IR 索引、GPU 进程探测、模型推理、诊断写盘或阻塞等待。

当前 client 自有后台资源：

| 资源 | 所有者 | 容量/策略 |
|---|---|---|
| IR index | `ClientNamedObjectIndexManager` | 单线程，队列 2，关闭 generation 防旧写入 |
| diagnostics | `ClientDiagnosticWriter` | 单线程，有界 2048，满时非阻塞丢弃并计数 |
| audio | `AudioManager` | 2 线程，队列 8，拒绝不回退到调用线程 |
| GPU detection | `GpuInfo` | 单线程，队列 1，同一时间只保留一个 refresh |
| Presence query | `PresenceContextQueryCoordinator` | 有界 64，每 tick 最多处理 8 个，满时返回 `PRESENCE_BUSY` |

Protocol/Core 自有线程继续由各自 policy 管理；NeoForge 不创建第二套协议执行器。

## 4. Minecraft 数据边界

Minecraft 活对象只能存在于 NeoForge adapter。跨入 client/common 的数据必须是字符串、ID、数值、record 或不可变集合。

- Presence：NeoForge 只在客户端线程捕获请求指定的游戏上下文和 advancement packet，转换为 snapshot/payload；动态事实不做固定扫描。
- IR：NeoForge registry provider 构建命名对象字典；client 负责缓存和索引。
- GUI：client 生成 `UiText` 和设置模型；NeoForge 转换为原版文本与 Widget。
- HUD：client 提供 `PresenceHudDisplay`；NeoForge 执行字体和 `GuiGraphics` 绘制。

禁止把 Player、Level、Entity、ItemStack、Screen 或原生 packet 以 `Object` 形式穿过边界。

## 5. 配置

NeoForge `ClientConfig` 是 `config/tianshu-client.toml` 的唯一实现和保存入口。client 只依赖模块窄端口：

- `AsrSettingsAccess`
- `LlmSettingsAccess`
- `TtsSettingsAccess`
- `AxSettingsAccess`
- `PresenceSettingsAccess`
- `IrSettingsAccess`
- `IaSettingsAccess`
- `ClientDiagnosticsConfiguration`

这些端口不能合并回全能配置接口。新增宿主时实现相同端口即可，模块设置源不需要变化。

IR 与 IA 各自通过 `IrSettingsRegistrySource`、`IaSettingsRegistrySource` 注册自己的分类和诊断开关。工程不存在全局 Diagnostics 设置分类；ASR、AX、LLM、TTS 的诊断开关同样归属各自模块设置 source。`ModuleDiagnosticsSettingsRegistrySource` 只是复用单个诊断 toggle 的 session/save 行为，不代表独立功能模块。

## 6. GUI 与资源

语言文件、纹理和 `ir-intent-keywords.json` 位于 `tianshu-client/src/main/resources/assets/tianshu`。最终 NeoForge jar 聚合 client/common 输出，同时保留 NeoForge 专属的：

- `tianshu.mixins.json`
- `META-INF/neoforge.mods.toml`
- 加载器模板和 metadata

固定显示文本必须通过资源 key。client 使用 `UiText`，只有 NeoForge adapter 能转换成 Minecraft 文本组件。

## 7. 外部接入

功能模块之间仍只通过 Protocol Center 通信。NeoForge 平台层不能成为业务消息总线。

外部模组通过 `TianshuIntegrationRegisterEvent` 获取集成 API，并可注册 `TianshuSettingsContributor`。设置 contributor 位于平台无关 API 上，不依赖原版 Screen。

## 8. 性能验收

自动门禁覆盖：

- client 禁止平台 import。
- 生命周期重复事件和旧 generation 回调。
- 后台队列容量和关闭拒绝。
- 设置页面下载进度刷新合并。
- tick/world event 不执行同步等待和文件 IO。
- NeoForge jar 同时包含共享 assets 与加载器 metadata。

真实游戏仍需验证：帧时间、真实麦克风、长时间 LLM/TTS、显存压力、MOSS warmed RTF、世界反复进入退出和资源重载。自动测试不能替代这些设备与性能基线。

## 9. Fabric/版本迁移

新宿主需要实现：

- 生命周期事件到 `ClientRuntimeLifecycle` 的转发。
- `ClientScheduler`、`ClientUiHost`、`ClientTextProvider`、`ClientFilePicker`。
- Minecraft context/registry snapshot provider。
- 配置端口和资源聚合。
- 对应加载器的 Screen/HUD renderer。

Common 功能模块、协议 payload、client runtime、设置 session、Presence、IR index、音频和诊断逻辑不应因加载器或 MC 小版本变化而修改。
