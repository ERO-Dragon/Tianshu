package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.*;

public interface IPlayerStateProvider {

    NavigationInfo getPlayerNavigationInfo();

    GameSettingsData getClientGameSettings();

    DeathContextData getLastDeathContext();

    PlayerStatusData getPlayerStatus();

    PositionData getSpawnPoint();

    long getLastDamageTick();
}
