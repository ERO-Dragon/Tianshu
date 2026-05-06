Sherpa-ONNX TTS 自动下载与加载系统 - 开发手册
1. 系统概述
本系统用于在 Minecraft Mod 中实现 TTS（文本转语音）模型的自动化管理。整体数据流向为：
Agent 爬取的 JSON 列表 -> Java 本地缓存与 GUI 展示 -> HuggingFaceDownloader 智能下载 -> Sherpa-ONNX 运行时装配。

2. 模型元数据结构规范 (JSON Schema)
以下是单个模型的完整数据结构定义，所有字段均由 Agent 预处理生成，Java 侧直接反序列化使用。


{
  "author": "csukuangfj",              // [固定] 仓库作者
  "name": "kokoro-multi-lang-v1_0",    // [核心] 模型短名，直接作为本地保存的文件夹名
  "id": "csukuangfj/kokoro-multi-lang-v1_0", // [核心] HuggingFace repo_id，用于拼接下载链接
  "size": 314572800,                   // [UI] 预估体积(字节)，用于在 GUI 中提示玩家所需磁盘空间
  "needVocoder": false,                // [逻辑] 是否需要额外下载声码器(Matcha模型为true，默认走官方vocoders仓库)
  "lang": ["en", "zh"],                // [UI] 支持的语言标签，用于玩家按语言筛选模型
  "pinned": false,                     // [UI] 是否为精选/推荐模型，true 则在列表中置顶并加星号

  // ---- 以下为运行时装配所需字段(路径均相对于仓库根目录) ----
  "modelFiles": ["model.onnx"],        // [加载] 仓库内所有的 .onnx 文件。若有多个(如 int8 量化版)，Java 侧需按优先级挑选一个主模型加载
  "dataDir": "espeak-ng-data",         // [加载] 文本前端数据目录(通常为 espeak-ng-data)，若为 null 则无需配置
  "lexiconFiles": ["lexicon-zh.txt"],  // [加载] 发音词典数组。存在则需全量加载(多个用逗号拼接传给底层)，若为 null 则跳过
  "ruleFsts": ["number-zh.fst"],       // [加载] 数字/日期正则化规则数组。存在则全量加载，若为 null 则跳过
  "voicesFile": "voices.bin"           // [加载] 音色特征文件。若为 null 则跳过
}
3. 下载引擎规范
Java 侧通过调用 HuggingFaceDownloader.downloadModelFiles(repoId, targetDir, ...) 执行下载。该引擎具备以下特性：

3.1 智能双源路由 (无感切换)
机制：首次调用时，发起一次 800ms 超时的底层 TCP 探测（连接 huggingface.co:443）。
命中：国内被墙环境 TCP 必然超时，自动切换为 https://hf-mirror.com；海外环境秒连，保持使用 https://huggingface.co 。
缓存：探测结果缓存在静态变量中，游戏运行期间全局只测一次，不增加后续请求延迟。
3.2 增量全量同步策略
不依赖 JSON 精准指路：为防止 JSON 漏写必需的 .fst 导致模型损坏，引擎不按 JSON 里的字段去“点名下载”，而是调用 /api/models/{id}/tree/main 拿到整棵文件树，全量下载到本地。
增量跳过：如果本地文件已存在（通过 skipExisting=true 控制），直接跳过，支持断点续传。
3.3 严格黑名单过滤 (防垃圾文件)
全量下载时，以下文件将在本地拦截，绝对不发起 HTTP 请求：

dict/ 整个目录：包含 jieba 分词的训练遗留物（如 hmm_model.utf8, jieba.dict.utf8 等），Java 运行时完全不需要。
*.py 文件：Python 训练/导出脚本。
.gitattributes 文件：Git LFS 指针配置。
readme* 文件：任何大小写的说明文档。
3.4 健壮性保障
防穿越：所有解析出的相对路径，必须经过 normalize().startsWith(targetDir) 校验，防止恶意仓库构造 ../../ 路径。
原子写入：大文件先下载为 .tmp 后缀，下载完成后再通过 Files.move 原子替换为目标文件，防止下载到一半崩溃留下残缺文件。
指数退避重试：遇到网络波动报错，自动重试 3 次，每次间隔递增。
4. 运行时装配规范
模型下载到 config/tts/models/{name}/ 后，需将 JSON 中的路径字段与本地绝对路径拼接，注入到 sherpa-onnx 的 Config 中。

4.1 挑选主模型 (modelFiles 处理逻辑)
仓库里可能有多个 .onnx，按以下优先级挑选唯一一个作为主声学模型：

优先级 1：找后缀为 .pack.onnx 的文件（官方单文件打包版，内含所有配置）。
优先级 2：找名字严格等于 model.onnx 的文件（标准版）。
优先级 3：取列表里的第一个文件。
挑出后，将 本地目录 + 文件名 赋给 config.vits.model 或 config.matcha.acousticModel。
4.2 附加组件装配 (有则装配，无则留空)
以下配置相互不冲突，属于流水线上的不同环节，有就一定得启用：

文本前端：若 dataDir != null，赋值 config.dataDir = 本地目录/dataDir。
发音词典：若 lexiconFiles != null，将数组用逗号拼接成一个字符串（如 path/lex1.txt,path/lex2.txt），赋值给 config.lexicon。
数字规则：若 ruleFsts != null，同理逗号拼接赋值给 config.ttsRuleFsts。
音色文件：若 voicesFile != null，赋值给特定的音色配置项（如 Kokoro 专属的 config.kokoro.voices）。
4.3 声码器补全 (needVocoder 处理)
若 JSON 中 needVocoder == true（通常是 Matcha 系列模型）：

不在当前仓库找声码器。
需要静默触发下载官方声码器仓库：csukuangfj/sherpa-onnx-vocoders。
下载后，从中挑选出 .onnx 文件，赋值给 config.matcha.vocoder。
一）与 JSON 对应的 POJO（Java Bean）
注意：下面用到了 Jackson（@JsonProperty），这是目前 Minecraft 模组生态里最常用的 JSON 库之一。如果你项目里是 Gson，叫 AI 把 @JsonProperty 全删掉即可。


import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class TtsModelInfo {
    // ---- 你原有的字段 ----
    public String author;
    public String name;
    public String id;        // repo_id，例如 "csukuangfj/vits-piper-en_US-glados"
    public long size;
    public boolean pinned;
    public boolean needVocoder;
    public List<String> lang;

    // ---- 新增的“仓库内文件路径”字段（相对于仓库根目录） ----
    public List<String> modelFiles;   // 例如 ["model.onnx"] 或 ["xxx.pack.onnx"]
    public String dataDir;            // 例如 "espeak-ng-data" 或 null
    public List<String> lexiconFiles; // 例如 ["lexicon-us-en.txt","lexicon-zh.txt"] 或 null
    public List<String> ruleFsts;     // 例如 ["phone-zh.fst","number-zh.fst"] 或 null
    public String voicesFile;         // 例如 "voices.bin" 或 null
}
二）核心：从 HuggingFace 仓库或hf-mirror.com下载指定文件的 Service
要点：

用 /api/models/{id}/tree/{revision} 拿到整棵文件树，按 type=file 过滤【对应你引用里说的“用 type 过滤目录，而不要靠后缀猜”】。
只下载“这次 JSON 里声明的文件”。
支持 hf-mirror（只要把域名改掉）。
包含：
路径穿越防护
超时 + 重试
已存在跳过（可配）
具体的函数方法如下，可供参考
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class HuggingFaceDownloader {

    private static final String HF_OFFICIAL = "https://huggingface.co" ;
    private static final String HF_MIRROR = "https://hf-mirror.com" ;
    
    // 缓存探测结果，全局只测一次
    private static String activeBaseUrl = null;
    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * 自动探测网络环境，决定使用 HuggingFace 官方源还是国内镜像源
     * 原理：尝试建立到 huggingface.co 的 TCP 连接（不发送数据），超时极短。
     */
    public static synchronized String getActiveBaseUrl() {
        if (activeBaseUrl != null) {
            return activeBaseUrl;
        }
        try (Socket socket = new Socket()) {
            // 设置 800 毫秒超时。如果网络被墙或极差，800ms 内一定会抛异常
            socket.connect(new InetSocketAddress("huggingface.co", 443), 800);
            activeBaseUrl = HF_OFFICIAL; // 连通了，走官方
        } catch (IOException e) {
            activeBaseUrl = HF_MIRROR;   // 连不通，走镜像
        }
        return activeBaseUrl;
    }

    /**
     * 如果玩家在网络环境变化后（比如从家里拿到外面），需要强制重新探测，可以调用这个方法
     */
    public static synchronized void resetDomainCache() {
        activeBaseUrl = null;
    }

    /**
     * 下载指定 HuggingFace 仓库的所有必需文件到本地目录
     * 
     * @param repoId      仓库 ID，例如 "csukuangfj/vits-piper-en_US-glados"
     * @param targetDir   本地保存目录
     * @param revision    分支或版本，例如 "main"
     * @param skipExisting 是否跳过本地已存在的文件
     * @param maxRetries  下载失败时的最大重试次数
     */
    public static void downloadModelFiles(
            String repoId,
            Path targetDir,
            String revision,
            boolean skipExisting,
            int maxRetries
    ) throws Exception {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(targetDir, "targetDir");

        Files.createDirectories(targetDir);

        // 1. 获取当前应该使用的域名（自动判断国内外）
        String baseUrl = getActiveBaseUrl();

        // 2. 拿到整棵文件树
        List<HfFileEntry> allFiles = fetchFileTree(baseUrl, repoId, revision);

        // 3. 过滤掉不需要下载的垃圾文件
        List<HfFileEntry> toDownload = allFiles.stream()
                .filter(f -> !shouldSkipFile(f.path))
                .collect(Collectors.toList());

        // 4. 逐个下载
        for (HfFileEntry file : toDownload) {
            Path localPath = targetDir.resolve(file.path).normalize();
            ensureWithinTarget(localPath, targetDir);

            if (skipExisting && Files.exists(localPath)) {
                continue;
            }

            String resolveUrl = buildResolveUrl(baseUrl, repoId, revision, file.path);
            downloadFile(resolveUrl, localPath, maxRetries);
        }
    }

    /**
     * 文件黑名单过滤逻辑（排除源码、说明文档和 git 配置）
     */
    private static boolean shouldSkipFile(String path) {
        // 统一转小写判断，避免大小写问题（如 README.md 或 readme.txt）
        String lowerPath = path.toLowerCase();
        
        // 排除整个 dict 目录（这是 Python jieba 分词的训练遗留物，运行时不需要）
        if (lowerPath.startsWith("dict/") || lowerPath.startsWith("dict\\")) {
            return true;
        }
        // 1. 排除 .py 结尾的 Python 源码
        if (lowerPath.endsWith(".py")) {
            return true;
        }
        
        // 2. 提取纯文件名，排除特定的配置和说明文件
        String fileName = lowerPath;
        int lastSlash = lowerPath.lastIndexOf('/');
        if (lastSlash != -1) {
            fileName = lowerPath.substring(lastSlash + 1);
        }

        if (fileName.equals(".gitattributes")) {
            return true;
        }
        
        // 排除所有 readme 开头的文件 (如 readme.md, readme.txt)
        if (fileName.startsWith("readme.") || fileName.equals("readme")) {
            return true;
        }

        return false;
    }

    /**
     * 调用 HF API 获取文件树
     */
    private static List<HfFileEntry> fetchFileTree(String baseUrl, String repoId, String revision) throws Exception {
        String url = String.format(
                "%s/api/models/%s/tree/%s?recursive=true&expand=false",
                baseUrl,
                URLEncoder.encode(repoId, StandardCharsets.UTF_8),
                URLEncoder.encode(revision, StandardCharsets.UTF_8)
        );

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        conn.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        conn.setRequestMethod("GET");

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HF tree API failed. repo=" + repoId + " code=" + code);
        }

        try (InputStream is = conn.getInputStream()) {
            JsonNode root = OM.readTree(is);
            List<HfFileEntry> result = new ArrayList<>();
            for (JsonNode node : root) {
                // 只要文件，不要目录
                if (!"file".equalsIgnoreCase(node.path("type").asText(""))) {
                    continue;
                }
                result.add(new HfFileEntry(node.path("path").asText()));
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 拼接单文件下载直链
     */
    private static String buildResolveUrl(String baseUrl, String repoId, String revision, String filePath) {
        String[] segments = filePath.split("/");
        String encodedPath = Arrays.stream(segments)
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));

        return String.format(
                "%s/%s/resolve/%s/%s",
                baseUrl,
                URLEncoder.encode(repoId, StandardCharsets.UTF_8),
                URLEncoder.encode(revision, StandardCharsets.UTF_8),
                encodedPath
        );
    }

    /**
     * 带重试的单文件下载逻辑（写临时文件再原子替换）
     */
    private static void downloadFile(String url, Path target, int maxRetries) throws IOException {
        Files.createDirectories(target.getParent());
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
                conn.setReadTimeout((int) Duration.ofSeconds(60).toMillis()); // 模型文件大，读超时给长点
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "MC-Mod-TTS-Downloader/1.0");

                if (conn.getResponseCode() != 200) {
                    throw new IOException("HTTP " + conn.getResponseCode());
                }

                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                } finally {
                    conn.disconnect();
                }

                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return; 
            } catch (IOException e) {
                lastException = e;
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new IOException("Failed after " + maxRetries + " retries: " + url, lastException);
    }

    private static void ensureWithinTarget(Path path, Path targetDir) throws IOException {
        if (!path.normalize().startsWith(targetDir.normalize())) {
            throw new IOException("Path traversal detected: " + path);
        }
    }

    private static class HfFileEntry {
        final String path;
        HfFileEntry(String path) { this.path = path; }
    }
}
怎么调用它（极简版）
因为改成了“全量同步”，所以你甚至不需要把整个 JSON 传进去了，只需要传 id 就行。JSON 里的那些 modelFiles、lexiconFiles 留着，等下载完了，在 Java 里加载 sherpa-onnx 模型时再去读。


// 假设这是你从 JSON 里拿到的对象
TtsModelInfo model = ...; 

// 直接调用，只传 repoId 和要保存的本地文件夹名字
HuggingFaceDownloader.downloadModelFiles(
        model.id,                                  // 例如 "csukuangfj/vits-piper-en_US-glados"
        Paths.get("config/tts/models", model.name),// 保存到本地哪个目录
        "main",                                    // 分支
        true,                                      // 如果已经下过一半了，跳过已存在的
        3                                          // 失败重试 3 次
);