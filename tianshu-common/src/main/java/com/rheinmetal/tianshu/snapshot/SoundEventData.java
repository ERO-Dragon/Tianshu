package com.rheinmetal.tianshu.snapshot;

public final class SoundEventData {

    public final String soundEventId;
    public final double sourceX;
    public final double sourceY;
    public final double sourceZ;
    public final double relativeAngle;
    public final double distance;
    public final long gameTick;

    public SoundEventData(
            String soundEventId,
            double sourceX,
            double sourceY,
            double sourceZ,
            double relativeAngle,
            double distance,
            long gameTick
    ) {
        this.soundEventId = soundEventId;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceZ = sourceZ;
        this.relativeAngle = relativeAngle;
        this.distance = distance;
        this.gameTick = gameTick;
    }

    public String getSoundEventId() { return soundEventId; }
    public double getSourceX() { return sourceX; }
    public double getSourceY() { return sourceY; }
    public double getSourceZ() { return sourceZ; }
    public double getRelativeAngle() { return relativeAngle; }
    public double getDistance() { return distance; }
    public long getGameTick() { return gameTick; }
}
