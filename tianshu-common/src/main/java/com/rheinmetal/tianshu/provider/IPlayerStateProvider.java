package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.PlayerStatusData;

public interface IPlayerStateProvider {

    String getCurrentDimensionDisplayName();

    PlayerStatusData getPlayerStatus();
}
