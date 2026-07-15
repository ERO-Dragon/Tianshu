# ASR 协议中心使用文档

本文面向需要消费天枢语音识别结果的功能模块和外部模组，只说明 ASR 的稳定跨模块协议，不暴露录音桥、识别引擎、模型下载器或内部 session 实现。

## 1. 当前公开契约

ASR 当前通过协议中心公开三个事件 topic，不提供公共请求 capability：

| 方向 | Topic | PayloadType | Payload | 语义 |
| --- | --- | --- | --- | --- |
| ASR 发布 | `ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY` | `ASR_SPEECH_ACTIVITY` | `AsrSpeechActivityPayload` | 处理后音频中的说话活动状态变化。 |
| ASR 发布 | `ProtocolTopics.INPUT_ASR_FINAL_TEXT` | `ASR_TEXT` | `AsrTextPayload` | 已完成且仍属于当前 session 的识别文本。 |
| ASR 发布 | `ProtocolTopics.MODULE_STATUS` | `MODULE_STATUS` | `ModuleStatusPayload` | `module.asr` 的准备、下载、重载和失败状态。 |
| ASR 订阅 | `ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT` | `CUSTOM` | `RuntimeInterruptPayload` | 运行时会话中断；不是普通录音控制入口。 |

topic 的信封类型均为 `PacketType.EVENT`。ASR 不通过公共 topic 接收 `BEGIN / END / COMMIT / CANCEL`，也没有可供外部模块发送 `REQUEST` 的 ASR capability。

调用方应先作为天枢托管模块完成装配，并在模块装配阶段使用 `TianshuModuleAssemblyContext.moduleRuntime()` 提供的 `ModuleRuntimeAccess` 创建自己的 `AbstractProtocolAdapter`。不要从 `TianshuCoreManager` 获取完整协议运行时，也不要长期缓存其他模块的 adapter；订阅与清理应跟随调用方自己的模块生命周期。

## 2. 为什么没有公共麦克风控制请求

开始或结束语音采集不是普通跨模块业务调用，它涉及玩家输入意图、当前输入焦点、客户端权限、麦克风所有权和 ASR readiness。

同一宿主内的客户端按键层通过 `AsrInputService` 提交这些意图；设置页通过 `AsrModelService` 管理模型和预览。这两个 Java 接口是宿主内窄端口，不是跨模块协议，也不构成外部模组兼容承诺。

外部模块不得通过反射、服务表或直接持有 `AsrController / AudioCaptureService / AsrEngine` 来启动录音。如果以后需要开放受控输入请求，必须新增明确的 capability、不可变 payload、权限策略和生命周期响应，并先更新本文；不能复用 `SYSTEM.RUNTIME_INTERRUPT` 反向模拟控制。

## 3. 订阅最终识别文本

模块适配器可以按下面的方式订阅：

```java
public void subscribeAsrFinalText(EnvelopeHandler handler) {
    subscribeTopic(
        ProtocolTopics.INPUT_ASR_FINAL_TEXT,
        PayloadType.ASR_TEXT,
        AsrTextPayload.class,
        BrokerType.STATELESS_FAST_PATH,
        EnumSet.of(PacketType.EVENT),
        Priority.LOW,
        CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
        handler,
        defaults()
    );
}
```

`AsrTextPayload` 字段：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `text` | `String` | ASR 当前交给下游的完整识别文本。ASR 不做 wake word 截断或 owner 判断。 |
| `rawText` | `String` | ASR 原始文本；当前通常与 `text` 相同，下游不得假定永远相同。 |
| `turnId` | `int` | 当前 ASR session 内递增的轮次编号。 |
| `sessionId` | `long` | 输入会话标识，用于丢弃旧流结果并与后续链路关联。 |
| `inputMode` | `String` | 当前可能为 `push_to_talk`、`stream`、`force_flush` 或 `vad_segment`。调用方应允许未来新增值。 |
| `createdAt` | `long` | ASR 发布结果时的 epoch millis。 |

消费要求：

- 不要只用 `turnId` 做全局唯一键；应至少结合 `sessionId`。
- 不要把 `inputMode` 当作固定 enum 反序列化；未知值应按普通 ASR 文本处理。
- 对话 owner 仲裁应消费 IR/IA 的结构化输出，不应由外部模块直接监听 ASR 文本抢占开放对话。
- handler 中不得执行模型推理、网络请求、文件 IO 或长任务；需要继续处理时提交到协议中心受控 execution lane。

`ProtocolTopics.INPUT_ASR_FINAL_TEXT` 在协议中心使用 `WAIT_IN_QUEUE` delivery policy，目的是保留有序文本事件。订阅者仍必须尽快返回，不能把协议投递线程当业务工作线程。

## 4. 订阅说话活动

```java
public void subscribeAsrSpeechActivity(EnvelopeHandler handler) {
    subscribeTopic(
        ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY,
        PayloadType.ASR_SPEECH_ACTIVITY,
        AsrSpeechActivityPayload.class,
        BrokerType.STATELESS_FAST_PATH,
        EnumSet.of(PacketType.EVENT),
        Priority.LOW,
        CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
        handler,
        defaults()
    );
}
```

`AsrSpeechActivityPayload` 字段：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `speaking` | `boolean` | 处理后音频当前是否处于有效说话活动。 |
| `sessionId` | `long` | 对应的输入 session。 |
| `occurredAtMillis` | `long` | 状态变化时间；传入非正值时 payload 会使用当前时间。 |

该事件表示高通、降噪等处理后的音频活动，不表示按键按下、麦克风刚启动或最终文本已经产生。topic 使用 `LATEST_ONLY` delivery policy；慢订阅者可能只看到最新状态，不应依靠它统计每一次音频边沿。

## 5. 订阅 ASR 模块状态

所有模块共用 `ProtocolTopics.MODULE_STATUS`，因此订阅者收到 `ModuleStatusPayload` 后必须先检查：

```java
ModuleStatus status = payload.status();
if (!AsrProtocolAdapter.MODULE_ID.equals(status.moduleId())) {
    return;
}
```

`ModuleStatus` 中稳定可用的字段包括 `moduleId`、`statusType`、`messageKey`、`severity`、`updatedAtMillis`、`ttlMillis` 和 `tags`。玩家界面优先使用 `messageKey` 本地化；`fallbackMessage` 只是兼容回退，不应被外部模组解析为状态码。

该 topic 使用 `LATEST_ONLY`，适合显示最新 readiness、下载或重载状态，不是完整审计日志。

## 6. 运行时中断

ASR 订阅 `ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT`，payload 为 `RuntimeInterruptPayload`。它包含：

```java
RuntimeInterruptPayload(
    long sessionId,
    RuntimeInterruptPayload.Reason reason,
    String detail,
    long occurredAtMillis
)
```

ASR 只在 `sessionId` 命中当前活动输入时停止该 session。这个 topic 面向统一运行时中断，例如玩家死亡、世界退出、维度切换、客户端关闭或引擎重启；普通业务模块不得把它当成“停止 ASR”按钮，也不得伪造其他模块的 session。

## 7. 请求与响应语义

当前 ASR 没有公共 `ProtocolCapabilities.ASR_*` 请求入口，因此：

- 不要向 ASR topic 发送 `PacketType.REQUEST` 或 `PacketType.COMMAND`。
- 不要为 ASR 最终文本注册 response handler；最终文本是独立事件，不是某个外部请求的回包。
- 不要创建私有 `ASR.RESULT` capability 绕过公开 topic。
- 需要接收识别结果时订阅公开 topic；需要控制宿主输入时通过宿主自身集成层处理玩家意图。

这一区分保证调用方只依赖稳定协议，而 ASR 可以在不破坏外部模块的前提下继续调整录音、模型、线程和 native runtime 实现。

## 8. 生命周期与线程边界

- ASR 音频采集、识别和模型工作在受控后台 lane，不由订阅者在 Minecraft 主线程触发。
- 下载 pause 使用条件等待；resume/cancel 主动唤醒，不进行固定周期轮询。
- ASR 停止时可能不再发布当前 session 的最终文本；调用方不能假设每个 `speaking=true` 都必然对应 final text。
- 模块 destroy 后公开 topic 不再产生新 ASR 事件，订阅者应清理自己的 pending 状态。
- 外部模块不得依赖 `ASR_STREAM` 队列容量、内部 recognizer 类型或模型目录结构。

## 9. 最小接入检查表

- [ ] 只使用 `ProtocolTopics`、`PayloadType` 和公开 payload 类。
- [ ] final text 处理同时保留 `sessionId` 和 `turnId`。
- [ ] 允许未知 `inputMode`。
- [ ] speech activity 只作为最新活动状态，不作为可靠边沿队列。
- [ ] module status 先过滤 `moduleId == module.asr`。
- [ ] handler 不执行阻塞任务，也不占用 Minecraft 主线程。
- [ ] 不直接访问 ASR controller、engine、audio bridge 或下载器。
- [ ] 不把 runtime interrupt 当普通录音控制接口。
- [ ] 不假设 ASR 提供请求响应 capability。
## 诊断记录

ASR 设置面板中的“诊断记录”开关独立控制 `module.asr` 的结构化诊断。开启后可记录识别原文、输入模式和会话标识；关闭时事件不会进入诊断文件。文件由 NeoForge 宿主异步集中写入，ASR 不自行创建文件或线程。
