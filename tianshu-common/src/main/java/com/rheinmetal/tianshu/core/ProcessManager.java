package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ProcessManager {

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
    }

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final INativeLibBridge nativeLibBridge;
    private final Map<ServiceType, Process> processes = new ConcurrentHashMap<>();
    private final Map<ServiceType, Thread> monitoringThreads = new ConcurrentHashMap<>();
    private final Runnable onLlmReady;

    public ProcessManager(IGameEnvironment env, ITianshuConfig config, INativeLibBridge nativeLibBridge, Runnable onLlmReady) {
        this.env = env;
        this.config = config;
        this.nativeLibBridge = nativeLibBridge;
        this.onLlmReady = onLlmReady;
    }

    public void stopServices() {
        env.info("正在停止本地推理服务");
        for (ServiceType serviceType : ServiceType.values()) {
            stopService(serviceType);
        }
        env.info("本地推理服务停止完成");
    }

    public void stopService(ServiceType serviceType) {
        Process process = processes.get(serviceType);
        if (process != null) {
            env.info("停止" + serviceType.getName());
            Thread monitoringThread = monitoringThreads.get(serviceType);
            if (monitoringThread != null) {
                monitoringThread.interrupt();
                monitoringThreads.remove(serviceType);
            }
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    env.info(serviceType.getName() + " 拒绝退出，执行强制终止...");
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                } else {
                    env.info(serviceType.getName() + "已退出，等待系统回收GPU资源...");
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                env.error("停止" + serviceType.getName() + "时发生异常", e);
            }
            processes.remove(serviceType);
            env.info(serviceType.getName() + "已停止");
        }
    }

    private void startMonitoringThread(ServiceType serviceType, Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                    env.info("[" + serviceType.getName() + "] " + line);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    env.error("监控" + serviceType.getName() + "时发生错误", e);
                }
            }
            if (process.isAlive()) {
                process.destroy();
            } else {
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    env.error(serviceType.getName() + "异常退出，退出码: " + exitCode, null);
                }
            }
            processes.remove(serviceType);
            monitoringThreads.remove(serviceType);
        });
        thread.setDaemon(true);
        thread.start();
        monitoringThreads.put(serviceType, thread);
    }

    private boolean waitForServiceReady(int port) {
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

    public void startLlmServer() {
        env.info("启动 JavaLlamaServer (独立 JVM 进程)");

        killOrphanedLlmProcess();

        if (isServiceRunning(ServiceType.LLM)) {
            env.info("检测到上一次 LLM 服务未完全停止，强制清理...");
            stopService(ServiceType.LLM);
        }
        try {
            nativeLibBridge.extractServerJar();
            Path serverJarPath = nativeLibBridge.getServerJarPath();
            if (serverJarPath == null || !Files.exists(serverJarPath)) {
                env.error("JavaLlamaServer JAR 未就绪", null);
                return;
            }

            Path nativesDir = nativeLibBridge.getNativesDir().toAbsolutePath();
            String nativeLibPath = nativesDir.toString();
            String modelPath = config.getLlmGgufFilePath().toString();

            if (!Files.exists(Path.of(modelPath))) {
                env.error("模型文件不存在: " + modelPath, null);
                return;
            }

            env.info("Server JAR: " + serverJarPath);
            env.info("Native lib path: " + nativeLibPath);
            env.info("Model path: " + modelPath);

            String currentJava = ProcessHandle.current().info().command().orElse("java");

            List<String> command = new ArrayList<>();
            command.add(currentJava);
            command.add("-Xmx1G");
            command.add("-Djava.library.path=" + nativeLibPath);
            command.add("-cp");
            command.add(serverJarPath.toAbsolutePath().toString());
            command.add("com.javallamaserver.core.ServerApp");
            command.add("-m");
            command.add(modelPath);
            command.add("-c");
            command.add("2060");
            command.add("-ngl");
            command.add("999");
            command.add("--host");
            command.add("127.0.0.1");
            command.add("--port");
            command.add(String.valueOf(config.getLlmPort()));

            env.info("启动命令: " + String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.directory(nativesDir.toFile());
            long currentPid = ProcessHandle.current().pid();
            processBuilder.environment().put("PARENT_PID", String.valueOf(currentPid));

            Process process = processBuilder.start();
            processes.put(ServiceType.LLM, process);

            startMonitoringThread(ServiceType.LLM, process);

            if (waitForServiceReady(config.getLlmPort())) {
                env.info("JavaLlamaServer 启动成功");
                onLlmReady.run();
            } else {
                env.error("JavaLlamaServer 启动超时", null);
                stopService(ServiceType.LLM);
            }
        } catch (Exception e) {
            env.error("启动 JavaLlamaServer 失败", e);
        }
    }

    private void killOrphanedLlmProcess() {
        int port = config.getLlmPort();
        if (!isServiceReady(port)) {
            return;
        }

        env.warn("检测到 LLM 端口 " + port + " 被占用，尝试查找并清理残留进程...");

        long myPid = ProcessHandle.current().pid();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        try {
            if (isWindows) {
                killOrphanedOnWindows(port, myPid);
            } else {
                killOrphanedOnUnix(port, myPid);
            }
        } catch (Exception e) {
            env.error("检测残留 LLM 进程失败", e);
        }

        if (isServiceReady(port)) {
            env.error("LLM 端口 " + port + " 仍被占用，可能启动失败", null);
        } else {
            env.info("残留进程已清理，端口 " + port + " 已释放");
        }

        for (int i = 0; i < 10; i++) {
            if (!isServiceReady(port)) break;
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void killOrphanedOnWindows(int port, long myPid) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("netstat", "-ano");
        pb.redirectErrorStream(true);
        Process netstat = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(netstat.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            String lower = line.toLowerCase();
            if (!lower.contains("listening") || !lower.contains(":" + port + " ")) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5) continue;
            String pidStr = parts[parts.length - 1];
            long pid;
            try {
                pid = Long.parseLong(pidStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid <= 0 || pid == myPid) continue;

            env.info("发现占用端口 " + port + " 的进程 PID: " + pid);

            if (!isJavaProcess(pid)) {
                env.warn("PID " + pid + " 非 Java 进程，跳过终止（端口可能被其他程序占用）");
                continue;
            }

            try {
                ProcessHandle.of(pid).ifPresent(ph -> {
                    env.info("正在终止残留 JVM 进程 PID: " + pid);
                    ph.destroyForcibly();
                });
                Thread.sleep(1000);
            } catch (Exception e) {
                env.error("终止进程 PID " + pid + " 失败", e);
            }
        }
        netstat.waitFor(5, TimeUnit.SECONDS);
        netstat.destroy();
    }

    private void killOrphanedOnUnix(int port, long myPid) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("lsof", "-i", ":" + port, "-t");
        pb.redirectErrorStream(true);
        Process lsof = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(lsof.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            long pid;
            try {
                pid = Long.parseLong(line.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid <= 0 || pid == myPid) continue;

            env.info("发现占用端口 " + port + " 的进程 PID: " + pid);

            if (!isJavaProcess(pid)) {
                env.warn("PID " + pid + " 非 Java 进程，跳过终止（端口可能被其他程序占用）");
                continue;
            }

            try {
                ProcessHandle.of(pid).ifPresent(ph -> {
                    env.info("正在终止残留 JVM 进程 PID: " + pid);
                    ph.destroyForcibly();
                });
                Thread.sleep(1000);
            } catch (Exception e) {
                env.error("终止进程 PID " + pid + " 失败", e);
            }
        }
        lsof.waitFor(5, TimeUnit.SECONDS);
        lsof.destroy();
    }

    private boolean isJavaProcess(long pid) {
        try {
            Optional<String> cmd = ProcessHandle.of(pid).flatMap(ph -> ph.info().command());
            if (cmd.isEmpty()) return false;
            String cmdStr = cmd.get().toLowerCase();
            return cmdStr.contains("java") || cmdStr.contains("javaw");
        } catch (Exception e) {
            return false;
        }
    }
}
