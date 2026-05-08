package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AsrModelDownloader {

    private static final String HF_OFFICIAL = "https://huggingface.co";
    private static final String HF_MIRROR = "https://hf-mirror.com";
    private static final String REVISION = "main";
    private static final Gson GSON = new Gson();

    private final IGameEnvironment env;
    private volatile boolean downloadPaused = false;
    private volatile boolean downloadCancelled = false;

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    public AsrModelDownloader(IGameEnvironment env) {
        this.env = env;
    }

    public boolean isDownloadPaused() {
        return downloadPaused;
    }

    public void pauseDownload() {
        downloadPaused = true;
    }

    public void resumeDownload() {
        downloadPaused = false;
    }

    public void cancelDownload() {
        downloadCancelled = true;
        downloadPaused = false;
    }

    public void download(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) {
        try {
            downloadSync(info, targetDir, githubProxyUrl, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage() != null ? e.getMessage() : "下载失败");
        }
    }

    public void downloadSync(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        downloadCancelled = false;
        downloadPaused = false;
        doDownload(info, targetDir, githubProxyUrl, callback);
    }

    private void doDownload(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(targetDir, "targetDir");
        Path stagingDir = targetDir.resolveSibling(targetDir.getFileName() + "-staging");
        deleteRecursivelyIfExists(stagingDir);
        Files.createDirectories(stagingDir);
        try {
            if (info.isHfDownload()) {
                downloadFromHuggingFace(info, stagingDir, callback);
            } else {
                downloadFromGitHub(info, stagingDir, targetDir, githubProxyUrl, callback);
            }
            validateStandardModel(info, stagingDir);
            deleteRecursivelyIfExists(targetDir);
            Files.move(stagingDir, targetDir, StandardCopyOption.REPLACE_EXISTING);
            callback.onProgress("完成", 100);
            callback.onComplete();
        } catch (Exception e) {
            deleteRecursivelyIfExists(stagingDir);
            throw e;
        }
    }

    private void downloadFromHuggingFace(AsrModelInfo info, Path stagingDir, DownloadProgressCallback callback) throws Exception {
        callback.onProgress("探测网络环境", 2);
        String baseUrl = resolveHfBaseUrl();
        env.info("ASR HF 下载: repo=" + info.id + " source=" + baseUrl);

        callback.onProgress("解析文件列表", 5);
        List<String> allFiles = fetchFileTree(baseUrl, info.id, REVISION);
        StandardModelSources sources = resolveSources(info, allFiles);
        List<SourceTarget> downloads = sources.toSourceTargets();
        env.info("ASR 选择文件: " + downloads.stream().map(SourceTarget::display).collect(Collectors.joining(", ")));

        int total = downloads.size();
        for (int i = 0; i < total; i++) {
            waitIfDownloadPaused();
            SourceTarget item = downloads.get(i);
            Path localPath = stagingDir.resolve(item.targetName()).normalize();
            ensureWithinTarget(localPath, stagingDir);
            String resolveUrl = buildResolveUrl(baseUrl, info.id, REVISION, item.sourcePath());
            downloadSingleFile(resolveUrl, localPath, 3);
            int percent = 5 + (int) (((i + 1) / (double) total) * 90);
            callback.onProgress("下载中", percent);
        }
    }

    private void downloadFromGitHub(AsrModelInfo info, Path stagingDir, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        callback.onProgress("探测网络环境", 2);
        boolean useProxy = shouldUseGithubProxy(githubProxyUrl);
        String finalUrl = buildGithubDownloadUrl(info.downloadUrl, githubProxyUrl, useProxy);
        env.info("ASR GitHub 下载: url=" + finalUrl + " (useProxy=" + useProxy + ")");

        Path archivePath = targetDir.resolveSibling(targetDir.getFileName() + ".tar.bz2");
        Path extractDir = targetDir.resolveSibling(targetDir.getFileName() + "-extract");
        Files.deleteIfExists(archivePath);
        deleteRecursivelyIfExists(extractDir);
        try {
            callback.onProgress("下载压缩包", 5);
            downloadSingleFileWithProgress(finalUrl, archivePath, 5, 60_000, (downloaded, total) -> {
                int percent = total > 0 ? Math.min(80, (int) (downloaded * 75 / total) + 5) : 40;
                callback.onProgress("下载压缩包", percent);
            });

            callback.onProgress("解压模型", 82);
            Files.createDirectories(extractDir);
            extractTarBz2(archivePath, extractDir);

            callback.onProgress("整理模型", 92);
            List<String> allFiles = listRelativeFiles(extractDir);
            StandardModelSources sources = resolveArchiveSources(info, allFiles);
            for (SourceTarget item : sources.toSourceTargets()) {
                Path source = extractDir.resolve(item.sourcePath()).normalize();
                ensureWithinTarget(source, extractDir);
                Path target = stagingDir.resolve(item.targetName()).normalize();
                ensureWithinTarget(target, stagingDir);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            env.info("ASR 压缩包整理完成: " + sources.toSourceTargets().stream().map(SourceTarget::display).collect(Collectors.joining(", ")));
        } finally {
            deleteRecursivelyIfExists(extractDir);
            Files.deleteIfExists(archivePath);
        }
    }

    private StandardModelSources resolveSources(AsrModelInfo info, List<String> paths) throws IOException {
        if (!info.isTransducer()) {
            throw new IOException("当前 ASR 下载器仅支持标准化 Transducer 模型: " + info.getModelType());
        }
        List<SourceFile> files = paths.stream()
                .filter(Objects::nonNull)
                .filter(path -> !path.isBlank())
                .map(SourceFile::new)
                .filter(SourceFile::isUseful)
                .toList();

        String encoder = resolveRole(info, files, "encoder", AsrModelInfo.STANDARD_ENCODER, true);
        String decoder = resolveRole(info, files, "decoder", AsrModelInfo.STANDARD_DECODER, true);
        String joiner = resolveRole(info, files, "joiner", AsrModelInfo.STANDARD_JOINER, true);
        String tokens = resolveRole(info, files, "tokens", AsrModelInfo.STANDARD_TOKENS, true);
        String bpeModel = resolveRole(info, files, "bpeModel", AsrModelInfo.STANDARD_BPE_MODEL, requiresFile(info, AsrModelInfo.STANDARD_BPE_MODEL));
        String bpeVocab = resolveRole(info, files, "bpeVocab", AsrModelInfo.STANDARD_BPE_VOCAB, requiresFile(info, AsrModelInfo.STANDARD_BPE_VOCAB));
        return new StandardModelSources(encoder, decoder, joiner, tokens, bpeModel, bpeVocab);
    }

    private StandardModelSources resolveArchiveSources(AsrModelInfo info, List<String> paths) throws IOException {
        if (!info.isTransducer()) {
            throw new IOException("当前 ASR 下载器仅支持标准化 Transducer 模型: " + info.getModelType());
        }
        List<SourceFile> files = paths.stream()
                .filter(Objects::nonNull)
                .filter(path -> !path.isBlank())
                .map(SourceFile::new)
                .filter(SourceFile::isUseful)
                .toList();

        StandardModelSources explicitSources = resolveExplicitSources(info, files);
        if (explicitSources != null) return explicitSources;

        String tokens = resolveRole(info, files, "tokens", AsrModelInfo.STANDARD_TOKENS, true);
        String bpeModel = resolveRole(info, files, "bpeModel", AsrModelInfo.STANDARD_BPE_MODEL, requiresFile(info, AsrModelInfo.STANDARD_BPE_MODEL));
        String bpeVocab = resolveRole(info, files, "bpeVocab", AsrModelInfo.STANDARD_BPE_VOCAB, requiresFile(info, AsrModelInfo.STANDARD_BPE_VOCAB));
        ModelFileGroup group = resolveBestModelGroup(files);
        return new StandardModelSources(group.encoder().path(), group.decoder().path(), group.joiner().path(), tokens, bpeModel, bpeVocab);
    }

    private StandardModelSources resolveExplicitSources(AsrModelInfo info, List<SourceFile> files) throws IOException {
        if (info.getSourceFile("encoder") == null
                && info.getSourceFile(AsrModelInfo.STANDARD_ENCODER) == null
                && info.getSourceFile("decoder") == null
                && info.getSourceFile(AsrModelInfo.STANDARD_DECODER) == null
                && info.getSourceFile("joiner") == null
                && info.getSourceFile(AsrModelInfo.STANDARD_JOINER) == null) {
            return null;
        }
        String encoder = resolveRole(info, files, "encoder", AsrModelInfo.STANDARD_ENCODER, true);
        String decoder = resolveRole(info, files, "decoder", AsrModelInfo.STANDARD_DECODER, true);
        String joiner = resolveRole(info, files, "joiner", AsrModelInfo.STANDARD_JOINER, true);
        String tokens = resolveRole(info, files, "tokens", AsrModelInfo.STANDARD_TOKENS, true);
        String bpeModel = resolveRole(info, files, "bpeModel", AsrModelInfo.STANDARD_BPE_MODEL, requiresFile(info, AsrModelInfo.STANDARD_BPE_MODEL));
        String bpeVocab = resolveRole(info, files, "bpeVocab", AsrModelInfo.STANDARD_BPE_VOCAB, requiresFile(info, AsrModelInfo.STANDARD_BPE_VOCAB));
        return new StandardModelSources(encoder, decoder, joiner, tokens, bpeModel, bpeVocab);
    }

    private ModelFileGroup resolveBestModelGroup(List<SourceFile> files) throws IOException {
        Map<String, List<SourceFile>> grouped = files.stream()
                .filter(file -> matchesRole(file, "encoder", AsrModelInfo.STANDARD_ENCODER)
                        || matchesRole(file, "decoder", AsrModelInfo.STANDARD_DECODER)
                        || matchesRole(file, "joiner", AsrModelInfo.STANDARD_JOINER))
                .collect(Collectors.groupingBy(SourceFile::parent));

        List<ModelFileGroup> candidates = grouped.entrySet().stream()
                .map(entry -> buildModelGroup(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .sorted(this::compareModelGroups)
                .toList();

        if (candidates.isEmpty()) {
            throw new IOException("ASR 模型缺少完整同组 encoder/decoder/joiner");
        }

        ModelFileGroup best = candidates.get(0);
        List<ModelFileGroup> sameScore = candidates.stream()
                .filter(group -> group.score() == best.score())
                .toList();
        if (sameScore.size() > 1) {
            throw new IOException("ASR 压缩包存在多个同分模型组，请在 JSON sourceFiles 中明确指定: "
                    + sameScore.stream().map(ModelFileGroup::groupPath).collect(Collectors.joining(", ")));
        }
        return best;
    }

    private ModelFileGroup buildModelGroup(String groupPath, List<SourceFile> files) {
        SourceFile encoder = bestInGroup(files, "encoder", AsrModelInfo.STANDARD_ENCODER);
        SourceFile decoder = bestInGroup(files, "decoder", AsrModelInfo.STANDARD_DECODER);
        SourceFile joiner = bestInGroup(files, "joiner", AsrModelInfo.STANDARD_JOINER);
        if (encoder == null || decoder == null || joiner == null) return null;
        int score = scoreModelGroup(groupPath, encoder, decoder, joiner);
        return new ModelFileGroup(groupPath, encoder, decoder, joiner, score);
    }

    private int compareModelGroups(ModelFileGroup left, ModelFileGroup right) {
        int scoreCompare = Integer.compare(right.score(), left.score());
        if (scoreCompare != 0) return scoreCompare;
        int lengthCompare = Integer.compare(left.groupPath().length(), right.groupPath().length());
        if (lengthCompare != 0) return lengthCompare;
        return left.groupPath().compareTo(right.groupPath());
    }

    private SourceFile bestInGroup(List<SourceFile> files, String role, String standardName) {
        return files.stream()
                .filter(file -> matchesRole(file, role, standardName))
                .sorted(comparatorFor(role))
                .findFirst()
                .orElse(null);
    }

    private int scoreModelGroup(String groupPath, SourceFile encoder, SourceFile decoder, SourceFile joiner) {
        String normalizedGroup = groupPath.toLowerCase(Locale.ROOT).replace('\\', '/');
        int score = scoreFor(encoder, "encoder") + scoreFor(decoder, "decoder") + scoreFor(joiner, "joiner");
        if (normalizedGroup.contains("/exp") || normalizedGroup.equals("exp")) score += 300;
        if (normalizedGroup.contains("/onnx") || normalizedGroup.equals("onnx")) score += 180;
        if (normalizedGroup.endsWith("/int8") || normalizedGroup.equals("int8")) score += 120;
        if (normalizedGroup.isBlank()) score += 80;
        score -= Math.max(0, encoder.pathSegments() - 2) * 5;
        return score;
    }

    private String resolveRole(AsrModelInfo info, List<SourceFile> files, String role, String standardName, boolean required) throws IOException {
        String roleSource = info.getSourceFile(role);
        String explicit = roleSource != null ? roleSource : info.getSourceFile(standardName);
        if (explicit != null) {
            SourceFile matched = files.stream()
                    .filter(file -> samePath(file.path(), explicit) || file.fileName().equalsIgnoreCase(explicit))
                    .findFirst()
                    .orElse(null);
            if (matched == null) throw new IOException("ASR 模型源文件不存在: role=" + role + " path=" + explicit);
            return matched.path();
        }

        List<SourceFile> candidates = files.stream()
                .filter(file -> matchesRole(file, role, standardName))
                .sorted(comparatorFor(role))
                .toList();
        if (candidates.isEmpty()) {
            if (required) throw new IOException("ASR 模型缺少源文件: " + role);
            return null;
        }
        SourceFile best = candidates.get(0);
        List<SourceFile> sameScore = candidates.stream()
                .filter(file -> scoreFor(file, role) == scoreFor(best, role))
                .toList();
        if (sameScore.size() > 1 && isAmbiguous(role, sameScore)) {
            throw new IOException("ASR 模型源文件候选不唯一: " + role + " -> " + sameScore.stream().map(SourceFile::path).collect(Collectors.joining(", ")));
        }
        return best.path();
    }

    private boolean matchesRole(SourceFile file, String role, String standardName) {
        String name = file.fileNameLower();
        String path = file.pathLower();
        return switch (role) {
            case "encoder" -> name.endsWith(".onnx") && name.contains("encoder");
            case "decoder" -> name.endsWith(".onnx") && name.contains("decoder");
            case "joiner" -> name.endsWith(".onnx") && name.contains("joiner");
            case "tokens" -> name.equals(AsrModelInfo.STANDARD_TOKENS);
            case "bpeModel" -> name.equals(AsrModelInfo.STANDARD_BPE_MODEL) || path.endsWith("/" + AsrModelInfo.STANDARD_BPE_MODEL);
            case "bpeVocab" -> name.equals(AsrModelInfo.STANDARD_BPE_VOCAB) || path.endsWith("/" + AsrModelInfo.STANDARD_BPE_VOCAB);
            default -> name.equalsIgnoreCase(standardName);
        };
    }

    private Comparator<SourceFile> comparatorFor(String role) {
        return Comparator.comparingInt((SourceFile file) -> scoreFor(file, role)).reversed()
                .thenComparingInt(file -> file.path().length())
                .thenComparing(SourceFile::path);
    }

    private int scoreFor(SourceFile file, String role) {
        String name = file.fileNameLower();
        String path = file.pathLower();
        int score = 0;
        if (role.equals("decoder")) {
            if (name.contains(".int8.")) score -= 1000;
            else if (name.contains("int8")) score -= 800;
            else score += 1000;
            if (path.contains("/int8/") || path.contains("\\int8\\")) score -= 300;
        } else {
            if (name.contains(".int8.")) score += 1000;
            else if (name.contains("int8")) score += 800;
            else if (name.contains(".fp16.")) score += 500;
            if (path.contains("/int8/") || path.contains("\\int8\\")) score += 300;
        }
        if (path.contains("/exp/") || path.contains("\\exp\\")) score += 80;
        if (path.contains("/onnx/") || path.contains("\\onnx\\")) score += 60;
        if (path.contains("/data/lang_char/") || path.contains("/data/lang_bpe/")) score += role.equals("tokens") || role.startsWith("bpe") ? 200 : 0;
        if (path.contains("/data/")) score += role.equals("tokens") || role.startsWith("bpe") ? 100 : 0;
        if (file.pathSegments() <= 2) score += 20;
        if (name.equals(role + ".onnx")) score += 50;
        if (role.equals("tokens") && name.equals(AsrModelInfo.STANDARD_TOKENS)) score += 100;
        return score;
    }

    private boolean isAmbiguous(String role, List<SourceFile> files) {
        if (files.size() <= 1) return false;
        Set<String> parents = files.stream().map(SourceFile::parent).collect(Collectors.toCollection(HashSet::new));
        if (Set.of("encoder", "joiner").contains(role) && parents.size() == 1) return false;
        if (role.equals("decoder") && files.stream().noneMatch(this::isInt8File) && parents.size() == 1) return false;
        if ((role.equals("tokens") || role.startsWith("bpe")) && files.stream().map(SourceFile::fileNameLower).distinct().count() == 1) return false;
        return true;
    }

    private boolean isInt8File(SourceFile file) {
        String name = file.fileNameLower();
        String path = file.pathLower();
        return name.contains("int8") || path.contains("/int8/") || path.contains("\\int8\\");
    }

    private boolean requiresFile(AsrModelInfo info, String fileName) {
        return info.getAllRequiredFiles().stream().anyMatch(file -> file.equalsIgnoreCase(fileName));
    }

    private void validateStandardModel(AsrModelInfo info, Path modelDir) throws IOException {
        List<String> missing = new ArrayList<>();
        for (String file : info.getAllRequiredFiles()) {
            if (!Files.isRegularFile(modelDir.resolve(file))) {
                missing.add(file);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("ASR 模型标准化失败，缺少文件: " + String.join(", ", missing));
        }
    }

    private String resolveHfBaseUrl() {
        try {
            String activeUrl = HuggingFaceDownloader.getActiveBaseUrl();
            if (HF_OFFICIAL.equals(activeUrl)) {
                env.info("HuggingFace 官方可达，使用官方源");
                return HF_OFFICIAL;
            }
        } catch (Exception ignored) {
        }
        env.info("HuggingFace 官方不可达，使用镜像源");
        return HF_MIRROR;
    }

    private boolean shouldUseGithubProxy(String githubProxyUrl) {
        if (githubProxyUrl == null || githubProxyUrl.isBlank()) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("github.com", 443), 300);
            env.info("GitHub 官方可达，不使用代理");
            return false;
        } catch (IOException e) {
            env.info("GitHub 官方不可达，使用代理");
            return true;
        }
    }

    private String buildGithubDownloadUrl(String downloadUrl, String proxyUrl, boolean useProxy) {
        if (!useProxy || proxyUrl == null || proxyUrl.isBlank()) return downloadUrl;
        String normalizedProxy = proxyUrl.endsWith("/") ? proxyUrl : proxyUrl + "/";
        if (downloadUrl.startsWith(normalizedProxy)) return downloadUrl;
        return normalizedProxy + downloadUrl;
    }

    private List<String> fetchFileTree(String baseUrl, String repoId, String revision) throws Exception {
        String url = String.format(
                "%s/api/models/%s/tree/%s?recursive=true&expand=false",
                baseUrl,
                encodeRepoId(repoId),
                URLEncoder.encode(revision, StandardCharsets.UTF_8)
        );

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        conn.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Tianshu-ASR-Downloader/1.0");

        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HF tree API 失败. repo=" + repoId + " code=" + code);

        try (InputStream is = conn.getInputStream(); Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonArray root = GSON.fromJson(reader, JsonArray.class);
            List<String> result = new ArrayList<>();
            for (JsonElement element : root) {
                JsonObject node = element.getAsJsonObject();
                if (!"file".equalsIgnoreCase(node.get("type").getAsString())) continue;
                result.add(node.get("path").getAsString());
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }

    private List<String> listRelativeFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toList());
        }
    }

    private String buildResolveUrl(String baseUrl, String repoId, String revision, String filePath) {
        String encodedPath = Arrays.stream(filePath.split("/"))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
        return String.format(
                "%s/%s/resolve/%s/%s",
                baseUrl,
                encodeRepoId(repoId),
                URLEncoder.encode(revision, StandardCharsets.UTF_8),
                encodedPath
        );
    }

    private String encodeRepoId(String repoId) {
        String[] parts = repoId.split("/", 2);
        if (parts.length == 2) {
            return URLEncoder.encode(parts[0], StandardCharsets.UTF_8) + "/" + URLEncoder.encode(parts[1], StandardCharsets.UTF_8);
        }
        return URLEncoder.encode(repoId, StandardCharsets.UTF_8);
    }

    private void downloadSingleFile(String url, Path target, int maxRetries) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        IOException lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
                conn.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Tianshu-ASR-Downloader/1.0");

                if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());

                try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        if (downloadCancelled) throw new IOException("下载已取消");
                        waitIfDownloadPaused();
                        out.write(buf, 0, len);
                    }
                } finally {
                    conn.disconnect();
                }

                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                env.info("下载完成: " + target.getFileName());
                return;
            } catch (IOException e) {
                lastException = e;
                env.warn("下载重试 " + attempt + "/" + maxRetries + ": " + target.getFileName() + " - " + e.getMessage());
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw new IOException("下载线程被中断", ignored);
                }
            }
        }
        throw new IOException("下载失败，重试 " + maxRetries + " 次后仍不成功: " + url, lastException);
    }

    private void downloadSingleFileWithProgress(String urlString, Path targetPath, int maxRetries, int timeoutMillis, ProgressListener listener) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            waitIfDownloadPaused();
            HttpURLConnection conn = null;
            Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".downloading");
            try {
                Files.createDirectories(targetPath.getParent());
                conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setConnectTimeout(timeoutMillis);
                conn.setReadTimeout(timeoutMillis);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Tianshu-ASR-Downloader/1.0");
                int code = conn.getResponseCode();
                if (code != 200) throw new IOException("HTTP 错误: " + code);
                long total = conn.getContentLengthLong();
                long downloaded = 0L;
                try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tempPath)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (downloadCancelled) throw new IOException("下载已取消");
                        waitIfDownloadPaused();
                        out.write(buffer, 0, read);
                        downloaded += read;
                        listener.onProgress(downloaded, total);
                    }
                }
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(tempPath);
                if (downloadCancelled) throw e;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    private void extractTarBz2(Path archive, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             InputStream bis = new BufferedInputStream(fis);
             BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(bis);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(bzIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path outputPath = targetDir.resolve(entry.getName()).normalize();
                ensureWithinTarget(outputPath, targetDir);
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }
                Files.createDirectories(outputPath.getParent());
                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    tarIn.transferTo(out);
                }
            }
        }
    }

    private boolean samePath(String left, String right) {
        return left.replace('\\', '/').equalsIgnoreCase(right.replace('\\', '/'));
    }

    private void ensureWithinTarget(Path path, Path targetDir) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(targetDir.toAbsolutePath().normalize())) {
            throw new IOException("路径穿越检测: " + path);
        }
    }

    private void waitIfDownloadPaused() throws IOException {
        while (downloadPaused) {
            if (downloadCancelled) throw new IOException("下载已取消");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载线程被中断", e);
            }
        }
        if (downloadCancelled) throw new IOException("下载已取消");
    }

    private void deleteRecursivelyIfExists(Path path) throws IOException {
        if (Files.exists(path)) deleteRecursively(path);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) throw ioException;
            throw e;
        }
    }

    private record SourceTarget(String sourcePath, String targetName) {
        private String display() {
            return sourcePath + " -> " + targetName;
        }
    }

    private record StandardModelSources(String encoder, String decoder, String joiner, String tokens, String bpeModel, String bpeVocab) {
        private List<SourceTarget> toSourceTargets() {
            Map<String, String> targets = new HashMap<>();
            targets.put(encoder, AsrModelInfo.STANDARD_ENCODER);
            targets.put(decoder, AsrModelInfo.STANDARD_DECODER);
            targets.put(joiner, AsrModelInfo.STANDARD_JOINER);
            targets.put(tokens, AsrModelInfo.STANDARD_TOKENS);
            if (bpeModel != null) targets.put(bpeModel, AsrModelInfo.STANDARD_BPE_MODEL);
            if (bpeVocab != null) targets.put(bpeVocab, AsrModelInfo.STANDARD_BPE_VOCAB);
            return targets.entrySet().stream().map(entry -> new SourceTarget(entry.getKey(), entry.getValue())).toList();
        }
    }

    private record ModelFileGroup(String groupPath, SourceFile encoder, SourceFile decoder, SourceFile joiner, int score) {
    }

    private record SourceFile(String path) {
        private String fileName() {
            int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return index >= 0 ? path.substring(index + 1) : path;
        }

        private String fileNameLower() {
            return fileName().toLowerCase(Locale.ROOT);
        }

        private String pathLower() {
            return path.toLowerCase(Locale.ROOT).replace('\\', '/');
        }

        private String parent() {
            String normalized = path.replace('\\', '/');
            int index = normalized.lastIndexOf('/');
            return index >= 0 ? normalized.substring(0, index) : "";
        }

        private int pathSegments() {
            String normalized = path.replace('\\', '/');
            if (normalized.isBlank()) return 0;
            return normalized.split("/").length;
        }

        private boolean isUseful() {
            String lower = fileNameLower();
            if (lower.equals(".gitattributes")) return false;
            if (lower.startsWith("readme")) return false;
            if (lower.endsWith(".py")) return false;
            if (pathLower().contains("/dict/")) return false;
            return lower.endsWith(".onnx")
                    || lower.equals(AsrModelInfo.STANDARD_TOKENS)
                    || lower.equals(AsrModelInfo.STANDARD_BPE_MODEL)
                    || lower.equals(AsrModelInfo.STANDARD_BPE_VOCAB);
        }
    }

    private interface ProgressListener {
        void onProgress(long downloaded, long total);
    }
}
