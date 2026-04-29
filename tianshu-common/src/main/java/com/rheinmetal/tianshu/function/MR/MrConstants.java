package com.rheinmetal.tianshu.function.MR;

public final class MrConstants {

    private MrConstants() {}

    public static final double MR_RANGE = 32.0;
    public static final int MAX_CARDS = 10;

    public static final float DAMPING_FACTOR = 0.15f;
    public static final float WHIP_KILL_THRESHOLD = 300.0f;
    public static final float RIGID_SEGMENT_LENGTH = 40.0f;

    public static final float DISTANCE_ALPHA_FACTOR = 0.2f;
    public static final double BASE_DISTANCE = 8.0;

    public static final float SOFT_MARGIN_PERCENT = 0.03f;
    public static final float HARD_MARGIN_PERCENT = 0.01f;

    public static final float APPEAR_SPEED = 2.0f;
    public static final float DISAPPEAR_SPEED = 3.0f;

    public static final float CARD_BASE_WIDTH = 120.0f;
    public static final float CARD_BASE_HEIGHT = 50.0f;
    public static final int CUT_CORNER_SIZE = 8;

    public static final int NEON_WIDTH_INNER = 1;
    public static final int NEON_WIDTH_OUTER = 3;

    public static final int STAGGER_MAX_PER_SECOND = 10;
    public static final float STAGGER_DELAY = 0.1f;

    public static final int COLOR_HOSTILE = 0xFF6600;
    public static final int COLOR_NEUTRAL = 0x00FF88;
    public static final int COLOR_BACKGROUND_MASK = 0x99000000;

    public static final float LOS_FOLLOW_GRACE_PERIOD = 1.0f;

    public static final float BACKGROUND_SCALE = 0.75f;
    public static final float FOCUS_SCALE = 1.5f;

    public static final float DEATH_TIME_SECONDS = 1.0f;

    public static final int TICK_INTERVAL = 2;
    public static final float TICK_DURATION = 0.05f;
}
