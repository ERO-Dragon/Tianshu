package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.config.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class EnvSetupManager {

    public interface SetupCallback {
        void onProgress(String stage, int percent);
        void onSuccess();
        void onError(String message);
    }

    private static final String SHERPA_JAR_CACHE_NAME = "sherpa-onnx-native-cache.jar";
    private static volatile boolean setupCompleted = false;

    private EnvSetupManager() {}

    public static boolean isSetupCompleted() {
        return setupCompleted;
    }

    public static void markSetupCompleted() {
        setupCompleted = true;
    }

    public static boolean isEnvironmentReady() {
        return checkNativesDllExists() && checkLlmEngineExists();
    }

    private static boolean checkNativesDllExists() {
        String nativesPath = System.getProperty("java.library.path");
        if (nativesPath == null || nativesPath.isEmpty()) return false;
        File nativesDir = new File(nativesPath.split(File.pathSeparator)[0]);
        File onnx = new File(nativesDir, "onnxruntime.dll");
        File jni = new File(nativesDir, "sherpa-onnx-jni.dll");
        return onnx.exists() && onnx.length() > 10000 && jni.exists() && jni.length() > 10000;
    }

    private static boolean checkLlmEngineExists() {
        Path engineDir = Config.getLlmEnginePath();
        if (!Files.exists(engineDir) || !Files.isDirectory(engineDir)) return false;
        File[] exeFiles = engineDir.toFile().listFiles((dir, name) -> name.toLowerCase().endsWith(".exe"));
        return exeFiles != null && exeFiles.length > 0;
    }

    public static Path getCacheDir() {
        Path cacheDir = Config.getRootPath().resolve("cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception e) {
            Tianshu.LOGGER.error("创建缓存目录失败", e);
        }
        return cacheDir;
    }

    public static void startSetup(SetupCallback callback) {
        Thread thread = new Thread(() -> {
            try {
                doSetup(callback);
                setupCompleted = true;
                callback.onSuccess();
            } catch (Exception e) {
                Tianshu.LOGGER.error("环境配置失败", e);
                callback.onError("环境配置失败: " + e.getMessage());
            }
        }, "Tianshu-EnvSetup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void doSetup(SetupCallback callback) throws Exception {
        boolean nativesReady = ensureNativesDll(callback, 0, 50);
        if (!nativesReady) {
            throw new RuntimeException("Native DLL 配置失败");
        }

        boolean llmReady = ensureLlmEngine(callback, 50, 100);
        if (!llmReady) {
            throw new RuntimeException("LLM 引擎配置失败");
        }
    }

    private static boolean ensureNativesDll(SetupCallback callback, int progressStart, int progressEnd) throws Exception {
        if (checkNativesDllExists()) {
            Tianshu.LOGGER.info("Native DLL 已存在，跳过下载");
            callback.onProgress("Native DLL 已就绪", progressEnd);
            return true;
        }

        String nativesPath = System.getProperty("java.library.path");
        File nativesDir;
        if (nativesPath != null && !nativesPath.isEmpty()) {
            nativesDir = new File(nativesPath.split(File.pathSeparator)[0]);
            if (!nativesDir.exists()) nativesDir.mkdirs();
        } else {
            nativesDir = new File(System.getProperty("java.io.tmpdir"), "tianshu_sherpa_cache");
            nativesDir.mkdirs();
        }

        Path cachedJar = getCacheDir().resolve(SHERPA_JAR_CACHE_NAME);

        if (!Files.exists(cachedJar) || Files.size(cachedJar) < 10000) {
            Tianshu.LOGGER.info("下载 sherpa-onnx native JAR...");
            callback.onProgress("下载 Native 运行库...", progressStart);
            downloadFile(
                com.rheinmetal.tianshu.config.ModelUrls.SHERPA_NATIVE_JAR_URL,
                cachedJar,
                (downloaded, total) -> {
                    int percent = total > 0
                        ? (int) (progressStart + (progressEnd - progressStart) * 0.6 * downloaded / total)
                        : progressStart + 10;
                    callback.onProgress("下载 Native 运行库...", percent);
                }
            );
            Tianshu.LOGGER.info("sherpa-onnx native JAR 下载完成: {}", cachedJar);
        } else {
            Tianshu.LOGGER.info("发现缓存的 sherpa-onnx native JAR: {}", cachedJar);
        }

        callback.onProgress("解压 Native 运行库...", (int) (progressStart + (progressEnd - progressStart) * 0.7));
        extractDllsFromJar(cachedJar.toFile(), nativesDir);

        System.setProperty("sherpa_onnx.native.path", nativesDir.getAbsolutePath());
        Tianshu.LOGGER.info("已设置 sherpa_onnx.native.path = {}", nativesDir.getAbsolutePath());

        callback.onProgress("Native DLL 配置完成", progressEnd);
        return true;
    }

    private static boolean ensureLlmEngine(SetupCallback callback, int progressStart, int progressEnd) throws Exception {
        if (checkLlmEngineExists()) {
            Tianshu.LOGGER.info("LLM 引擎已存在，跳过下载");
            callback.onProgress("LLM 引擎已就绪", progressEnd);
            return true;
        }

        Path engineDir = Config.getLlmEnginePath();
        Files.createDirectories(engineDir);

        Path tempZip = getCacheDir().resolve("llm_vulkan_engine.zip");

        Tianshu.LOGGER.info("下载 LLM Vulkan 引擎...");
        callback.onProgress("下载 LLM 推理引擎...", progressStart);
        downloadFile(
            com.rheinmetal.tianshu.config.ModelUrls.LLM_VULKAN_ZIP_URL,
            tempZip,
            (downloaded, total) -> {
                int percent = total > 0
                    ? (int) (progressStart + (progressEnd - progressStart) * 0.7 * downloaded / total)
                    : progressStart + 10;
                callback.onProgress("下载 LLM 推理引擎...", percent);
            }
        );
        Tianshu.LOGGER.info("LLM Vulkan 引擎下载完成: {}", tempZip);

        callback.onProgress("解压 LLM 推理引擎...", (int) (progressStart + (progressEnd - progressStart) * 0.75));
        extractZip(tempZip, engineDir);

        try {
            Files.deleteIfExists(tempZip);
            Tianshu.LOGGER.info("已删除 LLM 引擎 ZIP 临时文件");
        } catch (Exception e) {
            Tianshu.LOGGER.warn("删除 LLM 引擎 ZIP 临时文件失败（可忽略）", e);
        }

        callback.onProgress("LLM 引擎配置完成", progressEnd);
        return true;
    }

    private static void downloadFile(String url, Path targetPath, DownloadProgress progressCallback) throws Exception {
        Files.createDirectories(targetPath.getParent());

        Path tempPath = targetPath.resolveSibling(targetPath.getFileName().toString() + ".downloading");

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP 错误: " + response.statusCode());
        }

        long totalBytes = response.headers().firstValueAsLong("content-length").orElse(-1L);
        long downloadedBytes = 0L;

        try (InputStream is = response.body();
             FileOutputStream os = new FileOutputStream(tempPath.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;
                progressCallback.onProgress(downloadedBytes, totalBytes);
            }
        }

        Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extractDllsFromJar(File jarFile, File targetDir) throws Exception {
        doExtractDllsFromJar(jarFile, targetDir);
    }

    public static void extractDllsFromJarStatic(File jarFile, File targetDir) throws Exception {
        doExtractDllsFromJar(jarFile, targetDir);
    }

    private static void doExtractDllsFromJar(File jarFile, File targetDir) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.toLowerCase().endsWith(".dll")) {
                    String dllName = new File(name).getName();
                    File targetFile = new File(targetDir, dllName);

                    try (InputStream is = jar.getInputStream(entry);
                         FileOutputStream os = new FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                    Tianshu.LOGGER.info("从 JAR 提取 DLL: {} -> {}", name, targetFile.getAbsolutePath());
                }
            }
        }
    }

    private static void extractZip(Path zipPath, Path targetDir) throws Exception {
        try (InputStream fis = Files.newInputStream(zipPath);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName());

                if (entry.getName().contains("..")) {
                    Tianshu.LOGGER.warn("跳过可疑路径: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
        Tianshu.LOGGER.info("ZIP 解压完成: {} -> {}", zipPath, targetDir);
    }

    @FunctionalInterface
    private interface DownloadProgress {
        void onProgress(long downloadedBytes, long totalBytes);
    }
}
