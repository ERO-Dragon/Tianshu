# CoreManager 模块对接书

## 1. 对接目标

本文档面向准备接入天枢 core 宿主的新功能模块。

封版后的 CoreManager 不关心模块具体功能，只关心模块是否遵守宿主生命周期、是否声明运行时能力、是否通过规定的协议访问面与协议中心交互。

模块接入的目标是：

```text
模块可以被 CoreManager 托管，但 CoreManager 不需要知道模块是什么功能。
```

## 2. 模块接入的基本组成

一个标准模块通常包含：

- 一个实现 `TianshuManagedModule` 的模块类
- 可选的协议适配器
- 可选的服务对象
- 可选的运行时能力定义
- 可选的平台侧实现
- 可选的资源初始化逻辑

模块应该被装配器注册进 `TianshuModuleHost`，而不是由 CoreManager 直接 new 出来。

## 3. 模块生命周期接口

模块应实现 `TianshuManagedModule`。

核心生命周期方法包括：

- `moduleId()`
- `register(ModuleRegistrationContext context)`
- `prepare(ModuleRuntimeContext context)`
- `start(ModuleRuntimeContext context)`
- `stop()`
- `destroy()`
- `unregister()`

不同阶段的建议职责如下。

### 3.1 moduleId

`moduleId()` 返回稳定的模块 ID。

要求：

- 全局稳定
- 不要随 UI 名称变化
- 建议使用 `module.xxx` 风格

示例：

```text
module.asr
module.llm
module.tts
module.ir
module.my_feature
```

### 3.2 register

`register` 阶段用于注册静态能力和服务。

适合做：

- 注册模块服务到 `ModuleServiceRegistry`
- 注册协议 capability
- 注册 topic 订阅
- 注册请求 response handler
- 注册语音触发词
- 建立轻量适配器关系

不适合做：

- 启动线程
- 打开模型
- 初始化重型引擎
- 访问必须等 prepare 后才存在的运行时资源

### 3.3 prepare

`prepare` 阶段用于准备运行时资源。

适合做：

- 初始化引擎
- 创建 worker
- 读取配置
- 检查资源文件
- 标记运行时 capability ready / failed
- 建立运行时控制器

如果模块提供某个运行时能力，应在此阶段或后续异步完成时更新 `RuntimeCapabilityRegistry`。

### 3.4 start

`start` 阶段用于启动模块的活动逻辑。

适合做：

- 启动监听
- 启动桥接器
- 启动后台服务
- 启动协议输入处理

### 3.5 stop

`stop` 阶段用于停止活动逻辑，但不一定释放所有资源。

适合做：

- 停止监听
- 停止 worker 当前任务
- 断开事件桥接
- 停止后台循环

### 3.6 destroy

`destroy` 阶段用于释放模块持有的资源。

适合做：

- 关闭引擎
- 释放 native 资源
- 清空 worker
- 移除 runtime capability
- 清理模块内部状态

### 3.7 unregister

`unregister` 阶段用于撤销协议注册和服务注册。

模块不应假设 unregister 一定能访问 prepare 阶段创建的资源。

## 4. 模块装配

模块通过 `TianshuModuleAssembler` 进入宿主。

推荐的结构是：

- 每个功能模块自己提供一个 `TianshuFunctionModuleInstaller`
- 平台侧或产品侧用 `CompositeTianshuFunctionModuleAssembler` 组合这些 installer
- 装配器只负责把 installer 逐个应用到 `TianshuModuleHost`

这样做的好处是：

- 模块知道自己的依赖和能力
- 平台侧只负责组合，不负责理解具体功能
- 去掉中央默认总装配器后，模块扩展更直接

示例语义：

```java
moduleHost.registerOptionalModule(new MyModule(...), MyCapabilities.MY_FEATURE);
```

当前设计中，绝大多数模块都应视为 optional。

required 模块只适用于真正缺失后 core 无法继续作为宿主运行的基础模块。一般功能模块不应声明为 required。

## 5. 可选模块与失败策略

模块注册时可以带有失败策略：

- optional
- required

### 5.1 optional 模块

optional 模块失败时：

- core 不因该模块失败而整体失败
- 该模块声明的能力会被标记为 failed 或 absent
- 其他模块仍可继续启动

适用于：

- ASR
- LLM
- TTS
- IR
- GUI bridge
- ChatAssistant
- 绝大部分后续功能模块

### 5.2 required 模块

required 模块失败时：

- core 进入 failed 状态
- 生命周期停止继续推进
- 失败状态保留在状态快照中

只有极少数核心基础设施才适合 required。

## 6. 能力声明

Core 层只提供能力机制，具体能力常量应放在功能侧。

function 侧示例：

```java
public final class AsrRuntimeCapabilities {
    public static final RuntimeCapability INPUT = RuntimeCapability.of("capability.asr.input");
}
```

不同模块应把自己的能力定义放在自己的包内。例如：

```text
function/asr/AsrRuntimeCapabilities.java
function/llm/LlmRuntimeCapabilities.java
function/tts/TtsRuntimeCapabilities.java
```

新模块如果有自己的运行时能力，应在所属功能包内定义自己的 capability 常量，不要放进 core 包。

能力命名建议：

```text
capability.<domain>.<ability>
```

示例：

```text
capability.map.query
capability.memory.read
capability.npc.dialogue
capability.combat.assist
```

## 7. 能力状态更新

模块可以通过 `ModuleRuntimeContext.runtimeState().capabilities()` 更新能力状态。

常见操作：

- `markReady(capability, moduleId)`
- `markFailed(capability, moduleId, reason)`
- `remove(capability)`
- `isReady(capability)`

建议规则：

- 初始化成功后标记 ready
- 初始化失败但 core 可继续运行时标记 failed
- destroy 时移除自身持有的能力
- 不要修改其他模块拥有的能力状态

## 8. 模块服务注册

模块间共享服务通过 `ModuleServiceRegistry` 完成。

注册服务：

```java
context.services().register(MyService.class, myService);
```

获取服务：

```java
context.services().find(MyService.class);
context.services().require(MyService.class);
```

建议：

- 服务接口尽量稳定
- 不要把整个模块对象当服务到处传
- 不要通过服务绕回 CoreManager 做业务调用
- 服务依赖最好是 optional 或延迟解析

## 9. 协议访问

模块通过 `ModuleProtocolAccess` 访问协议中心。

模块上下文提供：

- `ModuleRegistrationContext.protocol()`
- `ModuleRuntimeContext.protocol()`

允许模块做：

- 注册 module descriptor
- 注册 topic
- 订阅 topic
- 注册请求 response handler
- submit envelope
- submit protocol task
- 使用 voice trigger registry

模块不应依赖协议中心内部对象，例如：

- lifecycle store
- dead letter queue
- storm guard
- cancellation registry
- executor manager 内部实现
- broker registry 内部结构

如果模块确实需要新的协议能力，应优先扩展协议侧窄接口，而不是把完整 `ProtocolRuntime` 泄漏给模块上下文。

### 9.1 语音触发注册不是模块生命周期注册

模块生命周期注册和语音触发注册是两件事。

模块生命周期注册发生在 assembler / `TianshuModuleHost` 中，用于告诉 CoreManager：

```text
这个模块存在，并由 core 托管生命周期。
```

语音触发注册发生在模块自己的协议适配器或语音适配逻辑中，用于告诉 `VoiceTriggerRegistry`：

```text
这些 wakeWords / extraWords 和我这个 moduleId 有关。
```

因此：

- 模块被 CoreManager 托管，不代表它自动有语音触发词。
- 模块注册语音触发词，不代表 CoreManager 需要知道这些词。
- 模块修改自己的热词配置后，应主动重新注册语音触发词。
- `VoiceTriggerRegistry` 根据 `moduleId` 覆盖旧注册。
- 后续热词文件物化由语音资源层处理。
- ASR 引擎是否重载或重启由 ASR 模块负责。

推荐流程：

```text
模块配置改变
    ↓
模块重新注册 VoiceTriggerRegistration
    ↓
VoiceTriggerRegistry 更新对应 moduleId 的词表
    ↓
语音资源层重新物化 hotwords.txt
    ↓
ASR 模块按需重载或重启引擎
```

CoreManager 不参与这个链路。CoreManager 只负责模块生命周期托管。

## 10. 线程与任务

模块不应自行随意创建长期线程。

优先使用协议运行时提供的任务入口：

- `ModuleProtocolAccess.submitTask(...)`
- 模块自己的协议适配器封装
- 已存在的 executor lane 机制

如果模块确实需要内部 worker，应在 `stop` / `destroy` 中确保关闭。

## 11. 中断处理

CoreManager 提供宿主级中断入口，用于表达“当前运行时处理应被打断”。

模块如果需要响应中断，应通过协议事件、服务或自身控制器处理，不应让 CoreManager 知道具体模块正在执行什么业务。

Core 层不会区分 LLM、TTS、ASR 或其他任务类型。

## 12. 模块不应做的事情

模块接入时禁止或不建议做以下事情：

- 要求 CoreManager 直接引用模块类
- 要求 CoreManager 增加某个模块专属方法
- 要求 CoreManager 判断某个产品功能是否可用
- 在 core 包中定义产品能力常量
- 通过 CoreManager 暴露协议中心完整对象
- 在 register 阶段启动重型资源
- 在 destroy 后继续持有线程或 native 资源
- 修改其他模块的 capability 状态
- 把旧 GUI / Client 兼容需求反向压到 core 里

## 13. 推荐模块结构

推荐结构示例：

```text
function/myfeature/
  MyFeatureModule.java
  MyFeatureProtocolAdapter.java
  MyFeatureService.java
  MyFeatureRuntimeCapabilities.java
```

平台相关实现应放在平台侧包中，再通过模块构造参数或平台 assembler 注入。

例如：

```text
tianshu-neoforge/client/myfeature/
  ClientMyFeatureAdapter.java
```

## 14. 模块接入检查表

接入新模块前，应检查：

- 是否实现 `TianshuManagedModule`
- 是否有稳定 `moduleId`
- 是否由 assembler 注册，而不是 CoreManager 直接创建
- 是否默认 optional
- 是否只在 function 侧定义产品能力
- 是否在 prepare 或异步完成后正确更新 capability 状态
- 是否在 stop / destroy 释放资源
- 是否通过 `ModuleProtocolAccess` 使用协议能力
- 是否没有要求 CoreManager 增加产品语义 API
- 是否没有依赖旧 GUI / Client 的临时结构

## 15. 封版后的扩展原则

CoreManager 封版后，新功能优先通过以下方式扩展：

1. 新增 function 模块
2. 新增模块服务
3. 新增协议 capability / topic / response handler
4. 新增平台侧 assembler 注入
5. 扩展协议侧窄接口

不应通过以下方式扩展：

1. 修改 CoreManager 让它认识新模块
2. 在 core 中加入产品能力常量
3. 在 CoreManager 中加入 `canXxx` 产品语义方法
4. 让 CoreManager 代替协议中心转发消息
5. 让 CoreManager 解释 client 脚本或 GUI 事件

## 16. 最小接入流程

最小模块接入流程如下：

1. 编写模块类实现 `TianshuManagedModule`
2. 在 function 侧定义模块 capability
3. 在 assembler 中注册 optional module
4. 在 register 中注册协议与服务
5. 在 prepare 中初始化资源并更新 capability
6. 在 start 中启动活动逻辑
7. 在 stop / destroy 中释放资源
8. 通过 CoreManager 的通用状态查询确认模块状态

完成以上流程后，模块即可被 core 宿主管理，而不需要 CoreManager 了解模块业务细节。
