package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.PotionEffectData;
import com.rheinmetal.tianshu.snapshot.WorldEnvironmentData;

import java.util.List;

public interface IEnvironmentAwarenessProvider {

    List<PotionEffectData> getActivePotionEffects();

    WorldEnvironmentData getWorldEnvironmentInfo();

}
