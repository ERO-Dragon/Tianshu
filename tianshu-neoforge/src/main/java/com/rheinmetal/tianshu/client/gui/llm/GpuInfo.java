package com.rheinmetal.tianshu.client.gui.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class GpuInfo {
    private static final long REFRESH_INTERVAL_MILLIS = 1000L;
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.empty());
    private static final AtomicReference<CompletableFuture<Void>> REFRESH_TASK = new AtomicReference<>();

    private GpuInfo() {
    }

    static List<GpuDevice> devices() {
        return snapshot().devices();
    }

    static boolean detecting() {
        snapshot();
        CompletableFuture<Void> task = REFRESH_TASK.get();
        return task != null && !task.isDone();
    }

    static boolean detected() {
        return SNAPSHOT.get().updatedAtMillis() > 0L;
    }

    static void requestRefresh(Runnable onComplete) {
        requestRefresh(System.currentTimeMillis(), onComplete);
    }

    static GpuDevice selectedDevice(String selectedId) {
        List<GpuDevice> devices = devices();
        if (devices.isEmpty()) {
            return null;
        }
        String normalized = normalizeId(selectedId);
        if (!normalized.isBlank()) {
            for (GpuDevice device : devices) {
                if (device.id().equalsIgnoreCase(normalized)) {
                    return device;
                }
            }
        }
        return devices.get(0);
    }

    private static Snapshot snapshot() {
        Snapshot current = SNAPSHOT.get();
        long now = System.currentTimeMillis();
        if (current.updatedAtMillis() <= 0L) {
            requestRefresh(now);
        } else if (now - current.updatedAtMillis() > REFRESH_INTERVAL_MILLIS) {
            requestRefresh(now);
        }
        return SNAPSHOT.get();
    }

    private static void requestRefresh(long now) {
        requestRefresh(now, null);
    }

    private static void requestRefresh(long now, Runnable onComplete) {
        CompletableFuture<Void> running = REFRESH_TASK.get();
        if (running != null && !running.isDone()) {
            if (onComplete != null) {
                running.whenComplete((ignored, error) -> onComplete.run());
            }
            return;
        }
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                SNAPSHOT.set(querySnapshot(now));
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        if (!REFRESH_TASK.compareAndSet(running, task)) {
            task.cancel(false);
        }
    }

    private static Snapshot querySnapshot(long requestedAtMillis) {
        List<GpuDevice> nvidia = queryNvidiaSmi();
        if (!nvidia.isEmpty()) {
            return new Snapshot(nvidia, requestedAtMillis);
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return new Snapshot(queryWindowsDevices(), requestedAtMillis);
            }
        } catch (Exception ignored) {
        }
        return Snapshot.emptyAt(requestedAtMillis);
    }

    private static List<GpuDevice> queryNvidiaSmi() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=index,name,memory.total,memory.used",
                    "--format=csv,noheader,nounits"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (output.isBlank()) {
                return List.of();
            }
            List<GpuDevice> devices = new ArrayList<>();
            for (String line : output.lines().toList()) {
                String[] parts = line.split(",", 4);
                if (parts.length < 2) {
                    continue;
                }
                String id = normalizeId(parts[0]);
                String name = parts[1].trim();
                long total = parts.length > 2 ? parseLong(parts[2]) * 1024L * 1024L : 0L;
                long used = parts.length > 3 ? parseLong(parts[3]) * 1024L * 1024L : 0L;
                devices.add(new GpuDevice(id.isBlank() ? String.valueOf(devices.size()) : id, name, total, used));
            }
            return List.copyOf(devices);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<GpuDevice> queryWindowsDevices() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "$gpus = Get-CimInstance Win32_VideoController; " +
                        "$i = 0; " +
                        "foreach ($gpu in $gpus) { " +
                        "  $name = [string]$gpu.Name; " +
                        "  if ([string]::IsNullOrWhiteSpace($name)) { continue }; " +
                        "  $ram = [uint64]($gpu.AdapterRAM); " +
                        "  Write-Output ([string]$i + '|' + $name + '|' + [string]$ram); " +
                        "  $i++ " +
                        "}");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean completed = process.waitFor(3, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            return List.of();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (output.isBlank()) {
            return List.of();
        }
        List<GpuDevice> devices = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 2) {
                continue;
            }
            String id = normalizeId(parts[0]);
            String name = parts[1].trim();
            long total = parts.length > 2 ? parseLong(parts[2]) : 0L;
            devices.add(new GpuDevice(id.isBlank() ? String.valueOf(devices.size()) : id, name, total, 0L));
        }
        return List.copyOf(devices);
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    record GpuDevice(String id, String name, long vramTotalBytes, long vramUsedBytes) {
        GpuDevice {
            id = normalizeId(id);
            name = name == null || name.isBlank() ? "GPU " + id : name.trim();
            vramTotalBytes = Math.max(0L, vramTotalBytes);
            vramUsedBytes = Math.max(0L, vramUsedBytes);
        }
    }

    private record Snapshot(List<GpuDevice> devices, long updatedAtMillis) {
        private Snapshot {
            devices = devices == null ? List.of() : List.copyOf(devices);
            updatedAtMillis = Math.max(0L, updatedAtMillis);
        }

        private static Snapshot empty() {
            return emptyAt(0L);
        }

        private static Snapshot emptyAt(long updatedAtMillis) {
            return new Snapshot(List.of(), updatedAtMillis);
        }
    }
}
