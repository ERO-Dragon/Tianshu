package com.rheinmetal.tianshu.client.gui.llm;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class GpuInfo {
    private static final long REFRESH_INTERVAL_MILLIS = 1000L;
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.empty());
    private static final AtomicReference<CompletableFuture<Void>> REFRESH_TASK = new AtomicReference<>();

    private GpuInfo() {
    }

    static String queryGpuName() {
        return snapshot().gpuName();
    }

    static long[] queryVramBytes() {
        Snapshot snapshot = snapshot();
        return snapshot.vramTotalBytes() <= 0L ? null : new long[]{snapshot.vramTotalBytes(), snapshot.vramUsedBytes()};
    }

    private static Snapshot snapshot() {
        Snapshot current = SNAPSHOT.get();
        long now = System.currentTimeMillis();
        if (now - current.updatedAtMillis() > REFRESH_INTERVAL_MILLIS) {
            requestRefresh(now);
        }
        return SNAPSHOT.get();
    }

    private static void requestRefresh(long now) {
        CompletableFuture<Void> running = REFRESH_TASK.get();
        if (running != null && !running.isDone()) {
            return;
        }
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> SNAPSHOT.set(querySnapshot(now)));
        if (!REFRESH_TASK.compareAndSet(running, task)) {
            task.cancel(false);
        }
    }

    private static Snapshot querySnapshot(long requestedAtMillis) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                return queryWindowsSnapshot(requestedAtMillis);
            }
        } catch (Exception ignored) {
        }
        return Snapshot.emptyAt(requestedAtMillis);
    }

    private static Snapshot queryWindowsSnapshot(long requestedAtMillis) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "$gpu = Get-CimInstance Win32_VideoController | Select-Object -First 1; " +
                        "if ($null -eq $gpu) { exit 0 }; " +
                        "$name = [string]$gpu.Name; " +
                        "$total = [int64]$gpu.AdapterRAM; " +
                        "Write-Output ($name + '|' + $total)");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean completed = process.waitFor(3, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            return Snapshot.emptyAt(requestedAtMillis);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (output.isBlank()) {
            return Snapshot.emptyAt(requestedAtMillis);
        }
        String[] parts = output.lines().findFirst().orElse("").split("\\|", 2);
        String name = parts.length > 0 ? parts[0].trim() : "";
        long total = parts.length > 1 ? parseLong(parts[1]) : 0L;
        return new Snapshot(name, total, 0L, requestedAtMillis);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private record Snapshot(String gpuName, long vramTotalBytes, long vramUsedBytes, long updatedAtMillis) {
        private Snapshot {
            gpuName = gpuName == null || gpuName.isBlank() ? null : gpuName.trim();
            vramTotalBytes = Math.max(0L, vramTotalBytes);
            vramUsedBytes = Math.max(0L, vramUsedBytes);
            updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
        }

        private static Snapshot empty() {
            return emptyAt(0L);
        }

        private static Snapshot emptyAt(long updatedAtMillis) {
            return new Snapshot(null, 0L, 0L, updatedAtMillis);
        }
    }
}
