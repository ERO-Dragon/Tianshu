package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.NearbyEntityData;
import com.rheinmetal.tianshu.snapshot.PotionEffectData;
import com.rheinmetal.tianshu.snapshot.WorldEnvironmentData;

import java.util.List;

public interface IEnvironmentAwarenessProvider {
    List<NearbyEntityData> getNearbyEntities(double radius);

    List<PotionEffectData> getActivePotionEffects();

    WorldEnvironmentData getWorldEnvironmentInfo();
}
