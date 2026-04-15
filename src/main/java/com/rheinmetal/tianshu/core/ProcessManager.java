package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.config.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProcessManager {
    private final Map<ServiceType, Process> processes = new HashMap<>();
    private final Map<ServiceType, Thread> monitoringThreads = new HashMap<>();

    // 服务类型枚举
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

        public String getPath() {
            switch (this) {
                case ASR: return Config.getAsrEnginePath().toString(); // 使用约定的路径
                case LLM: return Config.getLlmEnginePath().toString();
                case TTS: return Config.getTtsEnginePath().toString();
                default:
                    return "";
            }
        }

        public int getPort() {
            switch (this) {
                case ASR: return Config.ASR_PORT.get(); // 使用新 Config 的端口名
                case LLM: return Config.LLM_PORT.get();
                case TTS: return Config.TTS_PORT.get();
                default:
                    return 0;
            }
        }
    }

    // 启动所有服务
    public void startServices() {
        Tianshu.LOGGER.info("开始启动本地推理服务");

        for (ServiceType serviceType : ServiceType.values()) {
            startService(serviceType);
        }

        Tianshu.LOGGER.info("本地推理服务启动完成");
    }

    // 启动单个服务
    public boolean startService(ServiceType serviceType) {
        String servicePath = serviceType.getPath();
        int port = serviceType.getPort();

        if (servicePath.isEmpty()) {
            Tianshu.LOGGER.warn("{}路径未配置，跳过启动", serviceType.getName());
            return false;
        }

        // 检查端口是否被占用
        if (isPortInUse(port)) {
            Tianshu.LOGGER.warn("{}端口 {} 已被占用，跳过启动", serviceType.getName(), port);
            return false;
        }

        Tianshu.LOGGER.info("启动{}，路径: {}, 端口: {}", serviceType.getName(), servicePath, port);

        try {
            // 构建启动命令
            ProcessBuilder processBuilder = new ProcessBuilder(servicePath);
            processBuilder.redirectErrorStream(true);

            // 启动进程
            Process process = processBuilder.start();
            processes.put(serviceType, process);

            // 启动监控线程
            startMonitoringThread(serviceType, process);

            // 等待服务启动
            if (waitForServiceReady(serviceType, port)) {
                Tianshu.LOGGER.info("{}启动成功", serviceType.getName());
//临时写在这里
if (serviceType == ServiceType.TTS) {
    com.rheinmetal.tianshu.client.TianshuClient.ttsReady = true;
    net.minecraft.client.Minecraft.getInstance().execute(() -> {
        if (net.minecraft.client.Minecraft.getInstance().player != null) {
            net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§a[天枢] §f语音辅助功能已就绪"), false
            );
        }
    });
}
                return true;
            } else {
                Tianshu.LOGGER.error("{}启动超时", serviceType.getName());
                stopService(serviceType);
                return false;
            }
        } catch (IOException e) {
            Tianshu.LOGGER.error("启动{}失败", serviceType.getName(), e);
            return false;
        }
    }

    // 停止所有服务
    public void stopServices() {
        Tianshu.LOGGER.info("正在停止本地推理服务");

        for (ServiceType serviceType : ServiceType.values()) {
            stopService(serviceType);
        }

        Tianshu.LOGGER.info("本地推理服务停止完成");
    }

    // 停止单个服务
    public void stopService(ServiceType serviceType) {
        Process process = processes.get(serviceType);
        if (process != null) {
            Tianshu.LOGGER.info("停止{}", serviceType.getName());
            // 停止监控线程
            Thread monitoringThread = monitoringThreads.get(serviceType);
            if (monitoringThread != null) {
                monitoringThread.interrupt();
                monitoringThreads.remove(serviceType);
            }
            
            // 先尝试温柔地杀
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    // 5秒还没死，启动终极树杀逻辑，防止子进程吃显存
                    Tianshu.LOGGER.warn("{} 拒绝退出，执行强制树杀...", serviceType.getName());
                    if (System.getProperty("os.name").toLowerCase().contains("win")) {
                        // Windows 下的终极手段：/F 强制，/T 杀进程树
                        Runtime.getRuntime().exec("taskkill /F /T /PID " + process.pid());
                    } else {
                        // Linux 下的终极手段
                        Runtime.getRuntime().exec("kill -9 -" + process.pid());
                    }
                }
            } catch (Exception e) {
                Tianshu.LOGGER.error("停止{}时发生异常", serviceType.getName(), e);
            }
            processes.remove(serviceType);
            Tianshu.LOGGER.info("{}已停止", serviceType.getName());
        }
    }

    // 启动监控线程
    private void startMonitoringThread(ServiceType serviceType, Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                    // Tianshu.LOGGER.info("{}: {}", serviceType.getName(), line); 
                    // Tianshu.LOGGER.debug("{}: {}", serviceType.getName(), line);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Tianshu.LOGGER.error("监控{}时发生错误", serviceType.getName(), e);
                }
            }

            // 检查进程是否异常退出
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

    // 等待服务就绪
    private boolean waitForServiceReady(ServiceType serviceType, int port) {
        int timeout = 30;
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeout * 1000) {
            if (isServiceReady(port)) {
                return true;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Tianshu.LOGGER.error("等待{}就绪时被中断", serviceType.getName(), e);
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    // 检查服务是否就绪
    private boolean isServiceReady(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // 检查端口是否被占用
    private boolean isPortInUse(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // 检查服务是否运行
    public boolean isServiceRunning(ServiceType serviceType) {
        Process process = processes.get(serviceType);
        return process != null && process.isAlive();
    }

    // 获取服务进程
    public Process getServiceProcess(ServiceType serviceType) {
        return processes.get(serviceType);
    }

    // 开发模式下启动LLM服务器    // 开发模式下启动LLM服务器
    public void startLlmServerForDev() {

        cleanOrphanLlmProcess(); 
        Tianshu.LOGGER.info("启动开发模式 LLM 服务器");
        
        // 1. 获取约定的引擎专属目录
        java.io.File engineDir = Config.getLlmEnginePath().toFile();
        
        // 防御性检查：目录存在吗？
        if (!engineDir.exists() || !engineDir.isDirectory()) {
            Tianshu.LOGGER.error("LLM引擎目录不存在: {}", engineDir.getAbsolutePath());
            return;
        }

        // 2. 智能扫描：在这个文件夹里找 .exe 文件
        java.io.File[] exeFiles = engineDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".exe"));
        
        if (exeFiles == null || exeFiles.length == 0) {
            Tianshu.LOGGER.error("在 {} 中找不到任何 .exe 文件！", engineDir.getAbsolutePath());
            return;
        }
        
        if (exeFiles.length > 1) {
            Tianshu.LOGGER.warn("在 {} 中发现多个 .exe 文件，默认使用第一个: {}", engineDir.getAbsolutePath(), exeFiles[0].getName());
        }

        // 拿到我们要启动的真实 exe 文件
        java.io.File targetExe = exeFiles[0];
        Tianshu.LOGGER.info("自动捕获到 LLM 引擎程序: {}", targetExe.getName());

        try {
            // 3. 获取模型路径
            String modelPath = Config.getLlmGgufFilePath().toString();
            Tianshu.LOGGER.info("模型路径: {}", modelPath);

            ProcessBuilder processBuilder = new ProcessBuilder(
                targetExe.getAbsolutePath(), // 直接用扫描出来的绝对路径
                "-m", modelPath,
                "-c", "2048",
                "-ngl", "99",
                "--port", String.valueOf(Config.LLM_PORT.get()),
                "--host", "127.0.0.1"
            );
            
            processBuilder.redirectErrorStream(true);
            
            // 【终极防御】工作目录必须是 exe 所在的文件夹，这样才能找到旁边的 dll
            processBuilder.directory(engineDir);

            // 【终极防御 2.0】：强行把引擎目录追加到 PATH 环境变量中！
            // 这是解决 0xC0000135 找不到 DLL 的最暴力、最有效的手段！
            // java.util.Map<String, String> env = processBuilder.environment();
            // String currentPath = env.get("PATH");
            // if (currentPath != null) {
            //     env.put("PATH", engineDir.getAbsolutePath() + ";" + currentPath);
            // } else {
            //     env.put("PATH", engineDir.getAbsolutePath());
            // }

            // 启动进程
            Process process = processBuilder.start();
            processes.put(ServiceType.LLM, process);

            // 启动监控线程
            startMonitoringThread(ServiceType.LLM, process);

            // 等待服务启动
            if (waitForServiceReady(ServiceType.LLM, Config.LLM_PORT.get())) {
                Tianshu.LOGGER.info("开发模式 LLM 服务器启动成功");
                // 【新增】发出 LLM 就绪信号
                com.rheinmetal.tianshu.client.TianshuClient.llmReady = true;
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (net.minecraft.client.Minecraft.getInstance().player != null) {
                        net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§a[天枢] §f中枢核心已就绪"), false
                        );
                    }
                });
            } else {
                Tianshu.LOGGER.error("开发模式 LLM 服务器启动超时");
                stopService(ServiceType.LLM);
            }
        } catch (Exception e) {
            Tianshu.LOGGER.error("启动开发模式 LLM 服务器失败", e);
        }
    }
    private void cleanOrphanLlmProcess() {
        try {
            // 假设你的 LLM 服务叫 llama-server.exe 或者 python.exe (看你具体用的什么)
            String processName = "llama-server.exe"; 
            
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", processName);
            pb.redirectErrorStream(true);
            Process killProcess = pb.start();
            killProcess.waitFor();
            Tianshu.LOGGER.info("已执行僵尸进程清理检查: {}", processName);
        } catch (Exception e) {
            // 如果找不到进程 taskkill 会报错，直接忽略即可
        }
    }
}