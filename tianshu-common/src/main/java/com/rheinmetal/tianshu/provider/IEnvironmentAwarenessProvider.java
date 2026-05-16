package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.*;

import java.util.List;

public interface IEnvironmentAwarenessProvider {

    List<NearbyEntityData> getNearbyEntities(double radius);

    List<PotionEffectData> getActivePotionEffects();

    WorldEnvironmentData getWorldEnvironmentInfo();

    List<NearbyEntityData> getNearbyHostiles(double radius);

    float getSkyLightAtPlayer();

    MiningTargetData getCurrentMiningTarget();

    void setActiveScanRadius(double radius);

    String getCrosshairTargetEntityUuid();

    String getCrosshairTargetKey();

    FocusTargetData getFocusTarget(double range);

    default FocusTargetData refreshFocusTarget(FocusTargetData currentTarget, double range) {
        return currentTarget;
    }
}
