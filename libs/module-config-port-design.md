# 天枢模块配置端口拆分设计

日期：2026-07-14  
状态：已确认，待实施

## 1. 目标

移除 common 运行时对总接口 `ITianshuConfig` 的依赖，让 Core、ASR、LLM、TTS、AX 和语音资源管理只获得各自需要的只读配置。NeoForge 继续拥有 GUI 配置的写入、校验和保存职责；玩家可见设置、当前默认值、模型选择结果、运行时刷新方式和外部协议不变。

本设计不修改模型推理、显存策略、协议语义、线程 lane、下载降级链或 Minecraft 主线程边界。

## 2. 当前问题

`ITianshuConfig` 同时承担：

- ASR、LLM、TTS 和全局开关的读取与写入；
- 模块存储目录和模型目录计算；
- LLM/embedding catalog 查询；
- 目录扫描和 GGUF 文件发现；
- GUI 保存入口；
- 大量带默认实现的可选 setter。

这使功能模块能够读取或修改不属于自己的配置，也让 common API 接口执行文件 IO、依赖具体模型 catalog。`TianshuModuleAssemblyContext` 继续传递完整配置后，模块装配端口虽然已收窄协议运行时，配置边界仍然是横向泄漏点。

## 3. 方案比较

### 方案 A：总接口继承多个小接口

让 `ITianshuConfig` 继承 ASR/LLM/TTS 等接口，模块构造器改用小接口。迁移较短，但完整总接口和 setter 仍存在，装配上下文仍能把所有配置交给任意模块，模型文件发现仍可能留在 API 默认方法中。

### 方案 B：宿主装配模块只读端口（采用）

common 定义按职责划分的只读端口，NeoForge `ClientConfig` 或其轻量 adapter 实现这些端口。Core 装配只分发模块所需端口，功能模块无法取得完整宿主配置。setter 和 `save()` 留在 NeoForge；模型目录解析进入对应 model/function 域协作者。

这一方案保留 GUI 动态读取能力，同时消除总接口、跨模块配置访问和 API 文件 IO。

### 方案 C：启动时生成不可变快照

所有模块只接收不可变 record。隔离最强，但 GUI 设置变化后必须重新创建快照并决定哪些模块刷新或重建，容易改变当前即时读取和 refresh 行为。本轮不采用；模块内部已有适合的 request/session snapshot 可继续保留。

## 4. 端口与归属

### 4.1 宿主路径

定义只读宿主存储端口，只提供稳定根目录，不执行文件扫描：

- 游戏配置根；
- 模块数据根；
- ASR、LLM、TTS 模块根；
- voice library 根。

各功能域在自己的 layout/path resolver 中派生 `model`、RAG、AX memory 等子目录。路径端口不查 catalog，也不判断文件是否存在。

### 4.2 ASR 配置

ASR 端口只包含启用状态、触发模式、麦克风、音频预处理开关、所选模型标识和 ASR 所需内部连接值。模型标识规范化由 ASR model catalog/resolver 负责，配置对象不依赖 `AsrModelManager`。

### 4.3 LLM 配置

LLM 端口只包含启用状态、模型标识、GPU/MTP/frame guard、上下文与 token budget、请求 admission、RAG 和 timeout 等 LLM 运行参数。

所选 chat/embedding 模型到实际 GGUF 的解析由 LLM model resolver 负责：先使用 catalog 指定文件，再按已有确定性规则选择目录中的 GGUF，最后返回明确的未解析结果。目录扫描失败保留 cause/诊断 code，不在配置 getter 中吞掉异常。

### 4.4 TTS 配置

TTS 端口只包含启用状态、所选模型标识和当前后端真正需要的运行参数。voice library 使用独立只读路径端口。未来 Qwen/Fish 接入时扩展 backend/model descriptor，不向现有 TTS 配置端口提前加入空字段。

### 4.5 AX 配置

AX 继续使用现有 `AXAssistantSettings`、`AXOutputSettings` 等窄接口；存储只接收模块数据根或 AX 专用 layout root，不再为了 `getRootPath()` 依赖总配置。

### 4.6 Core 与语音资源

Core 不读取 ASR/LLM/TTS/AX 设置。`VoiceResourceManager` 只接收构建 voice resource registry 所需的路径/模型选择只读视图。`TianshuModuleAssemblyContext` 删除完整配置字段；NeoForge assembler 在宿主组合根中持有配置端口集合，并把具体端口传给相应 installer。

配置端口集合只存在于组合根，不作为功能模块 API，也不注册进 `ModuleServiceRegistry`。

## 5. 数据流

1. NeoForge GUI 修改 `ClientConfig` 的具体值并由宿主保存。
2. `ClientConfig` 的只读 adapter 将当前值暴露给对应模块端口。
3. Core 创建生命周期宿主与协议运行时，不持有功能模块总配置。
4. NeoForge assembler 创建各 installer，并只传入该模块所需端口。
5. 模块在既有 prepare/request 边界读取当前值；需要一致请求视图的地方继续生成模块内部 snapshot。
6. 模型 resolver 使用“模型标识 + 模块根路径 + catalog”解析实际文件；解析结果交给模型生命周期服务。

## 6. 错误处理

- 必需端口缺失在装配阶段立即失败，不使用 null/default 伪装生产配置。
- 空模型选择保持现有“未选择/未就绪”语义，不偷偷选择另一模型。
- 路径解析拒绝越过模块根目录的模型标识。
- 文件发现失败返回结构化结果或保留 cause，由现有模块状态/诊断出口发布；不得在 getter 中 `catch` 后静默返回猜测路径。
- GUI 配置错误由 NeoForge 校验和本地化界面呈现，common 不产生玩家语言文本。
- 所有目录扫描继续运行在既有模型 IO/生命周期 lane，不进入 Minecraft 主线程。

## 7. 迁移策略

采用一次性迁移，不保留双架构：

1. 先以架构测试固定功能模块不得依赖 `ITianshuConfig`、其他模块配置端口或 NeoForge 类型。
2. 引入只读端口和模型 path resolver，并为现有行为补 characterization tests。
3. 由叶子协作者向 installer、assembler、Core 逐层迁移构造依赖。
4. 删除 `TianshuModuleAssemblyContext.config()`、`ITianshuConfig`、兼容构造器、no-op setter 默认实现和 API 中的文件扫描。
5. 清理不再使用的 imports、helpers、测试 fake 总配置和文档示例。

测试可使用各模块自己的 immutable test config/fake port，不重新创建测试专用总接口。

## 8. 验证

- 配置端口架构测试：功能模块只能 import 自己的配置端口和共享路径端口。
- Core 边界测试：装配上下文不含完整配置，Core 不依赖功能模块配置。
- LLM resolver 测试：catalog 文件、`model.gguf`、确定性首个 GGUF、空目录、IO 失败和路径越界。
- ASR/TTS 模型选择 characterization tests：迁移前后解析结果一致。
- GUI/宿主编译测试：`ClientConfig` 字段、setter、保存和配置页面调用保持可编译。
- 生命周期测试：GUI 值变化后的 refresh、退出世界/重进和 destroy 行为保持一致。
- 全量执行 `:tianshu-common:test`、`:tianshu-neoforge:compileJava`、架构扫描和 `git diff --check`。

## 9. 非目标

- 不增加玩家配置项。
- 不接入新的 TTS/LLM/ASR 模型。
- 不改变下载、推理或音频执行链。
- 不建立通用依赖注入框架或配置总线。
- 不为删除的 `ITianshuConfig` 提供兼容 facade。
