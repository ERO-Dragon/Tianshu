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

NeoForge 先在局部构建 `NeoForgeClientSession`，只有全部资源、Integration API 和事件监听器装配成功后才发布当前会话。启动中途失败会逆序清理已经获得的资源，并允许再次启动。事件采用对象级注册，关闭时整体注销，不会遗留持有旧 runtime 的监听器。

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
  -> GameShuttingDownEvent
  -> unregister NeoForge event binding
  -> clear Integration API and Presence static hook
  -> CoreManager.destroy
  -> audio shutdown
  -> diagnostics flush/close
  -> IR index close
  -> GPU detector close
```

`GameShuttingDownEvent` 是正常关闭入口，JVM shutdown hook 只作为最终兜底，两者进入同一个幂等关闭流程。runtime 使用 generation 丢弃退出世界后到达的旧启动回调。Presence 同步使用独立的世界代次清空快照、状态和排队查询，旧世界请求不会在重进后继续。重复 login、logout 和 shutdown 均幂等；启动失败会回到 `CLIENT_READY`，允许下次进入世界重试。

## 3. 线程边界

Minecraft 主线程只执行：

- NeoForge 事件接收。
- 原版 GUI/HUD 绘制。
- 读取当前 Minecraft 对象并立即转换为不可变快照。
- 必要的 Screen 切换和 Widget 更新。
- 每 tick 更新一次 HUD 展示快照。

禁止在主线程执行模型下载、文件解包、模型目录/音色目录扫描、音色文件复制、IR 索引、GPU 进程探测、模型推理、诊断写盘或阻塞等待。

设置页只读取模型可用性和音色列表快照。模型下载、删除、音色枚举和音色导入由对应功能模块使用已有 IO lane 执行；完成后再通过 `ClientScheduler` 请求页面刷新。

游戏目录在 Bootstrap 构造时通过加载器路径捕获为稳定 `Path`。配置、模型目录、诊断和 IR 缓存的后台调用只读该路径，不再从后台访问 `Minecraft` 单例。语言和 AX 世界身份同样在客户端线程捕获为不可变快照；后台 prompt、embedding 选择、IR 索引和记忆隔离只读快照。

语音按键由 `NeoForgeVoiceInputController` 单独维护平台输入状态。持续监听模式只在按键按下沿提前提交当前分段，PTT 模式按下开始、松开结束；模式切换或 ASR 中途不可用时会取消旧输入并清除陈旧按键状态。`NeoForgeClientEvents` 只负责事件接收和有界转发。

当前 client 自有后台资源：

| 资源 | 所有者 | 容量/策略 |
|---|---|---|
| IR index | `ClientNamedObjectIndexManager` | 单线程，队列 2，关闭 generation 防旧写入 |
| diagnostics | `ClientDiagnosticWriter` | 单线程，有界 2048，满时非阻塞丢弃并计数 |
| audio | `AudioManager` | 2 线程，队列 8，拒绝不回退到调用线程 |
| GPU detection | `GpuInfo` | 单线程，队列 1，只响应显式刷新并保留最后完整快照 |
| Presence query | `PresenceContextQueryCoordinator` | 有界 64，每 tick 最多处理 8 个，满时返回 `PRESENCE_BUSY` |

Protocol/Core 自有线程继续由各自 policy 管理；NeoForge 不创建第二套协议执行器。

## 4. Minecraft 数据边界

Minecraft 活对象只能存在于 NeoForge adapter。跨入 client/common 的数据必须是字符串、ID、数值、record 或不可变集合。

- Presence：NeoForge 只在客户端线程捕获请求指定的游戏上下文和 advancement packet，转换为 snapshot/payload；动态事实不做固定扫描。
- IR：NeoForge registry provider 构建命名对象字典；client 负责缓存和索引。
- IR 资源重载：prepare 阶段读取关键词资源，apply 阶段刷新当前语言与 registry 快照，后台索引始终解析当前会话的索引管理器，不持有首次启动的旧实例。
- GUI：client 生成 `UiText` 和设置模型；NeoForge 转换为原版文本与 Widget。
- HUD：client 通过显式 `PRESENCE.ACTIVITY` 聚合出 `PresenceHudDisplay`，其中主状态与 `listening` 独立；NeoForge 每 tick 生成一次渲染快照，渲染帧只读缓存并执行字体和 `GuiGraphics` 绘制。

禁止把 Player、Level、Entity、ItemStack、Screen 或原生 packet 以 `Object` 形式穿过边界。

## 5. 配置

NeoForge `ClientConfig` 是 `config/tianshu-client.toml` 的唯一实现和保存入口。client 只依赖模块窄端口：

- `AsrSettingsAccess`
- `LlmSettingsAccess`
- `TtsSettingsAccess`
- `AxSettingsAccess`
- `PresenceSettingsAccess`
- `GlobalDebugSettingsAccess`

这些端口不能合并回全能配置接口。新增宿主时实现相同端口即可，模块设置源不需要变化。

Presence 设置只保留 HUD 总开关和状态文本开关。旧的 ASR / LLM / TTS / AX 来源过滤已经删除，玩家可见产品活动必须忠实反映真实状态；模块健康、错误和内部阶段继续留在设置、诊断与日志中。

旧的 ASR/LLM 服务端口以及语义不完整的隐藏总开关已经删除。ASR、LLM、TTS、AX 等模块继续使用各自明确的启用状态，不再存在只停止语音 tick、却没有停用其他模块的第二套总开关。

IR 与 IA 是内部工作模块，不注册玩家设置分类。ASR、AX、LLM、TTS 也不再各自持有诊断开关；`debug.enabled` 是唯一调试配置，由设置页右下角的独立草稿控件统一保存。开启后统一允许各模块诊断事件写入，并显示映迹模块流水线；关闭后全部停用。

GPU 设备发现和占用采样使用最后一次完整快照。打开设置页或 LLM 请求需要性能判断时可以显式请求后台刷新，但 `devices()`、`detected()` 和 `detecting()` 本身不会启动任务。已有结果在刷新期间继续展示，因此不会在“检测中”和设备结果之间闪烁，也不会由 GUI 读取每秒启动外部进程。

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
- HUD 渲染帧只读 tick 快照，不重复计算 Presence 展示状态。
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

## 10. 已知后续项

- `ClientFilePicker` 目前仍是同步返回接口。彻底异步化需要先修改 `tianshu-client` 的端口契约；NeoForge 不创建临时线程旁路。
- 当前 HUD 已具备稳定的 `primaryState + listening` tick 快照输入和状态文字 renderer；状态机 Shader 的视觉形态、位置、尺寸与动画需要产品方案确认后再实现，不能反向扩张协议枚举。
- `ClientConfig` 的 TOML 中文注释仍是现有例外。删除注释或明确允许技术配置注释硬编码，需要单独确认。
