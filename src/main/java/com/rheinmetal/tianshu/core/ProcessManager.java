package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.config.Config;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProcessManager {
    private final Map<ServiceType, Process> processes = new HashMap<>();
    private final Map<ServiceType, Thread> monitoringThreads = new HashMap<>();

    public enum ServiceType {
        ASR("ASR服务"),
        LLM("LLM服务"),
        TTS("TTS服务");

        private final String name;

        ServiceType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public int getPort() {
            switch (this) {
                case ASR: return Config.ASR_PORT.get();
                case LLM: return Config.LLM_PORT.get();
                case TTS: return Config.TTS_PORT.get();
                default: return 0;
            }
        }
    }

    public void stopServices() {
        Tianshu.LOGGER.info("正在停止本地推理服务");
        for (ServiceType serviceType : ServiceType.values()) {
            stopService(serviceType);
        }
        Tianshu.LOGGER.info("本地推理服务停止完成");
    }

    public void stopService(ServiceType serviceType) {
        Process process = processes.get(serviceType);
        if (process != null) {
            Tianshu.LOGGER.info("停止{}", serviceType.getName());
            Thread monitoringThread = monitoringThreads.get(serviceType);
            if (monitoringThread != null) {
                monitoringThread.interrupt();
                monitoringThreads.remove(serviceType);
            }
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    Tianshu.LOGGER.warn("{} 拒绝退出，执行强制终止...", serviceType.getName());
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                } else {
                    Tianshu.LOGGER.info("{}已退出，等待系统回收GPU资源...", serviceType.getName());
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                Tianshu.LOGGER.error("停止{}时发生异常", serviceType.getName(), e);
            }
            processes.remove(serviceType);
            Tianshu.LOGGER.info("{}已停止", serviceType.getName());
        }
    }

    private void startMonitoringThread(ServiceType serviceType, Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                    Tianshu.LOGGER.info("[{}] {}", serviceType.getName(), line);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Tianshu.LOGGER.error("监控{}时发生错误", serviceType.getName(), e);
                }
            }
            if (process.isAlive()) {
                process.destroy();
            } else {
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    Tianshu.LOGGER.error("{}异常退出，退出码: {}", serviceType.getName(), exitCode);
                }
            }
            processes.remove(serviceType);
            monitoringThreads.remove(serviceType);
        });
        thread.setDaemon(true);
        thread.start();
        monitoringThreads.put(serviceType, thread);
    }

    private boolean waitForServiceReady(ServiceType serviceType, int port) {
        int timeout = 60;
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeout * 1000L) {
            if (isServiceReady(port)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isServiceReady(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isServiceRunning(ServiceType serviceType) {
        Process process = processes.get(serviceType);
        return process != null && process.isAlive();
    }

    public Process getServiceProcess(ServiceType serviceType) {
        return processes.get(serviceType);
    }

    public void startLlmServerForDev() {
        Tianshu.LOGGER.info("启动 JavaLlamaServer (独立 JVM 进程)");

        if (isServiceRunning(ServiceType.LLM)) {
            Tianshu.LOGGER.warn("检测到上一次 LLM 服务未完全停止，强制清理...");
            stopService(ServiceType.LLM);
        }
        try {
            NativeLibManager.extractServerJar();
            Path serverJarPath = NativeLibManager.getServerJarPath();
            if (serverJarPath == null || !Files.exists(serverJarPath)) {
                Tianshu.LOGGER.error("JavaLlamaServer JAR 未就绪");
                return;
            }

            Path nativesDir = NativeLibManager.getNativesDir().toAbsolutePath();
            String nativeLibPath = nativesDir.toString();
            String modelPath = Config.getLlmGgufFilePath().toString();

            if (!Files.exists(Path.of(modelPath))) {
                Tianshu.LOGGER.error("模型文件不存在: {}", modelPath);
                return;
            }

            Tianshu.LOGGER.info("Server JAR: {}", serverJarPath);
            Tianshu.LOGGER.info("Native lib path: {}", nativeLibPath);
            Tianshu.LOGGER.info("Model path: {}", modelPath);

            // 获取当前 MC 使用的 Java 路径，直接用原生的，不再搞复制/硬链接
            String currentJava = ProcessHandle.current().info().command().orElse("java");

            List<String> command = new ArrayList<>();
            command.add(currentJava); // 纯粹的原始路径
            command.add("-Xmx1G");//分配给llm的内存大小
            command.add("-Djava.library.path=" + nativeLibPath); // 唯一需要指定的，让 Java 能找到 natives 里的 ggml-vulkan.dll
            command.add("-cp");
            command.add(serverJarPath.toAbsolutePath().toString());
            command.add("com.javallamaserver.core.ServerApp");
            command.add("-m");
            command.add(modelPath);
            command.add("-c");
            command.add("4096"); 
            command.add("-ngl");
            command.add("999");
            command.add("--host");
            command.add("127.0.0.1");
            command.add("--port");
            command.add(String.valueOf(Config.LLM_PORT.get()));

            Tianshu.LOGGER.info("启动命令: {}", String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.directory(nativesDir.toFile());
            
            Process process = processBuilder.start();
            processes.put(ServiceType.LLM, process);

            startMonitoringThread(ServiceType.LLM, process);

            if (waitForServiceReady(ServiceType.LLM, Config.LLM_PORT.get())) {
                Tianshu.LOGGER.info("JavaLlamaServer 启动成功");
                com.rheinmetal.tianshu.client.TianshuClient.llmReady = true;
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (net.minecraft.client.Minecraft.getInstance().player != null) {
                        net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§a[天枢] §f中枢核心已就绪"), false
                        );
                    }
                });
            } else {
                Tianshu.LOGGER.error("JavaLlamaServer 启动超时");
                stopService(ServiceType.LLM);
            }
        } catch (Exception e) {
            Tianshu.LOGGER.error("启动 JavaLlamaServer 失败", e);
        }
    }
}
