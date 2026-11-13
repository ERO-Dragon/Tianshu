# 听澜 · Intake

听澜是天枢（Tianshu）的语音输入与对话仲裁模块。它在本机完成语音识别，修复 Minecraft 专有词，并由「思衡」把每一轮对话交给最合适的参与方。

**游戏版本**：不限。听澜以单个 universal jar 分发，同时带 NeoForge / Fabric 入口；各 Minecraft 版本的兼容性由天枢适配。
**加载器**：NeoForge / Fabric
**运行环境**：天枢客户端，与舟楫（Ferry）、百工（Foundry）、映迹（Presence）协同。
**外部接入**：第三方模组可以注册对话参与方，接入方式见[外部接入指南](../EXTERNAL_INTEGRATION_GUIDE.md)。

---

## 为什么是听澜

- **听得准** — 按住语音键说完整句，本地识别；拼音索引会修复物品、实体与指令名，中文里的 Minecraft 专有词也能落到正确对象。
- **接得进** — 其他模组用 `DialogueParticipant` 声明唤醒词、准星目标、手持物或交互状态，就能参与同一套仲裁。
- **有兜底** — 没有 claim、也没有有效租期时，交给玩家选择的默认承接者；也可以设为「无」。
- **本地优先** — 语音识别在本机完成；模型按需下载，之后可离线使用。

## 思衡：选得对，也看得见

每轮开口时，思衡把识意（IR）的结构化结果与开口瞬间冻结的交互上下文放在一起：有硬 claim 时，上下文条件（准星、手持物、潜行等）优先于唤醒词，再按参与方优先级与稳定顺序选中标者；没有硬 claim 时，上一任中标者的租期未过期就继续承接，否则回到默认承接者。

预中标、中标与租期环直接画在映迹 HUD 上，开口前就知道这一轮归谁。HUD 和仲裁用的是同一份结果，不是两套逻辑。

## 模型

| 模型 | 语言 | 体积 | 说明 |
|---|---|---|---|
| **SenseVoice 中英日韩粤 Int8** | 中、英、日、韩、粤 | 226 MB | 默认推荐；离线整句识别，适合按键说话 |
| **Zipformer 多语种 Int8** | 阿、英、印尼、日、俄、泰、越、中 | 324 MB | 目录内唯一的流式模型，面向连续输入 |
| **Whisper Turbo 多语种** | 中、英、日、韩、法、德、俄、西 | 1.25 GB | 覆盖语言最多，体积与 CPU 占用最高 |

模型按需下载，下载后可离线使用；可在设置页切换，无需重装。

## 第三方参与方

其他模组可以注册 `DialogueParticipant`，声明唤醒词、准星目标、手持物品、装备或交互状态等 claim；听澜负责收集 claim、选出中标者、建立会话并把这一轮定向投递给胜出者。详细契约见[外部接入指南](../EXTERNAL_INTEGRATION_GUIDE.md)与 [IA 外部模组仲裁接入说明](../doc/function/intake/ia/IA_外部模组仲裁接入说明.md)。

## 许可与致谢

本模组以 GNU 宽通用公共许可证第 3 版（LGPL-3.0）开源（© 2026 Ero-Dragon）。

语音识别运行时与模型来自公开项目，版权归各自作者所有：

- sherpa-onnx — k2-fsa
- SenseVoice — FunAudioLLM
- Zipformer / Icefall — k2-fsa
- Whisper — OpenAI
- ONNX Runtime — Microsoft
- pinyin4j — 拼音索引

模型按需下载，首次使用时从模型目录获取。

更多文档：[ASR 模块架构](../doc/function/intake/ASR_模块架构说明.md) · [IR 模块架构](../doc/function/intake/repair/IR_模块架构说明.md) · [IA 模块开发技术说明](../doc/function/intake/ia/IA_模块开发技术说明.md) · [听澜游戏内测试说明](../doc/function/intake/听澜游戏内测试说明.md)