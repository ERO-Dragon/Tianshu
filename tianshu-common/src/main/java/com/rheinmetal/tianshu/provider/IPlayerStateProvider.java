package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.*;

public interface IPlayerStateProvider {

    NavigationInfo getPlayerNavigationInfo();

    default String getCurrentDimensionDisplayName() {
        NavigationInfo navigation = getPlayerNavigationInfo();
        PositionData position = navigation == null ? null : navigation.getCurrent();
        return position == null ? "" : position.getDimension();
    }

    GameSettingsData getClientGameSettings();

    DeathContextData getLastDeathContext();

    PlayerStatusData getPlayerStatus();

    PositionData getSpawnPoint();

    long getLastDamageTick();

    float getCurrentDynamicFov();
}
