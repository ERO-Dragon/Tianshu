package com.rheinmetal.tianshu.platform;

import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class NeoForgeNativeLibBridge implements INativeLibBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DLL_RESOURCE_PREFIX = "custom_dll/";
    private static final String SERVER_JAR_RESOURCE_PATH = "JavaLlamaServer/JavaLlamaServer-v1.0.1.jar";
    private static final String SERVER_JAR_NAME = "JavaLlamaServer-v1.0.1.jar";

    private volatile boolean nativesExtracted = false;
    private volatile boolean nativesLoaded = false;
    private volatile Path nativesDir;
    private volatile Path llmServerDir;
    private volatile Path serverJarPath;

    @Override
    public boolean isNativesReady() {
        return nativesLoaded;
    }

    @Override
    public Path getRootDir() {
        Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("config").resolve("TianshuAIAssistant");
    }

    @Override
    public Path getNativesDir() {
        if (nativesDir == null) {
            nativesDir = getRootDir().resolve("natives");
        }
        return nativesDir;
    }

    public Path getLlmServerDir() {
        if (llmServerDir == null) {
            llmServerDir = getRootDir().resolve("module").resolve("llm").resolve("server");
        }
        return llmServerDir;
    }

    @Override
    public Path getServerJarPath() {
        return serverJarPath;
    }

    public void ensureDirectories() {
        try {
            Files.createDirectories(getNativesDir());
            Files.createDirectories(getRootDir().resolve("module").resolve("asr").resolve("model"));
            Files.createDirectories(getRootDir().resolve("module").resolve("asr").resolve("hotwords"));
            Files.createDirectories(getRootDir().resolve("module").resolve("asr").resolve("cache"));
            Files.createDirectories(getRootDir().resolve("module").resolve("llm").resolve("model"));
            Files.createDirectories(getLlmServerDir());
            Files.createDirectories(getRootDir().resolve("module").resolve("llm").resolve("cache"));
            Files.createDirectories(getRootDir().resolve("module").resolve("tts").resolve("model"));
            Files.createDirectories(getRootDir().resolve("module").resolve("tts").resolve("voices"));
            Files.createDirectories(getRootDir().resolve("module").resolve("tts").resolve("cache"));
            Files.createDirectories(getRootDir().resolve("module").resolve("ir").resolve("cache"));
            Files.createDirectories(getRootDir().resolve("module").resolve("recipe").resolve("cache"));
        } catch (IOException e) {
            LOGGER.error("创建目录结构失败", e);
        }
    }

    @Override
    public void extractAndLoadAll() {
        extractNatives();
        loadNatives();
    }

    public synchronized void extractNatives() {
        if (nativesExtracted) return;
        try {
            Path targetDir = getNativesDir();
            Files.createDirectories(targetDir);

            List<String> dllNames = listResourceDlls();
            if (dllNames.isEmpty()) {
                LOGGER.warn("未在 JAR 内找到任何 DLL 资源 ({})", DLL_RESOURCE_PREFIX);
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

                try (InputStream is = NeoForgeNativeLibBridge.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) continue;
                    Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    extracted++;
                }
            }

            nativesExtracted = true;
            LOGGER.info("DLL 提取完成 (新提取: {}, 跳过: {}), 目录: {}", extracted, skipped, targetDir);
        } catch (Exception e) {
            LOGGER.error("提取 Native DLL 失败", e);
        }
    }

    @Override
    public synchronized void extractServerJar() {
        try {
            Path targetDir = getLlmServerDir();
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(SERVER_JAR_NAME);

            if (shouldSkipExtract(SERVER_JAR_RESOURCE_PATH, targetFile)) {
                serverJarPath = targetFile;
                return;
            }

            try (InputStream is = NeoForgeNativeLibBridge.class.getClassLoader().getResourceAsStream(SERVER_JAR_RESOURCE_PATH)) {
                if (is == null) {
                    LOGGER.error("无法在模组包内找到: {}", SERVER_JAR_RESOURCE_PATH);
                    return;
                }
                Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                serverJarPath = targetFile;
            }
        } catch (Exception e) {
            LOGGER.error("提取 JavaLlamaServer JAR 失败", e);
        }
    }

    public synchronized void loadNatives() {
        if (nativesLoaded) return;

        Path dir = getNativesDir();
        if (!Files.isDirectory(dir)) {
            LOGGER.error("Native 目录不存在: {}", dir);
            return;
        }
        // loadDll(dir, "onnxruntime.dll");

        System.setProperty("sherpa_onnx.native.path", dir.toAbsolutePath().toString());

        nativesLoaded = true;
        LOGGER.info("核心 Native 库加载完成");
    }

    private void loadDll(Path dir, String dllName) {
        Path dllPath = dir.resolve(dllName);
        if (!Files.exists(dllPath)) {
            LOGGER.error("DLL 文件不存在: {}", dllPath);
            return;
        }
        try {
            System.load(dllPath.toAbsolutePath().toString());
            LOGGER.info("已加载 DLL: {}", dllPath.getFileName());
        } catch (UnsatisfiedLinkError e) {
            if (e.getMessage() != null && e.getMessage().contains("already loaded in another classloader")) {
                LOGGER.info("DLL 已在其他 ClassLoader 中加载，视为成功: {}", dllName);
            } else {
                LOGGER.error("加载 DLL 失败: {}", dllName, e);
                throw e;
            }
        }
    }
    private boolean shouldSkipExtract(String resourcePath, Path targetFile) {
        if (!Files.exists(targetFile)) return false;
        try {
            long diskSize = Files.size(targetFile);
            long jarSize = getResourceSize(resourcePath);
            if (jarSize > 0 && diskSize == jarSize) return true;
        } catch (IOException e) {
            // will re-extract
        }
        return false;
    }

    private long getResourceSize(String resourcePath) {
        try {
            URL url = NeoForgeNativeLibBridge.class.getClassLoader().getResource(resourcePath);
            if (url == null) return -1;
            if ("jar".equals(url.getProtocol())) {
                try (InputStream is = NeoForgeNativeLibBridge.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) return -1;
                    long size = 0;
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = is.read(buf)) != -1) size += read;
                    return size;
                }
            }
            return new File(url.toURI()).length();
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<String> listResourceDlls() {
        List<String> dllNames = new ArrayList<>();
        
        // 1. 开发环境(IDEA)下，直接读物理硬盘的文件夹，速度最快
        URL resUrl = NeoForgeNativeLibBridge.class.getClassLoader().getResource(DLL_RESOURCE_PREFIX);
        if (resUrl != null && "file".equals(resUrl.getProtocol())) {
            try {
                Path dir = Path.of(resUrl.toURI());
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> stream = Files.list(dir)) {
                        stream.filter(p -> p.toString().endsWith(".dll"))
                            .map(p -> p.getFileName().toString())
                            .forEach(dllNames::add);
                    }
                }
            } catch (Exception e) {
                // 开发环境读物理文件失败，降级走下面的穷举法
            }
        }

        // 2. 核心保底逻辑：如果上面没读到（说明在 jar 包里运行），用已知列表逐个去 jar 包里试！
        if (dllNames.isEmpty()) {
            String[] knownDlls = {
                // "onnxruntime.dll", 

                "sherpa-onnx-jni.dll", "llama.dll", "ggml.dll", 
                "ggml-base.dll", "ggml-vulkan.dll", "ggml-cpu-alderlake.dll", 
                "ggml-cpu-cannonlake.dll", "ggml-cpu-cascadelake.dll", "ggml-cpu-haswell.dll", 
                "ggml-cpu-icelake.dll", "ggml-cpu-sandybridge.dll", "ggml-cpu-skylakex.dll", 
                "ggml-cpu-sse42.dll", "ggml-cpu-x64.dll", 
                "Java_org_argeo_jjml_ggml.dll", "Java_org_argeo_jjml_llm.dll", 
                "Java_org_argeo_jjml_mtmd.dll", "Java_org_argeo_jjml_whisper.dll", "whisper.dll"
            };
            
            for (String dll : knownDlls) {
                // 直接用完整路径去问 jar 包要文件，绝对能穿透！
                if (NeoForgeNativeLibBridge.class.getClassLoader().getResource(DLL_RESOURCE_PREFIX + dll) != null) {
                    dllNames.add(dll);
                }
            }
        }

        Collections.sort(dllNames);
        return dllNames;
    }
}
