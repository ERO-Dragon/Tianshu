# Sherpa-ONNX TTS 模型下载与装配

本文只描述当前实现。历史 Jackson 示例、单 endpoint 下载代码和“跳过已有文件等于断点续传”的说法已经删除；它们不再是工程依据。

## 1. 数据流

```text
模型 catalog
  -> TtsModelService / TtsModelDownloadCoordinator
  -> HuggingFaceDownloader
  -> ModelDownloadSourcePolicy
  -> ModelDownloadHttpClient
  -> config/tts/models/<model>
  -> TtsModelResolver / SherpaOnnxTtsBackend
```

下载和模型加载必须由 TTS 的后台生命周期执行，不允许在 Minecraft 主线程进行网络、文件或 ONNX 初始化。

## 2. 模型元数据

TTS catalog 的模型条目以 `TtsModelInfo` 为准，常用字段包括：

```json
{
  "author": "csukuangfj",
  "name": "kokoro-multi-lang-v1_0",
  "id": "csukuangfj/kokoro-multi-lang-v1_0",
  "size": 314572800,
  "needVocoder": false,
  "lang": ["en", "zh"],
  "pinned": false,
  "modelFiles": ["model.onnx"],
  "dataDir": "espeak-ng-data",
  "lexiconFiles": ["lexicon-zh.txt"],
  "ruleFsts": ["number-zh.fst"],
  "voicesFile": "voices.bin"
}
```

所有远程文件路径都相对于 Hugging Face 仓库根目录。本地落盘前必须 normalize 并验证仍位于目标模型目录内。

## 3. 下载边界

### 3.1 来源候选

`ModelDownloadSourcePolicy` 负责 URI 拼接：

- repo namespace/name 分段编码。
- revision 编码。
- file path 逐段编码并保留目录 `/`。
- HF official 与 HF Mirror 始终同时保留；连通性探测只决定顺序。
- GitHub direct 与用户配置的 GitHub proxy 同样保留双候选；该逻辑主要供 ASR archive 使用。

### 3.2 传输

`ModelDownloadHttpClient` 负责：

- 每个来源内部有限重试，耗尽后切换下一候选。
- 活动连接登记和 cancel disconnect。
- 受控退避，暂停/取消 control 会在重试间生效。
- 同目录 `.downloading` 临时文件。
- 已知 `Content-Length` 的字节数校验和空文件拒绝。
- 成功后原子移动；文件系统不支持原子移动时使用同文件系统替换。
- 全部来源失败时保留各来源 cause。

当前没有 HTTP Range 断点续传，也没有跨启动下载恢复。`skipExisting=true` 只是跳过已经存在的非空目标，不能称为断点续传。

### 3.3 Hugging Face facade

`HuggingFaceDownloader` 保留：

- `/api/models/{repo}/tree/{revision}` 文件树解析。
- `downloadModelFiles`、`downloadSingleFile` 和 `downloadVocoder` 的公开接口。
- TTS 所需的文件过滤和 progress 回调。

tree 请求与每个 file 请求都独立使用 official/mirror 候选，不能恢复成“探测一次后全程只用一个域名”。

## 4. 文件过滤

整仓同步继续过滤当前运行时不需要的内容：

- `dict/`
- `*.py`
- `.gitattributes`
- `readme` / `readme.*`

如果未来模型确实依赖这些路径，必须以模型 catalog 和运行时加载证据修改过滤规则，并补下载行为测试，不能在 GUI 或 backend 临时补下载。

## 5. Sherpa 装配

模型下载完成后，`TtsModelResolver` 将 catalog 相对路径解析为本地绝对路径：

- 主模型优先选择 `.pack.onnx`，其次 `model.onnx`，最后使用声明列表中的首个可用模型。
- `dataDir` 注入文本前端数据目录。
- `lexiconFiles` 注入发音词典。
- `ruleFsts` 注入文本规范化规则。
- `voicesFile` 注入后端对应音色资源。
- `needVocoder=true` 时通过 `downloadVocoder` 获取 Sherpa vocoder 仓库中的 ONNX 文件。

下载完成不等于模型可用；resolver 和 backend 仍必须验证所有必需文件、后端类型和 native runtime。

## 6. 验证要求

下载器修改至少必须通过：

- HF tree official/mirror 回退。
- HF file official/mirror 回退。
- GitHub direct/proxy 两种首选顺序。
- 每源重试、取消、长度不匹配和临时文件清理。
- ASR required-files 与 archive staging 回归。
- TTS/LLM/ASR 调用方测试、common 全量测试和 NeoForge 编译。
