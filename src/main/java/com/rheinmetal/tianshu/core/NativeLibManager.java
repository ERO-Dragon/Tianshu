package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.Tianshu;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class NativeLibManager {

    private static final String DLL_RESOURCE_PREFIX = "META-INF/natives/custom_dll/";
    private static final String SERVER_JAR_RESOURCE_PATH = "JavaLlamaServer/JavaLlamaServer-v1.0.0.jar";
    private static final String SERVER_JAR_NAME = "JavaLlamaServer-v1.0.0.jar";

    private static volatile boolean nativesExtracted = false;
    private static volatile boolean nativesLoaded = false;
    private static volatile Path nativesDir;
    private static volatile Path llmServerDir;
    private static volatile Path serverJarPath;

    private NativeLibManager() {}

    public static Path getRootDir() {
        Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("config").resolve("TianshuAIAssistant");
    }

    public static Path getNativesDir() {
        if (nativesDir == null) {
            nativesDir = getRootDir().resolve("natives");
        }
        return nativesDir;
    }

    public static Path getLlmServerDir() {
        if (llmServerDir == null) {
            llmServerDir = getRootDir().resolve("LLMServer");
        }
        return llmServerDir;
    }

    public static Path getCacheDir() {
        return getRootDir().resolve("cache");
    }

    public static Path getModelsDir() {
        return getRootDir().resolve("models");
    }

    public static boolean isNativesExtracted() {
        return nativesExtracted;
    }

    public static boolean isNativesLoaded() {
        return nativesLoaded;
    }

    public static Path getServerJarPath() {
        return serverJarPath;
    }

    public static void ensureDirectories() {
        try {
            Files.createDirectories(getNativesDir());
            Files.createDirectories(getLlmServerDir());
            Files.createDirectories(getCacheDir());
            Files.createDirectories(getModelsDir().resolve("asr"));
            Files.createDirectories(getModelsDir().resolve("llm"));
            Files.createDirectories(getModelsDir().resolve("tts"));
            Tianshu.LOGGER.info("目录结构已就绪: {}", getRootDir());
        } catch (IOException e) {
            Tianshu.LOGGER.error("创建目录结构失败", e);
        }
    }

    public static synchronized void extractNatives() {
        if (nativesExtracted) return;

        try {
            Path targetDir = getNativesDir();
            Files.createDirectories(targetDir);

            List<String> dllNames = listResourceDlls();
            if (dllNames.isEmpty()) {
                Tianshu.LOGGER.warn("未在 JAR 内找到任何 DLL 资源 ({})", DLL_RESOURCE_PREFIX);
                return;
            }

            int extracted = 0;
            int skipped = 0;
            for (String dllName : dllNames) {
                String resourcePath = DLL_RESOURCE_PREFIX + dllName;
                Path targetFile = targetDir.resolve(dllName);

                if (shouldSkipExtract(resourcePath, targetFile)) {
                    skipped++;
                    continue;
                }

                try (InputStream is = NativeLibManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        Tianshu.LOGGER.warn("无法读取资源: {}", resourcePath);
                        continue;
                    }
                    Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    extracted++;
                    Tianshu.LOGGER.debug("提取 DLL: {} -> {}", dllName, targetFile);
                }
            }

            nativesExtracted = true;
            Tianshu.LOGGER.info("DLL 提取完成 (新提取: {}, 跳过: {}), 目录: {}", extracted, skipped, targetDir);
        } catch (Exception e) {
            Tianshu.LOGGER.error("提取 Native DLL 失败", e);
        }
    }

    public static synchronized void extractServerJar() {
        if (serverJarPath != null && Files.exists(serverJarPath)) return;

        try {
            Path targetDir = getLlmServerDir();
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(SERVER_JAR_NAME);

            if (shouldSkipExtract(SERVER_JAR_RESOURCE_PATH, targetFile)) {
                serverJarPath = targetFile;
                Tianshu.LOGGER.info("JavaLlamaServer JAR 已存在且完整: {}", targetFile);
                return;
            }

            try (InputStream is = NativeLibManager.class.getClassLoader().getResourceAsStream(SERVER_JAR_RESOURCE_PATH)) {
                if (is == null) {
                    Tianshu.LOGGER.error("无法在模组包内找到: {}", SERVER_JAR_RESOURCE_PATH);
                    return;
                }
                Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                serverJarPath = targetFile;
                Tianshu.LOGGER.info("JavaLlamaServer JAR 已提取: {}", targetFile);
            }
        } catch (Exception e) {
            Tianshu.LOGGER.error("提取 JavaLlamaServer JAR 失败", e);
        }
    }

    public static synchronized void loadNatives() {
        if (nativesLoaded) return;

        Path dir = getNativesDir();
        if (!Files.isDirectory(dir)) {
            Tianshu.LOGGER.error("Native 目录不存在: {}", dir);
            return;
        }

        loadDll(dir, "onnxruntime.dll");
        //loadDll(dir, "sherpa-onnx-jni.dll");

        System.setProperty("sherpa_onnx.native.path", dir.toAbsolutePath().toString());
        Tianshu.LOGGER.info("已设置 sherpa_onnx.native.path = {}", dir.toAbsolutePath());

        nativesLoaded = true;
        Tianshu.LOGGER.info("核心 Native 库 (onnxruntime) 加载完成");
        // Tianshu.LOGGER.info("核心 Native 库 (onnxruntime + sherpa-onnx-jni) 加载完成");
    }

    private static void loadDll(Path dir, String dllName) {
        Path dllPath = dir.resolve(dllName);
        if (!Files.exists(dllPath)) {
            Tianshu.LOGGER.error("DLL 文件不存在: {}", dllPath);
            return;
        }
        try {
            System.load(dllPath.toAbsolutePath().toString());
            Tianshu.LOGGER.info("已加载 DLL: {}", dllPath.getFileName());
        } catch (UnsatisfiedLinkError e) {
            if (e.getMessage() != null && e.getMessage().contains("already loaded in another classloader")) {
                Tianshu.LOGGER.info("DLL 已在其他 ClassLoader 中加载，视为成功: {}", dllName);
            } else {
                Tianshu.LOGGER.error("加载 DLL 失败: {}", dllName, e);
                throw e;
            }
        }
    }

    private static boolean shouldSkipExtract(String resourcePath, Path targetFile) {
        if (!Files.exists(targetFile)) return false;
        try {
            long diskSize = Files.size(targetFile);
            long jarSize = getResourceSize(resourcePath);
            if (jarSize > 0 && diskSize == jarSize) {
                return true;
            }
        } catch (IOException e) {
            Tianshu.LOGGER.debug("比较文件大小失败，将重新提取: {}", targetFile);
        }
        return false;
    }

    private static long getResourceSize(String resourcePath) {
        try {
            URL url = NativeLibManager.class.getClassLoader().getResource(resourcePath);
            if (url == null) return -1;

            if ("file".equals(url.getProtocol())) {
                Path path = Path.of(url.toURI());
                return Files.size(path);
            }

            if ("jar".equals(url.getProtocol())) {
                try (InputStream is = NativeLibManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) return -1;
                    long size = 0;
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        size += read;
                    }
                    return size;
                }
            }
        } catch (Exception e) {
            Tianshu.LOGGER.debug("获取资源大小失败: {}", resourcePath);
        }
        return -1;
    }

    private static List<String> listResourceDlls() {
        List<String> dllNames = new ArrayList<>();
        try {
            URL resUrl = NativeLibManager.class.getClassLoader().getResource(DLL_RESOURCE_PREFIX);
            if (resUrl != null && "file".equals(resUrl.getProtocol())) {
                Path dir = Path.of(resUrl.toURI());
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> stream = Files.list(dir)) {
                        stream.filter(p -> p.toString().endsWith(".dll"))
                              .map(p -> p.getFileName().toString())
                              .forEach(dllNames::add);
                    }
                }
            }

            if (dllNames.isEmpty()) {
                String[] knownDlls = {
                    "onnxruntime.dll", "sherpa-onnx-jni.dll",
                    "llama.dll", "ggml.dll", "ggml-base.dll", "ggml-vulkan.dll",
                    "ggml-cpu-alderlake.dll", "ggml-cpu-cannonlake.dll", "ggml-cpu-cascadelake.dll",
                    "ggml-cpu-haswell.dll", "ggml-cpu-icelake.dll", "ggml-cpu-sandybridge.dll",
                    "ggml-cpu-skylakex.dll", "ggml-cpu-sse42.dll", "ggml-cpu-x64.dll",
                    "Java_org_argeo_jjml_ggml.dll", "Java_org_argeo_jjml_llm.dll",
                    "Java_org_argeo_jjml_mtmd.dll", "Java_org_argeo_jjml_whisper.dll",
                    "whisper.dll"
                };
                for (String dll : knownDlls) {
                    if (NativeLibManager.class.getClassLoader().getResource(DLL_RESOURCE_PREFIX + dll) != null) {
                        dllNames.add(dll);
                    }
                }
            }
        } catch (Exception e) {
            Tianshu.LOGGER.error("枚举 DLL 资源失败，使用硬编码列表兜底", e);
            String[] fallback = {
                "onnxruntime.dll", "sherpa-onnx-jni.dll",
                "llama.dll", "ggml.dll", "ggml-base.dll", "ggml-vulkan.dll",
                "ggml-cpu-alderlake.dll", "ggml-cpu-cannonlake.dll", "ggml-cpu-cascadelake.dll",
                "ggml-cpu-haswell.dll", "ggml-cpu-icelake.dll", "ggml-cpu-sandybridge.dll",
                "ggml-cpu-skylakex.dll", "ggml-cpu-sse42.dll", "ggml-cpu-x64.dll",
                "Java_org_argeo_jjml_ggml.dll", "Java_org_argeo_jjml_llm.dll",
                "Java_org_argeo_jjml_mtmd.dll", "Java_org_argeo_jjml_whisper.dll",
                "whisper.dll"
            };
            dllNames.clear();
            for (String dll : fallback) {
                if (NativeLibManager.class.getClassLoader().getResource(DLL_RESOURCE_PREFIX + dll) != null) {
                    dllNames.add(dll);
                }
            }
        }
        Collections.sort(dllNames);
        return dllNames;
    }

    public static boolean checkNativesReady() {
        Path dir = getNativesDir();
        if (!Files.isDirectory(dir)) return false;
        return Files.exists(dir.resolve("onnxruntime.dll"))
            && Files.exists(dir.resolve("sherpa-onnx-jni.dll"))
            && Files.exists(dir.resolve("llama.dll"));
    }

    public static void extractAndLoadAll() {
        ensureDirectories();
        extractNatives();
        loadNatives();
        extractServerJar();
    }
}
