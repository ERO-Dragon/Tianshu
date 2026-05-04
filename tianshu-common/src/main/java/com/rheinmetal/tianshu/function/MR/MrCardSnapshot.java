package com.rheinmetal.tianshu.function.MR;

public final class MrCardSnapshot {

    public float anchorX;
    public float anchorY;
    public float jointX;
    public float jointY;
    public float connectorX;
    public float connectorY;
    public float connectorEdgeRatio;
    public int connectorEdge;
    public boolean connectorOnTopEdge;
    public float connectorDirectionX;
    public float connectorDirectionY;
    public boolean orthogonalHorizontalFirst;
    public float cardX;
    public float cardY;

    public float cardWidth;
    public float cardHeight;
    public float scale;

    public float alpha;
    public float distanceFadeAlpha;
    public float environmentAlphaFactor;

    public float appearProgress;
    public float disappearProgress;

    public boolean isAlive;
    public boolean isHostile;
    public boolean isOcclusionVisible;
    public boolean isFocused;
    public boolean isBackground;
    public boolean hasMainHandItem;

    public String displayName;
    public String entityId;
    public String mainHandItemId;
    public String distanceIconItemId;
    public String attackIconItemId;
    public String armorIconItemId;
    public String entityUuid;

    public float health;
    public float maxHealth;
    public float distance;
    public float attackDamage;
    public float armorValue;
    public double relativeX;
    public double relativeY;
    public double relativeZ;
    public float eyeHeight;

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
    public String focusedDetailText;
    public int focusedDetailVisibleChars;
    public boolean focusedDetailOutputFinished;
    public float focusProgress;
    public boolean focusProgressActive;

    public float contentStartX;
    public float contentStartY;
    public float nameIconX;
    public float nameIconY;
    public float nameTextX;
    public float nameTextY;
    public float statsStartX;
    public float contentNameEndY;
    public float contentBarEndY;
    public float contentStatsY;
    public float distanceIconX;
    public float distanceIconY;
    public float distanceTextX;
    public float attackIconX;
    public float attackIconY;
    public float atkTextX;
    public float armorIconX;
    public float armorIconY;
    public float defTextX;

    public MrCardSnapshot() {}

    public MrCardSnapshot copy() {
        MrCardSnapshot s = new MrCardSnapshot();
        s.anchorX = this.anchorX;
        s.anchorY = this.anchorY;
        s.jointX = this.jointX;
        s.jointY = this.jointY;
        s.connectorX = this.connectorX;
        s.connectorY = this.connectorY;
        s.connectorEdgeRatio = this.connectorEdgeRatio;
        s.connectorEdge = this.connectorEdge;
        s.connectorOnTopEdge = this.connectorOnTopEdge;
        s.connectorDirectionX = this.connectorDirectionX;
        s.connectorDirectionY = this.connectorDirectionY;
        s.orthogonalHorizontalFirst = this.orthogonalHorizontalFirst;
        s.cardX = this.cardX;
        s.cardY = this.cardY;
        s.cardWidth = this.cardWidth;
        s.cardHeight = this.cardHeight;
        s.scale = this.scale;
        s.alpha = this.alpha;
        s.distanceFadeAlpha = this.distanceFadeAlpha;
        s.environmentAlphaFactor = this.environmentAlphaFactor;
        s.appearProgress = this.appearProgress;
        s.disappearProgress = this.disappearProgress;
        s.isAlive = this.isAlive;
        s.isHostile = this.isHostile;
        s.isOcclusionVisible = this.isOcclusionVisible;
        s.isFocused = this.isFocused;
        s.isBackground = this.isBackground;
        s.hasMainHandItem = this.hasMainHandItem;
        s.displayName = this.displayName;
        s.entityId = this.entityId;
        s.mainHandItemId = this.mainHandItemId;
        s.distanceIconItemId = this.distanceIconItemId;
        s.attackIconItemId = this.attackIconItemId;
        s.armorIconItemId = this.armorIconItemId;
        s.entityUuid = this.entityUuid;
        s.health = this.health;
        s.maxHealth = this.maxHealth;
        s.distance = this.distance;
        s.attackDamage = this.attackDamage;
        s.armorValue = this.armorValue;
        s.relativeX = this.relativeX;
        s.relativeY = this.relativeY;
        s.relativeZ = this.relativeZ;
        s.eyeHeight = this.eyeHeight;
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
        s.focusedDetailText = this.focusedDetailText;
        s.focusedDetailVisibleChars = this.focusedDetailVisibleChars;
        s.focusedDetailOutputFinished = this.focusedDetailOutputFinished;
        s.focusProgress = this.focusProgress;
        s.focusProgressActive = this.focusProgressActive;
        s.contentStartX = this.contentStartX;
        s.contentStartY = this.contentStartY;
        s.nameIconX = this.nameIconX;
        s.nameIconY = this.nameIconY;
        s.nameTextX = this.nameTextX;
        s.nameTextY = this.nameTextY;
        s.statsStartX = this.statsStartX;
        s.contentNameEndY = this.contentNameEndY;
        s.contentBarEndY = this.contentBarEndY;
        s.contentStatsY = this.contentStatsY;
        s.distanceIconX = this.distanceIconX;
        s.distanceIconY = this.distanceIconY;
        s.distanceTextX = this.distanceTextX;
        s.attackIconX = this.attackIconX;
        s.attackIconY = this.attackIconY;
        s.atkTextX = this.atkTextX;
        s.armorIconX = this.armorIconX;
        s.armorIconY = this.armorIconY;
        s.defTextX = this.defTextX;
        return s;
    }
}
