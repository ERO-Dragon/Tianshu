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

    public String displayName;
    public float health;
    public float maxHealth;
    public float distance;
    public float attackDamage;
    public float armorValue;
    public String mainHandItemId;

    public int accentColor;

    public String entityUuid;

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
        s.displayName = this.displayName;
        s.health = this.health;
        s.maxHealth = this.maxHealth;
        s.distance = this.distance;
        s.attackDamage = this.attackDamage;
        s.armorValue = this.armorValue;
        s.mainHandItemId = this.mainHandItemId;
        s.accentColor = this.accentColor;
        s.entityUuid = this.entityUuid;
        return s;
    }
}
