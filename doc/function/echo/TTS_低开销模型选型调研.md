# PLAN — 低开销 / 可 CPU 实时推理的神经 TTS 选型调研（Minecraft 本地语音 AI 模组）

> 本文件是**纯调研交付物**：不写代码、不改工程文件、不构建。所有工程改动留给后续阶段。
> 抓取时间：本次会话当日。可验证的时间锚点：GitHub API 返回的 `updated_at` 最新为 **2026-10-20**，据此推断抓取发生在 **2026 年 10 月下旬**（下文中「官方/第三方」均按抓取到的页面原文标注）。

---

## 0. 环境限制（影响证据强度，必须先声明）

本机的网络抓取存在明显的**域名可达性限制**，调研期间实测：

| 可达 | 不可达（DNS 失败 `ipv4only.arpa` / 401） |
|---|---|
| `api.github.com`、`github.com`（HTML 有导航截断）、`hf-mirror.com`（HuggingFace 镜像）、`arxiv.org`、`kyutai.org` | `huggingface.co`、`raw.githubusercontent.com`、`cdn.jsdelivr.net`、`pypi.org`、`*.github.io`、`r.jina.ai`、大量博客站 |

后果（必须在结论里保留的不确定性）：
1. **sherpa-onnx 官方 RTF 汇总表（`docs/source/onnx/tts/pretrained_models/rtf.rst`）始终抓取失败**——这是业内最权威的第三方 CPU RTF 对比表，本次只能改用 GitHub PR/issue 内的实测日志替代。
2. GitHub 仓库 README 正文因页面导航截断无法读取，只能拿到 API 元数据（license/描述/时间）与 issue/PR 正文（正文含实测日志，质量反而更高）。
3. 因此下表凡标 **UNVERIFIED** 的单元格，均为「有印象但本次未拿到一手页面」的项，**不得直接当作事实用于决策**，需下一阶段实测或人工复核。

---

## 1. 目标与成功标准

**目标**：为一个跑在 Minecraft 客户端里的本地语音 AI 模组，选定一款稳态 **RTF ≪ 1** 的神经 TTS 模型，取代当前 `OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX`。

**成功标准（验收口径）**：

| 编号 | 标准 | 判定方式 |
|---|---|---|
| G1 | 稳态 RTF ≤ 0.5（目标 ≤ 0.3），在 **4 个逻辑核可用**、游戏同时运行的条件下 | 目标机实测（i5-13500HX，`intra_op_threads=4`），≥ 3 段文本取中位数 |
| G2 | 首包延迟 ≤ 800 ms（用户按下"说话"到听见第一个音节） | 端到端打点（含 G2P/分词、首块推理、重采样、音频设备启动） |
| G3 | 支持**普通话 + 英文混读**（Minecraft 语境里中英混排是常态：物品名、模组名、玩家 ID） | 人工评测 30 句中英混排文本，无整句崩坏/漏读 |
| G4 | 纯 CPU 可跑（不依赖 CUDA；GPU 被游戏占用时不影响） | 关闭 GPU 后仍满足 G1/G2 |
| G5 | 体积可控（≤ 150 MB 常驻权重量级），许可证允许随模组分发 | 二进制体积 + 许可证复核 |
| G6 | 可流式：支持**按句/按子句分块合成**，块间无爆音、无重复/吞字 | 连续播放 5 分钟无欠载（audio underrun 计数 = 0） |

> 音色克隆（zero-shot voice cloning）列为**可选加分项**，不作为硬指标——因为克隆类模型基本都在 0.3B 以上且以自回归/DiT 为主，与 G1/G4 直接冲突（见 §3、§5）。

---

## 2. 现状基线：MOSS-TTS-Nano-100M 为什么在 CPU 上天然慢

**基线事实（来自官方模型卡，非推测）**：`OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX`
— Apache-2.0，约 0.1B 参数，原生输出 **48 kHz / 2 声道**，**纯自回归 Audio-Tokenizer + LLM** 架构，ONNX 被拆成 `prefill` / `decode_step`（带 KV cache）/ `local_decoder` / `local_cached_step` / `local_fixed_sampled_frame` 五张图，配套 `MOSS-Audio-Tokenizer-Nano-ONNX` 做波形编解码。
来源：[HF 模型卡](https://hf-mirror.com/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX)（官方，2026-10 抓取）。

**实测（本项目，非外部来源）**：RTF ≈ **1.15**（48 kHz 输出，i5-13500HX + RTX 4060 Laptop）。即 320 ms 音频需 ~355 ms 墙钟 → **必然欠载**。

### 2.1 结构性原因（与 VITS/MeloTTS 这类非自回归前馈模型的本质差异）

| # | 机理 | 对 RTF 的量化影响 |
|---|---|---|
| A1 | **逐 token 串行 decode**：音频被离散成 codec token，必须一帧一帧解码，第 t 帧依赖第 t−1 帧。假设 codec 帧率 ~25 帧/s（48 kHz 下 ~40 ms/帧，**帧率数值 UNVERIFIED，按量级论证**），则每 1 秒音频需 ≥ 25 次完整前向 | 计算量 ≈ 25 ×（单步前向成本），而 VITS 只要 1 次 |
| A2 | **batch=1 的访存瓶颈**：每步都要把 0.1B×4 B ≈ 400 MB 权重从内存搬一遍。25 步/s → **~10 GB/s 的权重带宽需求**，已接近 4 核笔记本可用有效带宽；游戏同时跑时争抢更严重 | 决定了下限：**无法靠多线程救回来**，因为每步的矩阵都很小、并行度不足 |
| A3 | **串行依赖压制多核扩展**：4 核只能靠算子内并行（intra-op）分摊单个小矩阵，扩展比远低于 1 | 有效加速比通常 1.5–2.5×（4 核 vs 1 核），而 VITS 类可接近 3–3.5× |
| A4 | **多张图 × 多次 `Run()`**：每帧要在图之间切换（global decode → local decoder → codec decode），每次 `Run()` 有 ~0.1–1 ms 固定开销 | 25 帧/s × 3 次调用 ≈ 7.5–75 ms/s → 在 RTF 上叠加约 1%–7% |
| A5 | **48 kHz 双声道 codec 解码器额外成本**：vocoder 每秒要产出 96k 采样点（VITS 类多为 16–24 kHz 单声道，产出量少 2–4×） | 解码器侧固定成本更高 |
| A6 | **FP32 ONNX on CPU**：无 fp16 加速；int8/bf16 需要额外量化且 AR 对量化误差更敏感 | 未量化时无捷径 |

**对照：VITS / MeloTTS 类**
- 一次前向就产出**整句（甚至整段）波形**：TextEncoder → DurationPredictor → Flow（若干耦合层）→ HiFi-GAN 声码器。**时间维完全并行**，无自回归依赖。
- 模型小（Piper medium 声学+声码器合计 63 MB ONNX；MeloTTS 中英双语 PyTorch checkpoint 198 MB，ONNX 导出后同量级或更小）。4 核可把 3–5 秒音频的时域计算铺满所有核，**接近线性扩展**。
- 代价：不能边算边出（延迟来自"整块"推理），但块长可做到 0.5–1.5 s 的子句级，配合游戏内对话节奏完全够用。

---

## 3. 候选对比表

> 说明：`RTF 来源` 列严格区分 **官方宣称** / **第三方实测**；没有可靠来源一律写「**无公开数据**」。参数量与体积分开写（参数量给架构规模，体积给实际下载/常驻量）。

### 3.1 第一梯队（非自回归 / 前馈，CPU 实时可行）

| 候选 | 参数量 / 体积 | 许可证 | 中文 | ONNX Runtime | 流式 | 音色克隆 | CPU RTF（来源 · CPU · 线程） |
|---|---|---|---|---|---|---|---|
| **Piper**（rhasspy/piper → OHF-Voice/piper1-gpl）× VITS | 中英双语单声学模型：`zh_CN-huayan-medium.onnx` = **63,201,294 B**（约 63 MB）；`low` 档更小 | **音色仓库 MIT**（rhasspy/piper-voices）；引擎有分裂：旧 `rhasspy/piper` = **MIT 但已 archived**，新 `piper1-gpl` = **GPL-3.0** | ✅ `zh_CN` 三个音色：`chaowen` / `huayan` / `xiao_ya` | ✅ 原生 ONNX（整模型单图） | 句级；另有**实验性分块流式导出**（encoder/decoder 拆分，PR #255 于 2024-04-24 合并，新版是否保留 **UNVERIFIED**） | ❌ 无零样本克隆（只能自己训练新音色） | **0.09**（medium）/ **0.55**（high）— 第三方 GitHub issue 自测，**Windows，CPU 型号与线程数未注明**；官方仓库描述只有 "A fast, local neural text to speech system"，**无 RTF 数字** |
| **MeloTTS / vits-melo-tts-zh_en** | VITS 系；官方 Chinese 仓库 `checkpoint.pth` = **207,770,124 B**（约 198 MB，含训练期组件）；ONNX 导出体积未公开（估 ~50–80 MB，**UNVERIFIED**） | **MIT**（官方模型卡明示 "free for both commercial and non-commercial use"） | ✅✅ **中文单模型内混读英文**（官方明示 "The Chinese speaker supports mixed Chinese and English"）；但**仅 1 个女声**（sherpa 转换版模型卡明示 "only a single female speaker ... for Chinese+English"） | ⚠️ **官方仓库不提供 ONNX 导出**；社区/icefall 侧有转换版 `csukuangfj/vits-melo-tts-zh_en` | 句级（模型本身非流式） | ❌ | **无公开数据**（官方仅宣称 "Fast enough for CPU real-time inference"，**无数字、无 CPU 型号**） |
| **Kokoro-82M** | **82M**；fp32 ONNX ≈ 328 MB（按 82M×4B 推算）、int8 ≈ 80–90 MB（社区量化版） | **Apache-2.0** | ⚠️ 支持但**质量差**：`VOICES.md` 中全部 8 个中文音色 Overall Grade = **D**，训练数据仅 **MM minutes 级** | ✅ 官方 pth 需自行导出；社区 `kokoro-onnx` 与 sherpa-onnx 已有转换权重 | ❌ 整句生成 | ❌（仅风格向量，可混合音色） | **0.124–0.144**（Supertonic 官方对比表，**M4 Pro CPU + ONNX**，线程未注明——竞品测量，属第三方）；**0.48–0.51 (fp32) / 0.62 (int8)**（第三方 sherpa-onnx issue，**Pixel 10 Pro / ARM，4 线程**） |
| **Supertonic 2** | **66M** | 代码 MIT / **权重 OpenRAIL-M** | ❌（en/ko/es/pt/fr，**无中文**） | ✅ 官方 ONNX，CPU 优先，GPU 未测 | 分块（chunk 推理）**UNVERIFIED** | ❌（固定预置音色；自定义需付费 Voice Builder） | **0.075**（第三方 sherpa-onnx PR 实测，**Intel Xeon Gold 6448Y，4 线程**，31.749 s 音频 / 2.425 s）；官方 **0.012–0.015**（M4 Pro CPU，2 steps） |
| **Supertonic 3** | 公开 ONNX 资产合计 **~99M**；int8 包 ~**138 MB**（duration_predictor 3.5M + text_encoder 35M + vector_estimator 75M + vocoder 25M） | 代码 MIT / **权重 OpenRAIL-M** | ❌ **31 种语言不含 zh**（en/ko/ja/ar/bg/cs/da/de/el/es/et/fi/fr/hi/hr/hu/id/it/lt/lv/nl/pl/pt/ro/ru/sk/sl/sv/tr/uk/vi） | ✅ 官方 + sherpa-onnx 均已支持 | 分块（**UNVERIFIED**） | ❌ | **0.184**（第三方 sherpa-onnx PR 实测，**1 线程**，macOS/Apple Silicon 宿主机，int8） |
| **Kitten TTS（nano 0.8）** | **15M**，文件 ~**50 MB** | **Apache-2.0** | ❌ 官方仅英文音色（8 个英文名） | ✅ sherpa-onnx 已支持 `kitten` 后端 | ❌ | ❌ | **无公开数据**（官方仅称 "runs without a GPU"） |
| **ZipVoice / ZipVoice-Distill**（k2-fsa） | 紧凑 flow-matching（Zipformer 估计器）；具体参数量 **UNVERIFIED** | 代码 **Apache-2.0** / **模型权重 CC-BY-NC**（因 Emilia 数据约束，**禁商用**） | ✅ zh + en，**零样本音色克隆** | ✅ sherpa-onnx 有 `zipvoice` 后端（C++/CPU） | 分块（chunk）**UNVERIFIED** | ✅ | **无公开数据**（论文只给相对加速："比 DiT 基线小 3×、快至 30×"，未给 4 核 CPU 绝对值）；需实测 |

### 3.2 第二梯队（自回归 / LLM-codec / DiT，CPU 实时不可行——列出以示已覆盖）

| 候选 | 参数量 / 体积 | 许可证 | 中文 | ONNX | 流式 | 克隆 | CPU RTF 与排除理由 |
|---|---|---|---|---|---|---|---|
| **MOSS-TTS-Nano-100M**（现状） | 0.1B；48 kHz 双声道 | Apache-2.0 | ✅ 多语 | ✅ 官方拆分图 | ✅ prefill/decode | — | **1.15（本项目实测）**；结构原因见 §2 |
| **NeuTTS Air**（neuphonic/neutts） | ~0.75B 级（Qwen0.5B 骨干 + NeuCodec），Q4/Q8 GGUF 可下 | GitHub 识别为 **NOASSERTION / Other** → **许可证需人工确认（UNVERIFIED）** | ❌ 英文 | 有 GGUF/ONNX 变体 | 分块 | ✅（few-sec 参考音频） | **0.338–0.390**（Supertonic 官方对比表，**M4 Pro CPU、Q8-GGUF**，线程未注明）。即便在单核极强的 M 系列上也只到 0.35，**4 核 x86 + 游戏争抢下大概率 > 0.7**（推断，非实测） |
| **CosyVoice 2** | 0.5B（LLM + chunk-aware causal flow matching） | **UNVERIFIED**（业界普遍为 Apache-2.0，本次未取到一手页） | ✅ | 无官方 ONNX，需自行导出 | ✅ 原生流式（论文卖点） | ✅ | **无公开 CPU 数据**；论文 [arXiv:2412.10117](https://arxiv.org/abs/2412.10117) 的延迟/RTF 全在 GPU 上报。0.5B AR-LLM 在 CPU 上 RTF 远超 1 → 排除 |
| **F5-TTS** | ~336M 级 DiT flow-matching（**UNVERIFIED**） | 代码 MIT / **权重 CC-BY-NC**（Emilia） | ✅ | 社区导出 | ❌ 整段 | ✅ | 无公开 CPU 数据；DiT + 多步 ODE 采样，CPU 上量级不可行 → 排除 |
| **Spark-TTS** | 0.5B（Qwen2.5 骨干 + BiCodec） | **UNVERIFIED** | ✅ | 社区 | 分块 | ✅ | 无公开 CPU 数据；LLM 自回归 → 排除 |
| **IndexTTS / IndexTTS2** | 1B 级以上（**UNVERIFIED**） | GitHub 识别为 **Other（自定义许可证）**，需人工确认 | ✅ | 社区 | ❌ | ✅ | 无公开 CPU 数据；GPT 式 AR + 大 codec → 排除 |
| **ChatTTS** | ~0.5B 级（**UNVERIFIED**），GPT + DVAE | **AGPL-3.0**（GitHub API 确证） | ✅ | 社区 ONNX | 分块 | ❌ | 无公开 CPU 数据；**AGPL-3.0 与"随模组分发"天然冲突**，即使性能达标也应排除 |
| **Pocket TTS**（Kyutai，2026-01） | **UNVERIFIED**（HF 权重仓库为 gated，无法读取） | 仓库 **MIT**（GitHub API 确证） | ❌（按 Kyutai 既有模型推断仅 en/fr，**UNVERIFIED**） | **UNVERIFIED** | **UNVERIFIED** | ✅（官方标题即 "with voice cloning that runs on CPU"） | **无公开数据**；官方定性宣称 "A TTS that fits in your CPU (and pocket)"。**因缺中文 + 数字缺失，本轮不作为候选** |

### 3.3 表外补充（值得在实测阶段加一条对照）

- sherpa-onnx 生态的中文 VITS/MatCha 模型（`vits-zh-hf-*`、`matcha-icefall-zh-baker` 等）：同为非自回归前馈、体积 30–100 MB、Apache-2.0 居多，理论上与 Piper/MeloTTS 同档。**但本次未取到其 CPU RTF 一手数据（无公开数据）**，且多为单说话人、无中英混读保证，故不列入主推。

---

## 4. 关键判断：4 核、无 GPU（或 GPU 被游戏占用）时，谁能真正做到 RTF ≤ 0.5

### 4.1 结论表

| 判定 | 候选 | 依据 |
|---|---|---|
| ✅ **能做到，且有实测支撑（同族/同量级）** | **Piper（VITS）medium/low**、**MeloTTS zh_en** | ① Piper medium 第三方实测 **0.09**；② 同族 82M 的 Kokoro 在 M4 Pro CPU 上 **0.12–0.14**；③ 架构上单次前向、时域可并行，4 核扩展比接近线性；④ Piper medium ONNX 仅 63 MB，算力需求与 4 核匹配。**注意：这些数字都不是在 i5-13500HX + 游戏负载下测的，属"同族 + 同量级"推断，必须由下一阶段实测确认。** |
| ⚠️ **可能做到，但依 CPU 微架构/线程数而定** | **Kokoro-82M**（fp32/int8）、**Supertonic 2/3**（若愿意放弃中文，或做"中文走 MeloTTS、英文走 Supertonic"的双模型路由） | ① Supertonic 2 在 **Xeon Gold 6448Y 4 线程 = 0.075**（该 CPU 有 AVX-512 + AMX，客户端 i5 无 AMX、无 AVX-512，**实际会更慢**，量级估计 ×1.5–3，UNVERIFIED）；② Kokoro 在 ARM 4 线程 fp32 = 0.48–0.51（已在边缘），x86 AVX2 通常比 ARM 快，但 82M 规模 + 两步式（duration/prosody 预测 + 声码）使其**不如 Piper 稳**；③ 二者中文质量都差（Kokoro 中文音色 D 级、Supertonic 无中文）。 |
| ❌ **做不到** | MOSS-TTS-Nano、NeuTTS Air、ChatTTS、CosyVoice2、F5-TTS、Spark-TTS、IndexTTS | 自回归逐 token 串行 + batch=1 访存瓶颈（§2），实测/推断均 ≥ 0.35（NeuTTS 在 M4 Pro 上 0.34–0.39 已是该类最好的边缘值），4 核 x86 上无法稳定 ≤ 0.5。 |

### 4.2 判断依据的三条硬逻辑

1. **并行结构决定上限**：非自回归前馈模型的时域并行度 = 音频帧数（几百到上千），4 核能吃满；AR 模型的并行度 = 1（每帧），只能靠算子内并行，4 核有效加速比顶多 2× 左右。
2. **每秒钟需要搬多少权重**：VITS 每秒音频只跑 1 次前向（一次搬 ~63 MB）；MOSS 每秒要跑 ~25 次（每秒搬 ~10 GB）。前者受算力限制，后者受内存带宽限制，而笔记本内存带宽是与 GPU/游戏共享的稀缺资源。
3. **实测锚点一致性**：所有 < 0.2 的公开数字（Piper 0.09、Supertonic 0.075、Kokoro 0.12–0.14）都来自非自回归模型；所有自回归模型（NeuTTS 0.34、MOSS 1.15）都 ≥ 0.34。**没有反例。**

### 4.3 需要提醒的反面信号

- sherpa-onnx issue #2762（2025-11，中文用户原话："没有找到 rtf 在 1 以内且支持中英双语的模型"）说明：**"中英双语 + RTF < 1" 在实践中确实是个坑**——多数人是被自回归模型坑住的，但也说明这类结论必须自测。
- 上述所有数字均在**空载**机器上测得。游戏 + ORT 抢 4 个核时，**保守按 1.3–1.8× 劣化预留余量**（经验系数，UNVERIFIED）。因此选型时应瞄 **≤ 0.3 而不是压线 0.5**。

---

## 5. 如果继续用 MOSS：业界加速手段清单与预期收益

> 前提：以下均**不改模型结构**（改结构 = 换模型，见 §6）。收益量级为经验区间，标注 UNVERIFIED 的为无一手文档支撑。

| # | 手段 | 具体做法 | 预期收益量级 | 备注 / 风险 |
|---|---|---|---|---|
| 1 | **ONNX 图优化** | `graph_optimization_level = ORT_ENABLE_ALL`（常量折叠、算子融合） | **1.05–1.15×**（UNVERIFIED 经验值） | 必须做，收益小但零风险 |
| 2 | **动态 int8 量化（只量化 global transformer）** | `quantize_dynamic(weight_type=QInt8, per_channel=True)`，codec/local decoder 保持 fp32 | **1.5–2.5×**（有 AVX2 的客户端 CPU）；官方称在支持 VNNI 的 CPU 上最高可达 ~4×，但 **i5-13500HX（Raptor Lake）无 VNNI/AMX**，拿不到 4× | AR 模型对量化误差敏感，可能音质下降/发音含糊；必须 A/B 视听验收 |
| 3 | **静态 QDQ 量化 + 校准** | 用真实文本/参考音频做校准集 | 比动态量化再 **+10–30%** | 工程成本高（要校准数据管线），收益边际递减 |
| 4 | **MatMulNBits 4-bit（ORT ≥1.17）** | 只对 global 图的大矩阵做 int4 | **2–4×**（UNVERIFIED）；体积 ÷4 | 精度损失风险最高，AR 逐帧误差会累积 → 容易爆音/走音 |
| 5 | **线程配置正确化** | `intra_op_num_threads = 4`（**物理核数**，不是 20）、`inter_op_num_threads = 1`、`execution_mode = ORT_SEQUENTIAL`、关闭 spinning | **1.1–1.3×** | 默认线程数会开到 20，在 4 核可用时严重过订阅，是"白捡"的收益 |
| 6 | **会话级优化** | 固定输入 shape（避免动态维反复重分配）、`enable_cpu_mem_arena=0`（形状多变时）、`enable_mem_reuse=0` | **1.02–1.10×** | AR 每步 shape 固定，收益偏小 |
| 7 | **IO binding / 预分配缓冲** | 用 `IoBinding` 绑定输入输出，避免每帧 host↔ORT 拷贝 | **1.01–1.05×** | CPU EP 下收益有限（本来就是 host 内存） |
| 8 | **合并 `Run()` 调用** | 把 `decode_step` 与 `local_cached_step` 合并成一张图 / 同 session 复用 | **1.01–1.07×**（对应 §2 A4 的每次 Run 固定开销） | 需要重新导出 ONNX，属于"改模型资产"级别的工作量 |
| 9 | **KV cache 复用 / 避免重复 prefill** | 官方导出已带 decode-step KV cache；确认流式调用不重复 prefill | 不做会 **×2 以上**劣化（做对属于"避免损失"） | 这是最容易在接入层写错的地方 |
| 10 | **批大小策略** | 流式时 batch=1（延迟最优）；若允许"预生成 + 缓冲播放"，可 batch 4–8 提升吞吐 | 吞吐 **1.5–3×**，但**首包延迟变差** | 与 G2 冲突，只在"先攒一句再播"的场景可用 |
| 11 | **OpenVINO EP（Intel CPU）** | ORT `OpenVINOExecutionProvider`，让 oneDNN/OpenVINO 接管 int8 GEMM | **1.2–2×**（UNVERIFIED） | 需额外打包 OpenVINO 运行库（体积 +），与现有 natives 打包方式冲突 |
| 12 | **降采样率/降帧率（模型侧）** | 输出 48 kHz → 24 kHz、codec 帧长拉长 | 计算量随帧率近似线性下降（帧率减半 ≈ **1.5–2×**） | 需要重训/重导出 codec，本质是换模型 |
| 13 | **Vulkan/GPU EP** | natives 里所谓的 "Vulkan 后端" | **UNVERIFIED，且不建议依赖** | ONNX Runtime **没有官方 Vulkan EP**；若实际是社区 EP 或 WebGPU(Dawn) 路径，成熟度/驱动兼容性不可控；目标场景 GPU 被游戏占用 |

**综合判断**：把 #1–#9 全做到，现实预期是 **1.8–2.8×**，即 RTF 1.15 → **0.4–0.65**。仍可能卡在 0.5 附近，且要承担量化音质劣化 + 大量导出/调参工程。**结论：加速手段值得做（尤其 #5 这种零成本的），但不足以把 MOSS 变成"稳态 RTF ≪ 1"的方案。**

---

## 6. 结论与建议

### 6.1 首选替换：**MeloTTS / `vits-melo-tts-zh_en`（VITS 前馈，MIT）**

**理由**
1. **架构对路**：非自回归单次前向，是唯一"中文 + 英文混读"且属于 ≤0.2 RTF 梯队（§4）的成熟模型。
2. **官方明示 CPU 实时**（"Fast enough for CPU real-time inference"）+ MIT（商用/分发无碍）。
3. **项目里已经在用**（用户提供），说明前后端、词典/G2P、音频管线已经跑通过一遍——这是最大的隐性成本节省。工程里也已有 ONNX Runtime 模块（仅从 `tianshu-client/.../ClientOnnxRuntimeModule*.java` 的文件清单得知，未读源码），替换模型不需要新建推理栈。
4. 中文单模型内直接混读英文 → 不需要做"中英文本路由 + 双模型切换"，这在 Minecraft 语境（"把 32 个 oak_log 放进 chest"）里是刚需。

**风险**
- **音色与自然度上限低**：只有 1 个女声（sherpa 转换版模型卡明示），语调偏平；对"NPC 陪伴感"型产品是明显短板。
- **ONNX 化非官方**：官方仓库不给 ONNX，须用社区导出（`csukuangfj/vits-melo-tts-zh_en`）。中文前端若依赖 BERT 特征，导出时是否已固化 / 是否需旁挂一个小 BERT —— **UNVERIFIED，必须在下阶段第一件事验证**（直接决定集成复杂度与 RTF）。
- **RTF 无一手数字**：官方只有定性宣称，**必须实测**（同族 Piper medium 0.09 只能作类比）。

### 6.2 次选/兜底：**Piper `zh_CN-huayan-medium`（63 MB）+ 英文音色分路**

**理由**：VITS 同族、体积最小、生态最成熟、有第三方 0.09 这一档的实测；`zh_CN` 有 3 个音色可选（chaowen/huayan/xiao_ya），比 MeloTTS 的单女声多一点选择；英文质量在业内有口碑。

**风险 / 必须规避的坑**
- **许可证陷阱**：新仓库 `OHF-Voice/piper1-gpl` 是 **GPL-3.0**（GitHub API 确证），旧 `rhasspy/piper` 是 MIT 但已 archived。**正确姿势：不引入 piper1-gpl 的 C++ 代码，直接用 MIT 的 ONNX Runtime（或 Apache-2.0 的 sherpa-onnx）加载 `piper-voices`（MIT）的 ONNX 权重**——这样引擎与权重都不会传染 GPL。
- **中文前端弱**：Piper 的中文音素化能力弱于 MeloTTS（多音字/轻声/儿化处理粗糙），中文自然度预计低于 MeloTTS；且**不能用一个模型中英混读**，需要文本切分 + 双语模型路由（工程复杂度上升）。
- 第三方 0.09 的测量环境（CPU/线程）未注明，只能当量级参考。

### 6.3 明确不建议

- **Kokoro 用于中文**：中文音色全员 Grade D、训练数据 "MM minutes"，与 G3 正面冲突（英文可用，可作"英文专用轻量音源"备选）。
- **Supertonic 2/3**：速度非常好（4 线程 0.075），但**没有中文**，且权重为 OpenRAIL-M（带使用限制条款，需法务过一眼）；只能作为"英文/多语场景"的候选。
- **NeuTTS Air / ChatTTS / CosyVoice2 / F5-TTS / Spark-TTS / IndexTTS**：§4 已判死；ChatTTS 另叠加 **AGPL-3.0**，IndexTTS 与 NeuTTS 为自定义许可证（NOASSERTION）。
- **靠加速 MOSS 达标**：§5 综合预期最好 0.4–0.65，不值得作为主路径。

### 6.4 落地顺序建议（下一阶段，非本任务）

| 步骤 | 内容 | 责任阶段 |
|---|---|---|
| 1 | 用 **4 线程**在目标机（游戏运行中）实测 `vits-melo-tts-zh_en` 与 `zh_CN-huayan-medium` 的 RTF/首包延迟，产出与 §3 同格式的对照表 | D（实测/复核） |
| 2 | 验证 MeloTTS ONNX 导出的中文前端是否自包含（BERT 特征是否被固化）；若需旁挂 BERT，评估其 RTF 与体积 | D |
| 3 | 确认引擎侧许可证路径（ORT MIT / sherpa-onnx Apache-2.0 + 权重 MIT），出一页许可证结论 | D + 人工 |
| 4 | 按"子句切分 → 单块合成 → 环形缓冲播放"的骨架改造音频管线（保持现有 ORT 会话生命周期） | C（内容） |
| 5 | 配置/枚举/文案骨架（音色、语言路由、回退模型） | B（骨架） |

---

## 7. 风险清单

| 类别 | 风险 | 影响 | 缓解 |
|---|---|---|---|
| 证据 | 关键表（sherpa-onnx RTF 汇总）与多数博客不可达，部分数字只有单点来源 | 选型依据强度打折 | 所有 UNVERIFIED 项在 §3 已标注；下阶段以目标机实测为准 |
| 测量 | 公开 RTF 多在空载机/不同 CPU 上测得；目标机有游戏抢核 | 实测可能比预期慢 1.3–1.8× | 选型瞄 ≤0.3；实测用 `intra_op=4` 并记录 CPU 占用 |
| 中文质量 | MeloTTS 单女声、Piper 中文前端弱、Kokoro 中文 D 级 | 产品听感/陪伴感不足 | 先做 30 句人工盲测再定音色；必要时接受"英文 Supertonic + 中文 MeloTTS"双路由 |
| 技术集成 | MeloTTS 官方无 ONNX，中文 BERT 前端可能未固化 | 集成成本可能高于预期 | 步骤 2 优先验证；有现成 `vits-melo-tts-zh_en` 说明社区路径可行 |
| 许可证 | piper1-gpl = **GPL-3.0**；ZipVoice 权重 = **CC-BY-NC**；ChatTTS = **AGPL-3.0**；IndexTTS/NeuTTS = 自定义/NOASSERTION；Supertonic = **OpenRAIL-M** | 分发合规风险 | 主路径只用 MIT/Apache-2.0 组合（MeloTTS 权重 MIT、piper-voices MIT、ORT MIT、sherpa-onnx Apache-2.0）；GPL/NC/RAIL 一律不走主路径 |
| 流式 | VITS 类模型本身不产帧级流式，只能"子句级分块" | 首包延迟取决于子句长度 | 子句切分 + 短句优先 + 静音填充；G2 设 800 ms |
| 环境 | 无 CUDA，natives 只有 CPU(+所谓 Vulkan)；Vulkan 路径未证实 | 加速手段受限 | §5 只列 CPU 侧手段；Vulkan 标 UNVERIFIED，不排期 |

---

## 8. 来源清单（抓取时间 2026-10 下旬）

**官方（模型/厂商发布页）**
1. MOSS-TTS-Nano-100M-ONNX 模型卡（Apache-2.0、0.1B、48 kHz、AR、prefill/decode 拆分图）— https://hf-mirror.com/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX
2. MeloTTS 模型卡（MIT、"CPU real-time inference"、中英混读、单女声说明）— https://hf-mirror.com/myshell-ai/MeloTTS-Chinese ；ONNX 转换版说明 — https://hf-mirror.com/csukuangfj/vits-melo-tts-zh_en
3. Kokoro-82M 模型卡（82M、Apache-2.0、StyleTTS2+ISTFTNet）— https://hf-mirror.com/hexgrad/Kokoro-82M ；音色质量表（中文全 D、MM minutes）— https://hf-mirror.com/hexgrad/Kokoro-82M/blob/main/VOICES.md
4. Supertonic（66M、OpenRAIL-M、官方 M4 Pro CPU RTF 对比表含 Kokoro/NeuTTS 基线）— https://hf-mirror.com/Supertone/supertonic
5. Supertonic 2（5 语言，无中文）— https://hf-mirror.com/Supertone/supertonic-2 ；Supertonic 3（31 语言，**不含 zh**，~99M，int8 体积）— https://hf-mirror.com/Supertone/supertonic-3
6. Kitten TTS nano 0.8（15M、~50 MB、Apache-2.0、StyleTTS2）— https://hf-mirror.com/KittenML/kitten-tts-nano-0.8-fp32
7. ZipVoice（代码 Apache-2.0 / 权重 CC-BY-NC、zh+en 零样本克隆）— https://hf-mirror.com/k2-fsa/ZipVoice ；论文摘要 — https://arxiv.org/abs/2506.13053
8. Piper 音色仓库（MIT；zh 语言标记；`zh_CN-huayan-medium.onnx` = 63,201,294 B，HF API tree 接口）— https://hf-mirror.com/rhasspy/piper-voices
9. Pocket TTS（Kyutai，标题即"runs on CPU"）— https://kyutai.org/blog/2026-01-13-pocket-tts/ ；仓库 MIT — https://api.github.com/repos/kyutai-labs/pocket-tts

**第三方实测（GitHub PR/issue 中的运行日志）**
10. sherpa-onnx PR #3094「Add Supertonic2 TTS support」：Xeon Gold 6448Y、**4 线程、RTF 0.075**（31.749 s 音频 / 2.425 s）— https://github.com/k2-fsa/sherpa-onnx/pull/3094
11. sherpa-onnx PR #3609「Release Supertonic3 TTS models」：int8、**1 线程、RTF 0.184**（2.855 s / 15.510 s）— https://github.com/k2-fsa/sherpa-onnx/pull/3609
12. sherpa-onnx issue #3754：Kokoro **Pixel 10 Pro（ARM）、4 线程、fp32 0.48–0.51 / int8 0.62** — https://github.com/k2-fsa/sherpa-onnx/issues/3754
13. sherpa-onnx issue #2762：中文用户反馈"找不到 RTF<1 且支持中英双语"的模型 — https://github.com/k2-fsa/sherpa-onnx/issues/2762
14. rhasspy/piper issue #431：medium **RTF ~0.09** / high ~0.55（Windows，CPU/线程未注明）— https://github.com/rhasspy/piper/issues/431
15. rhasspy/piper PR #255：分块流式导出（encoder/decoder 拆分），**2024-04-24 合并** — https://github.com/rhasspy/piper/pull/255
16. CosyVoice 2 论文（流式 / 低延迟 / GPU 侧指标）— https://arxiv.org/abs/2412.10117

**许可证元数据（GitHub API，逐仓库确认）**
17. `rhasspy/piper` → **MIT**（archived）— https://api.github.com/repos/rhasspy/piper
18. `OHF-Voice/piper1-gpl` → **GPL-3.0** — https://api.github.com/repos/OHF-Voice/piper1-gpl
19. `2noise/ChatTTS` → **AGPL-3.0** — https://api.github.com/repos/2noise/ChatTTS
20. `index-tts/index-tts` → **NOASSERTION（自定义）** — https://api.github.com/repos/index-tts/index-tts
21. `neuphonic/neutts` → **NOASSERTION（自定义）** — https://api.github.com/repos/neuphonic/neutts
22. `kyutai-labs/pocket-tts` → **MIT** — https://api.github.com/repos/kyutai-labs/pocket-tts

> 下列条目**未取到一手页面**，按 UNVERIFIED 处理：CosyVoice / F5-TTS / Spark-TTS 的具体许可证与参数量；MOSS 编解码器帧率；Piper `piper1-gpl` 是否保留流式导出；Pocket TTS 的语言与参数量；Kokoro 中文 ONNX 实测量；sherpa-onnx 官方 RTF 汇总表。

---

## 9. 结论摘要（≤10 行）

1. MOSS-TTS-Nano 慢是结构性的：自回归逐 token 解码 + batch=1，每秒要跑 ~25 次前向、搬 ~10 GB 权重，4 核救不回来，RTF 1.15 合理。
2. 反例很清晰：所有公开 ≤0.2 的数字（Piper medium 0.09、Supertonic 0.075@4 线程、Kokoro 0.12–0.14）无一例外都是非自回归前馈模型。
3. 算法上唯一同时满足"中文+英文混读 + CPU 实时 + 许可证干净"的成熟路线是 **VITS 族**：MeloTTS zh_en（MIT）与 Piper zh_CN-huayan-medium（权重 MIT）。
4. **首选 MeloTTS / vits-melo-tts-zh_en**：官方宣称 CPU 实时、MIT、单模型中英混读、项目里已在用，接入成本最低；代价是单女声、语调平、ONNX 为社区导出。
5. **次选 Piper `zh_CN-huayan-medium`（63 MB）**：体积最小、生态最成熟、有 0.09 级实测；但中文前端弱、不能混读，需双语路由。
6. 许可证红线：引擎别碰 `piper1-gpl`（GPL-3.0），用 ORT(MIT) 或 sherpa-onnx(Apache-2.0) 直接跑 MIT 权重即可绕开。
7. 明确排除：Kokoro（中文 D 级）、Supertonic（无中文，OpenRAIL-M）、ZipVoice（CC-BY-NC）、ChatTTS（AGPL）、NeuTTS/CosyVoice2/F5/Spark/IndexTTS（AR/DiT，CPU 不可实时）。
8. 若坚持 MOSS：图优化 + **intra_op=4** + int8 动态量化 + IO binding + 合并 Run 全做完，现实预期 1.8–2.8×（RTF→0.4–0.65），仍压线，不推荐当主路径。
9. 所有公开 RTF 都是空载测得，目标机有游戏抢核，**选型应瞄 ≤0.3**，并按 1.3–1.8× 劣化预留余量。
10. 下一步只有一件事最重要：在 i5-13500HX、4 线程、游戏运行状态下实测 MeloTTS 与 Piper zh 的 RTF 与首包延迟，并验证 MeloTTS 的 ONNX 中文前端是否自包含。
