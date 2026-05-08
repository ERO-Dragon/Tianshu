package com.rheinmetal.tianshu.function.llm.server;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class LlmServerProcessManager {

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final INativeLibBridge nativeLibBridge;
    private final ProtocolExecutorManager executorManager;
    private final Runnable onLlmReady;
    private volatile Process llmProcess;
    private volatile ProtocolTaskHandle monitoringTask;

    public LlmServerProcessManager(IGameEnvironment env, ITianshuConfig config, INativeLibBridge nativeLibBridge, ProtocolExecutorManager executorManager, Runnable onLlmReady) {
        this.env = env;
        this.config = config;
        this.nativeLibBridge = nativeLibBridge;
        this.executorManager = executorManager;
        this.onLlmReady = onLlmReady;
    }

    public void stopServices() {
        stopLlmServer();
    }

    public void stopLlmServer() {
        Process process = llmProcess;
        if (process == null) {
            return;
        }
        env.info("停止LLM服务");
        ProtocolTaskHandle task = monitoringTask;
        if (task != null) {
            task.cancel("llm service stopping");
            monitoringTask = null;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                env.info("LLM服务拒绝退出，执行强制终止...");
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            } else {
                env.info("LLM服务已退出，等待系统回收GPU资源...");
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            env.error("停止LLM服务时发生异常", e);
        }
        if (llmProcess == process) {
            llmProcess = null;
        }
        env.info("LLM服务已停止");
    }

    private void startMonitoringTask(Process process) {
        ProtocolTaskHandle task = executorManager.submit(processTaskSpec("llm.monitor"), () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                    env.info("[LLM服务] " + line);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    env.error("监控LLM服务时发生错误", e);
                }
            }
            if (process.isAlive()) {
                process.destroy();
            } else {
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    env.error("LLM服务异常退出，退出码: " + exitCode, null);
                }
            }
            if (llmProcess == process) {
                llmProcess = null;
            }
            monitoringTask = null;
        });
        monitoringTask = task;
    }

    private boolean waitForServiceReady(int port) {
        int timeout = 60;
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeout * 1000L) {
            if (isLlmServiceReady(port)) {
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

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isLlmServiceReady(int port) {
        if (!isPortOpen(port)) {
            return false;
        }
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/health");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            env.warn("LLM 健康检查返回状态码: " + code);
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isLlmRunning() {
        Process process = llmProcess;
        return process != null && process.isAlive();
    }

    public boolean isLlmHealthy() {
        if (!isLlmRunning()) {
            return false;
        }
        return isLlmServiceReady(config.getLlmPort());
    }

    public Process getLlmProcess() {
        return llmProcess;
    }

    private ProtocolTaskSpec processTaskSpec(String key) {
        return ProtocolTaskSpec.builder()
                .moduleId("module.llm")
                .lane(ExecutionLane.LONG)
                .concurrencyKey("module.llm:process:" + key)
                .maxConcurrency(1)
                .queueCapacity(1)
                .build();
    }

    public void startLlmServer() {
        executorManager.submit(processTaskSpec("llm.start"), this::startLlmServerBlocking);
    }

    private void startLlmServerBlocking() {
        env.info("启动 JavaLlamaServer (独立 JVM 进程)");

        if (!prepareLlmPort()) {
            env.error("LLM 端口不可用，取消启动", null);
            return;
        }

        if (isLlmRunning()) {
            env.info("检测到上一次 LLM 服务未完全停止，强制清理...");
            stopLlmServer();
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
            llmProcess = process;

            startMonitoringTask(process);

            if (waitForServiceReady(config.getLlmPort())) {
                env.info("JavaLlamaServer 启动成功");
                onLlmReady.run();
            } else {
                env.error("JavaLlamaServer 启动超时", null);
                stopLlmServer();
            }
        } catch (Exception e) {
            env.error("启动 JavaLlamaServer 失败", e);
        }
    }

    private boolean prepareLlmPort() {
        int port = config.getLlmPort();
        if (!isPortOpen(port)) {
            return true;
        }
        env.warn("检测到 LLM 端口 " + port + " 已被占用，开始保守清理检查...");
        if (!killOrphanedLlmProcess()) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (!isPortOpen(port)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isPortOpen(port);
    }

    private boolean killOrphanedLlmProcess() {
        int port = config.getLlmPort();
        if (!isPortOpen(port)) {
            return true;
        }

        long myPid = ProcessHandle.current().pid();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        boolean killedAny = false;

        try {
            if (isWindows) {
                killedAny = killOrphanedOnWindows(port, myPid);
            } else {
                killedAny = killOrphanedOnUnix(port, myPid);
            }
        } catch (Exception e) {
            env.error("检测残留 LLM 进程失败", e);
            return false;
        }

        if (isPortOpen(port)) {
            if (killedAny) {
                env.error("LLM 端口 " + port + " 仍被占用，停止继续启动以避免误杀其他服务", null);
            } else {
                env.warn("LLM 端口 " + port + " 被进程占用，未执行清理");
            }
            return false;
        }

        env.info("残留进程已清理，端口 " + port + " 已释放");
        return true;
    }

    private boolean killOrphanedOnWindows(int port, long myPid) throws Exception {
        boolean killedAny = false;
        ProcessBuilder pb = new ProcessBuilder("netstat", "-ano");
        pb.redirectErrorStream(true);
        Process netstat = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(netstat.getInputStream()))) {
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

                if (!isOwnedLlmProcess(pid, port)) {
                    env.warn("PID " + pid + " 不是可确认的 LLM 残留进程，跳过终止");
                    continue;
                }

                try {
                    ProcessHandle.of(pid).ifPresent(ph -> {
                        env.info("正在终止残留 JVM 进程 PID: " + pid);
                        ph.destroyForcibly();
                    });
                    killedAny = true;
                    Thread.sleep(1000);
                } catch (Exception e) {
                    env.error("终止进程 PID " + pid + " 失败", e);
                }
            }
        }
        netstat.waitFor(5, TimeUnit.SECONDS);
        netstat.destroy();
        return killedAny;
    }

    private boolean killOrphanedOnUnix(int port, long myPid) throws Exception {
        boolean killedAny = false;
        ProcessBuilder pb = new ProcessBuilder("lsof", "-i", ":" + port, "-t");
        pb.redirectErrorStream(true);
        Process lsof = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(lsof.getInputStream()))) {
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

                if (!isOwnedLlmProcess(pid, port)) {
                    env.warn("PID " + pid + " 不是可确认的 LLM 残留进程，跳过终止");
                    continue;
                }

                try {
                    ProcessHandle.of(pid).ifPresent(ph -> {
                        env.info("正在终止残留 JVM 进程 PID: " + pid);
                        ph.destroyForcibly();
                    });
                    killedAny = true;
                    Thread.sleep(1000);
                } catch (Exception e) {
                    env.error("终止进程 PID " + pid + " 失败", e);
                }
            }
        }
        lsof.waitFor(5, TimeUnit.SECONDS);
        lsof.destroy();
        return killedAny;
    }

    private boolean isOwnedLlmProcess(long pid, int port) {
        try {
            Optional<ProcessHandle> handleOptional = ProcessHandle.of(pid);
            if (handleOptional.isEmpty()) {
                return false;
            }
            ProcessHandle handle = handleOptional.get();
            ProcessHandle.Info info = handle.info();
            String command = info.command().orElse("").toLowerCase();
            if (!(command.contains("java") || command.contains("javaw"))) {
                return false;
            }
            String[] arguments = info.arguments().orElse(new String[0]);
            String joinedArguments = String.join(" ", arguments).toLowerCase();
            return joinedArguments.contains("com.javallamaserver.core.serverapp")
                    || joinedArguments.contains("javallamaserver")
                    || joinedArguments.contains("--port " + port)
                    || joinedArguments.contains("--port=" + port);
        } catch (Exception e) {
            return false;
        }
    }
}
