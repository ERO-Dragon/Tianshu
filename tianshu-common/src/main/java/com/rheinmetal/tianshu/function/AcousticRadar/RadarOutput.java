package com.rheinmetal.tianshu.function.AcousticRadar;

import java.util.List;

public final class RadarOutput {

    public final List<RadarIndicator> indicators;
    public final long gameTick;

    public RadarOutput(List<RadarIndicator> indicators, long gameTick) {
        this.indicators = indicators;
        this.gameTick = gameTick;
    }

    public List<RadarIndicator> getIndicators() { return indicators; }
    public long getGameTick() { return gameTick; }
}
