package com.rheinmetal.tianshu.snapshot;

public final class PotionEffectData {

    /**
     * 期望填入经过 Minecraft 本地化处理后的显示名称（如通过 effect.getDescriptionId() 对应的翻译获取）。
     * 严禁填入未经翻译的注册表 ID（如 "minecraft.speed"），必须是对应语言的文本（如 "速度"），
     * 以防止 2B 小模型因上下文充斥英文而产生语言混乱。
     */
    public final String displayName;

    public final int durationTicks;
    public final int amplifier;
    public final boolean beneficial;

    public PotionEffectData(
            String displayName,
            int durationTicks,
            int amplifier,
            boolean beneficial
    ) {
        this.displayName = displayName;
        this.durationTicks = durationTicks;
        this.amplifier = amplifier;
        this.beneficial = beneficial;
    }

    public String getDisplayName() { return displayName; }
    public int getDurationTicks() { return durationTicks; }
    public int getAmplifier() { return amplifier; }
    public boolean isBeneficial() { return beneficial; }
}
