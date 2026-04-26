package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.DeathContextData;
import com.rheinmetal.tianshu.snapshot.GameSettingsData;
import com.rheinmetal.tianshu.snapshot.NavigationInfo;

public interface IPlayerStateProvider {
    NavigationInfo getPlayerNavigationInfo();

    GameSettingsData getClientGameSettings();

    DeathContextData getLastDeathContext();
}
