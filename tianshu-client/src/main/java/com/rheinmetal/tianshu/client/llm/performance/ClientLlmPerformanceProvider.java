package com.rheinmetal.tianshu.client.llm.performance;

import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceProvider;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceSnapshot;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;

import java.util.List;

public final class ClientLlmPerformanceProvider implements LlmPerformanceProvider {
    private static final String AUTO_DEVICE_ID = "auto";
    private static final String CPU_DEVICE_ID = "cpu";
    private static final String MANUAL_CPU_DEVICE_ID = "cpu:manual";
    private static final long FPS_STALE_MILLIS = 3000L;

    private final LlmConfiguration config;
    private volatile long lastFrameAtNanos;
    private volatile long lastFpsSampleAtMillis;
    private volatile double smoothedFps;

    public ClientLlmPerformanceProvider(LlmConfiguration config) {
        this.config = config;
    }

    public void markFrame() {
        long nowNanos = System.nanoTime();
        long previous = lastFrameAtNanos;
        lastFrameAtNanos = nowNanos;
        if (previous <= 0L) {
            return;
        }
        long delta = nowNanos - previous;
        if (delta <= 0L) {
            return;
        }
        double fps = 1_000_000_000.0D / delta;
        smoothedFps = smoothedFps <= 0.0D ? fps : (smoothedFps * 0.85D + fps * 0.15D);
        lastFpsSampleAtMillis = System.currentTimeMillis();
    }

    @Override
    public LlmPerformanceSnapshot performanceSnapshot() {
        if (config == null) {
            return LlmPerformanceSnapshot.unavailable();
        }
        List<GpuInfo.GpuDevice> devices = GpuInfo.devices();
        String configuredDevice = normalizeDeviceId(config.getLlmGpuDeviceId());
        if (isCpuDevice(configuredDevice)) {
            return new LlmPerformanceSnapshot(true, false, false, currentFps(), false, 0.0D, 0L, 0L, System.currentTimeMillis());
        }
        GpuInfo.GpuDevice llmDevice = resolveLlmDevice(devices, configuredDevice);
        if (llmDevice == null) {
            return LlmPerformanceSnapshot.unavailable();
        }
        GpuInfo.GpuDevice renderDevice = devices.isEmpty() ? null : devices.get(0);
        boolean sharesRenderGpu = renderDevice != null && renderDevice.id().equalsIgnoreCase(llmDevice.id());
        return new LlmPerformanceSnapshot(
                true,
                true,
                sharesRenderGpu,
                currentFps(),
                llmDevice.hasUtilization(),
                llmDevice.hasUtilization() ? llmDevice.utilization() : 0.0D,
                llmDevice.vramUsedBytes(),
                llmDevice.vramTotalBytes(),
                System.currentTimeMillis()
        );
    }

    private int currentFps() {
        long now = System.currentTimeMillis();
        if (lastFpsSampleAtMillis <= 0L || now - lastFpsSampleAtMillis > FPS_STALE_MILLIS) {
            return 0;
        }
        return Math.max(0, (int) Math.round(smoothedFps));
    }

    private static GpuInfo.GpuDevice resolveLlmDevice(List<GpuInfo.GpuDevice> devices, String configuredDevice) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        if (isAutoDevice(configuredDevice)) {
            return devices.size() >= 2 ? devices.get(1) : devices.get(0);
        }
        String normalized = normalizeDeviceIndex(configuredDevice);
        for (GpuInfo.GpuDevice device : devices) {
            if (device.id().equalsIgnoreCase(normalized)) {
                return device;
            }
        }
        return null;
    }

    private static boolean isAutoDevice(String value) {
        return value == null || value.isBlank() || AUTO_DEVICE_ID.equalsIgnoreCase(value.trim());
    }

    private static boolean isCpuDevice(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return CPU_DEVICE_ID.equalsIgnoreCase(normalized) || MANUAL_CPU_DEVICE_ID.equalsIgnoreCase(normalized);
    }

    private static String normalizeDeviceId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeDeviceIndex(String value) {
        String normalized = normalizeDeviceId(value);
        return normalized.startsWith("#") ? normalized.substring(1) : normalized;
    }
}
