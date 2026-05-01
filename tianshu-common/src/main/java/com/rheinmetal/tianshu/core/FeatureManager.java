package com.rheinmetal.tianshu.core;

public final class FeatureManager {

    private FeatureManager() {}

    // ═══════ 服务端管控（零信任，默认 false，仅由 S2C 包写入） ═══════
    private static volatile boolean autoEquipAllowed = false;
    private static volatile boolean autoTrashAllowed = false;
    private static volatile boolean highPrecisionModeAllowed = false;

    // ═══════ 客户端偏好（由 ClientConfig 同步，默认 true） ═══════
    private static volatile boolean tianshuEnabled = true;
    private static volatile boolean tacticalRadarEnabled = true;
    private static volatile boolean navigationEnabled = true;
    private static volatile boolean recipePanelEnabled = true;
    private static volatile boolean audioRadarEnabled = true;
    private static volatile boolean companionCardEnabled = true;
    private static volatile boolean durabilityAlertEnabled = true;
    private static volatile boolean chatAssistantEnabled = true;
    private static volatile boolean tacticalMrEnabled = true;

    // ─── 服务端管控 ───

    public static boolean isAutoEquipAllowed() { return autoEquipAllowed; }
    public static void setAutoEquip(boolean v) { autoEquipAllowed = v; }

    public static boolean isAutoTrashAllowed() { return autoTrashAllowed; }
    public static void setAutoTrash(boolean v) { autoTrashAllowed = v; }

    public static boolean isHighPrecisionModeAllowed() { return highPrecisionModeAllowed; }
    public static void setHighPrecisionMode(boolean v) { highPrecisionModeAllowed = v; }

    // ─── 客户端偏好 ───

    public static boolean isTianshuEnabled() { return tianshuEnabled; }
    public static void setTianshuEnabled(boolean v) { tianshuEnabled = v; }

    public static boolean isTacticalRadarEnabled() { return tacticalRadarEnabled; }
    public static void setTacticalRadarEnabled(boolean v) { tacticalRadarEnabled = v; }

    public static boolean isNavigationEnabled() { return navigationEnabled; }
    public static void setNavigationEnabled(boolean v) { navigationEnabled = v; }

    public static boolean isRecipePanelEnabled() { return tianshuEnabled && recipePanelEnabled; }
    public static void setRecipePanelEnabled(boolean v) { recipePanelEnabled = v; }

    public static boolean isAudioRadarEnabled() { return audioRadarEnabled; }
    public static void setAudioRadarEnabled(boolean v) { audioRadarEnabled = v; }

    public static boolean isCompanionCardEnabled() { return companionCardEnabled; }
    public static void setCompanionCardEnabled(boolean v) { companionCardEnabled = v; }

    public static boolean isDurabilityAlertEnabled() { return durabilityAlertEnabled; }
    public static void setDurabilityAlertEnabled(boolean v) { durabilityAlertEnabled = v; }

    public static boolean isChatAssistantEnabled() { return chatAssistantEnabled; }
    public static void setChatAssistantEnabled(boolean v) { chatAssistantEnabled = v; }

    public static boolean isTacticalMrEnabled() { return tacticalMrEnabled; }
    public static void setTacticalMrEnabled(boolean v) { tacticalMrEnabled = v; }

    public static void syncFromClientConfig(
            boolean tianshu, boolean tacticalRadar, boolean navigation, boolean recipePanel,
            boolean audioRadar, boolean companionCard, boolean durabilityAlert,
            boolean chatAssistant, boolean tacticalMr
    ) {
        tianshuEnabled = tianshu;
        tacticalRadarEnabled = tacticalRadar;
        navigationEnabled = navigation;
        recipePanelEnabled = recipePanel;
        audioRadarEnabled = audioRadar;
        companionCardEnabled = companionCard;
        durabilityAlertEnabled = durabilityAlert;
        chatAssistantEnabled = chatAssistant;
        tacticalMrEnabled = tacticalMr;
    }
}