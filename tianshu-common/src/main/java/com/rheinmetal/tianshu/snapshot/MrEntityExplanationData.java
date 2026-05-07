package com.rheinmetal.tianshu.snapshot;

import java.util.Collections;
import java.util.List;

public final class MrEntityExplanationData {

    private final String name;
    private final String typeLabel;
    private final boolean invisible;
    private final double movementSpeed;
    private final String movementSpeedLabel;
    private final CombatEstimateData meleeEstimate;
    private final CombatEstimateData rangedEstimate;
    private final List<EffectData> beneficialEffects;
    private final List<EffectData> harmfulEffects;
    private final List<String> possibleDrops;
    private final Double followRange;

    public MrEntityExplanationData(String name, String typeLabel, boolean invisible, double movementSpeed, String movementSpeedLabel, CombatEstimateData meleeEstimate, CombatEstimateData rangedEstimate, List<EffectData> beneficialEffects, List<EffectData> harmfulEffects, List<String> possibleDrops, Double followRange) {
        this.name = name;
        this.typeLabel = typeLabel;
        this.invisible = invisible;
        this.movementSpeed = movementSpeed;
        this.movementSpeedLabel = movementSpeedLabel;
        this.meleeEstimate = meleeEstimate;
        this.rangedEstimate = rangedEstimate;
        this.beneficialEffects = beneficialEffects == null ? Collections.emptyList() : Collections.unmodifiableList(beneficialEffects);
        this.harmfulEffects = harmfulEffects == null ? Collections.emptyList() : Collections.unmodifiableList(harmfulEffects);
        this.possibleDrops = possibleDrops == null ? Collections.emptyList() : Collections.unmodifiableList(possibleDrops);
        this.followRange = followRange;
    }

    public String getName() { return name; }
    public String getTypeLabel() { return typeLabel; }
    public boolean isInvisible() { return invisible; }
    public double getMovementSpeed() { return movementSpeed; }
    public String getMovementSpeedLabel() { return movementSpeedLabel; }
    public CombatEstimateData getMeleeEstimate() { return meleeEstimate; }
    public CombatEstimateData getRangedEstimate() { return rangedEstimate; }
    public List<EffectData> getBeneficialEffects() { return beneficialEffects; }
    public List<EffectData> getHarmfulEffects() { return harmfulEffects; }
    public List<String> getPossibleDrops() { return possibleDrops; }
    public Double getFollowRange() { return followRange; }

    public static final class CombatEstimateData {
        private final String weaponName;
        private final String weaponId;
        private final String mode;
        private final int hitCount;
        private final double fastestSeconds;
        private final double effectiveDamage;
        private final double attackSpeed;

        public CombatEstimateData(String weaponName, String weaponId, String mode, int hitCount, double fastestSeconds, double effectiveDamage, double attackSpeed) {
            this.weaponName = weaponName;
            this.weaponId = weaponId;
            this.mode = mode;
            this.hitCount = hitCount;
            this.fastestSeconds = fastestSeconds;
            this.effectiveDamage = effectiveDamage;
            this.attackSpeed = attackSpeed;
        }

        public String getWeaponName() { return weaponName; }
        public String getWeaponId() { return weaponId; }
        public String getMode() { return mode; }
        public int getHitCount() { return hitCount; }
        public double getFastestSeconds() { return fastestSeconds; }
        public double getEffectiveDamage() { return effectiveDamage; }
        public double getAttackSpeed() { return attackSpeed; }
    }

    public static final class EffectData {
        private final String id;
        private final String displayName;
        private final int amplifier;
        private final int durationSeconds;
        private final boolean beneficial;

        public EffectData(String id, String displayName, int amplifier, int durationSeconds, boolean beneficial) {
            this.id = id;
            this.displayName = displayName;
            this.amplifier = amplifier;
            this.durationSeconds = durationSeconds;
            this.beneficial = beneficial;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }
        public int getAmplifier() { return amplifier; }
        public int getDurationSeconds() { return durationSeconds; }
        public boolean isBeneficial() { return beneficial; }
    }
}
