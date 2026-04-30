package com.rheinmetal.tianshu.function.MR;

public final class MrCardSnapshot {

    public float anchorX;
    public float anchorY;
    public float jointX;
    public float jointY;
    public float cardX;
    public float cardY;

    public float cardWidth;
    public float cardHeight;
    public float scale;

    public float alpha;
    public float distanceFadeAlpha;

    public float appearProgress;
    public float disappearProgress;

    public boolean isAlive;
    public boolean isHostile;
    public boolean isLineOfSight;
    public boolean isFocused;
    public boolean isBackground;
    public boolean shouldKill;
    public boolean isGrayscale;
    public boolean hasMainHandItem;

    public String displayName;
    public String mainHandItemId;
    public String entityUuid;

    public float health;
    public float maxHealth;
    public float distance;
    public float attackDamage;
    public float armorValue;

    public int accentColor;

    public int accentR;
    public int accentG;
    public int accentB;
    public int textAlphaColor;
    public int accentTextColor;
    public int healthBarBgColor;
    public int healthBarColor;
    public float healthBarFillWidth;
    public float healthBarFullWidth;
    public int glitchOffset;

    public String distanceText;
    public String attackText;
    public String armorText;

    public float contentStartX;
    public float contentStartY;
    public float statsStartX;
    public float contentNameEndY;
    public float contentBarEndY;
    public float contentStatsY;
    public float weaponIconX;
    public float weaponIconY;
    public float atkTextX;
    public float defTextX;

    public MrCardSnapshot() {}

    public MrCardSnapshot copy() {
        MrCardSnapshot s = new MrCardSnapshot();
        s.anchorX = this.anchorX;
        s.anchorY = this.anchorY;
        s.jointX = this.jointX;
        s.jointY = this.jointY;
        s.cardX = this.cardX;
        s.cardY = this.cardY;
        s.cardWidth = this.cardWidth;
        s.cardHeight = this.cardHeight;
        s.scale = this.scale;
        s.alpha = this.alpha;
        s.distanceFadeAlpha = this.distanceFadeAlpha;
        s.appearProgress = this.appearProgress;
        s.disappearProgress = this.disappearProgress;
        s.isAlive = this.isAlive;
        s.isHostile = this.isHostile;
        s.isLineOfSight = this.isLineOfSight;
        s.isFocused = this.isFocused;
        s.isBackground = this.isBackground;
        s.shouldKill = this.shouldKill;
        s.isGrayscale = this.isGrayscale;
        s.hasMainHandItem = this.hasMainHandItem;
        s.displayName = this.displayName;
        s.mainHandItemId = this.mainHandItemId;
        s.entityUuid = this.entityUuid;
        s.health = this.health;
        s.maxHealth = this.maxHealth;
        s.distance = this.distance;
        s.attackDamage = this.attackDamage;
        s.armorValue = this.armorValue;
        s.accentColor = this.accentColor;
        s.accentR = this.accentR;
        s.accentG = this.accentG;
        s.accentB = this.accentB;
        s.textAlphaColor = this.textAlphaColor;
        s.accentTextColor = this.accentTextColor;
        s.healthBarBgColor = this.healthBarBgColor;
        s.healthBarColor = this.healthBarColor;
        s.healthBarFillWidth = this.healthBarFillWidth;
        s.healthBarFullWidth = this.healthBarFullWidth;
        s.glitchOffset = this.glitchOffset;
        s.distanceText = this.distanceText;
        s.attackText = this.attackText;
        s.armorText = this.armorText;
        s.contentStartX = this.contentStartX;
        s.contentStartY = this.contentStartY;
        s.statsStartX = this.statsStartX;
        s.contentNameEndY = this.contentNameEndY;
        s.contentBarEndY = this.contentBarEndY;
        s.contentStatsY = this.contentStatsY;
        s.weaponIconX = this.weaponIconX;
        s.weaponIconY = this.weaponIconY;
        s.atkTextX = this.atkTextX;
        s.defTextX = this.defTextX;
        return s;
    }
}
