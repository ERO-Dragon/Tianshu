package com.rheinmetal.tianshu.client.gui.llm;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;

public interface LlmSettingsAccess extends LlmConfiguration {
    void setLlmEnabled(boolean enabled);
    boolean isLlmDiagnosticsEnabled();
    void setLlmDiagnosticsEnabled(boolean enabled);
    void setCustomLlmName(String name);
    String getLlmGpuDeviceId();
    void setLlmGpuDeviceId(String deviceId);
    void setLlmFrameGuardEnabled(boolean enabled);
    int getLlmFrameGuardTargetFps();
    void setLlmFrameGuardTargetFps(int fps);
    void setLlmMtpEnabled(boolean enabled);
    void save();
}
