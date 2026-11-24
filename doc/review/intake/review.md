# 听澜模组代码审查

审查范围：`Tianshu-features/tianshu-intake` 当前工作区源码，以及 `Tianshu/doc/function/intake` 中 ASR、IR、IA 架构和接入文档。重点核对语音采集/识别、文本修复、仲裁、生命周期、异常处理、性能与资源释放。

## 发现

### [已修复] 录音上限的截断告警只会在服务生命周期内发出一次

位置：`Tianshu-features/tianshu-intake/src/main/java/com/rheinmetal/tianshu/intake/audio/AudioCaptureService.java:83`、`:173`、`:205`

`limitReported` 原先是服务实例级状态，第一次录音达到上限后，后续录音仍会静默截断。本次已将音频缓冲区与告警状态收进单轮 PTT capture 状态，并增加连续两轮触发上限的回归测试。

## 架构与契约核对

- ASR、IR、IA 的职责边界整体与文档相符：ASR 输出整句文本，IR 负责清理/修复并发布结构化 `IR_RESULT`，IA 接收后按 claim、租期与默认承接者仲裁并定向投递。
- IA 的仲裁顺序与文档一致。代码按 claim 来源档、priority、稳定 participant key 决胜；无硬 claim 时再检查 lease 与 default owner。交付前重新授权 owner capability，失败时释放新 session 并恢复旧 active session 索引。
- 会话历史有终态上限，冻结上下文有数量上限与过期清理；下载、预览和 ASR 引擎代码也有独立生命周期边界。未发现可由当前源码直接证实的长期集合无界增长或必然未释放资源问题。
- IR 预处理明确只做 trim、填充词/边界词清除，不实现通用同音纠错；实际专名修复依赖命名对象 enhancer 与拼音索引。总体符合 IR 文档对能力边界的说明，不应将其理解为任意句子的 ASR 同音纠错。
- Zipformer 是目录中的流式模型，但当前产品输入保持 PTT，流式输入与 VAD 实现封存；按维护者澄清，保留该模型是为了利用模型能力，本次不将其列为文档缺陷。
- 无限时长录音属于已确认的产品策略，不作为本次整改缺陷。其缓冲随录音结束释放，录音结束时复制缓冲造成的峰值内存仍是该策略的资源成本。

## 验证限制

已使用 `Tianshu-adapter/gradlew.bat :tianshu-intake:test --rerun-tasks` 强制运行听澜测试任务，构建成功，48 个任务执行完成，包含新增的连续两轮截断告警测试。
