package com.rheinmetal.tianshu.function.MR;

public final class MrConstants {

    private MrConstants() {}

    public static final double MR_RANGE = 32.0;
    public static final int MAX_CARDS = 6;

    public static final float DAMPING_FACTOR = 0.15f;
    public static final float DAMPING_DISTANCE_REFERENCE = 200.0f;
    public static final float DAMPING_MAX_FACTOR = 0.75f;
    public static final float RIGID_SEGMENT_LENGTH = 40.0f;
    public static final float CARD_MIN_SCALE = 0.4f;
    public static final float CARD_MAX_SCALE = 1.5f;
    public static final float DAY_ALPHA_FACTOR = 1.0f;
    public static final float NIGHT_ALPHA_FACTOR = 0.55f;
    public static final float BC_REST_ANGLE_DEGREES = 40.0f;
    public static final float CONNECTOR_EDGE_MIN_RATIO = 0.2f;
    public static final float CONNECTOR_EDGE_MAX_RATIO = 0.8f;

    public static final float BASE_ALPHA = 0.8f;
    public static final float MIN_DISTANCE_ALPHA = 0.5f;
    public static final float DISTANCE_ALPHA_FACTOR = 0.5f;
    public static final double BASE_DISTANCE = 8.0;

    public static final float APPEAR_SPEED = 1.0f;
    public static final float DISAPPEAR_SPEED = 1.0f;

    public static final float CARD_BASE_WIDTH_RATIO = 0.0625f;
    public static final float CARD_BASE_HEIGHT_RATIO = 0.046f;
    public static final float CARD_MIN_BASE_WIDTH = 96.0f;
    public static final float CARD_MIN_BASE_HEIGHT = 40.0f;
    public static final float CARD_MAX_BASE_WIDTH = 160.0f;
    public static final float CARD_MAX_BASE_HEIGHT = 72.0f;
    public static final float CARD_MAX_FOCUSED_WIDTH_RATIO = 0.25f;
    public static final float CARD_MAX_FOCUSED_AREA_RATIO = 0.08f;
    public static final float CUT_CORNER_HEIGHT_RATIO = 0.18f;
    public static final float CUT_CORNER_MIN_SIZE = 6.0f;
    public static final float CUT_CORNER_MAX_SIZE = 18.0f;

    public static final float NEON_OUTER_WIDTH_HEIGHT_RATIO = 0.05f;
    public static final float NEON_OUTER_WIDTH_MIN = 2.0f;
    public static final float NEON_OUTER_WIDTH_MAX = 5.0f;
    public static final float NEON_INNER_WIDTH_HEIGHT_RATIO = 0.02f;
    public static final float NEON_INNER_WIDTH_MIN = 1.0f;
    public static final float NEON_INNER_WIDTH_MAX = 2.0f;
    public static final float ORIGIN_MARKER_OUTER_RADIUS_HEIGHT_RATIO = 0.075f;
    public static final float ORIGIN_MARKER_OUTER_RADIUS_MIN = 3.0f;
    public static final float ORIGIN_MARKER_OUTER_RADIUS_MAX = 7.0f;
    public static final float ORIGIN_MARKER_INNER_RADIUS_HEIGHT_RATIO = 0.05f;
    public static final float ORIGIN_MARKER_INNER_RADIUS_MIN = 2.0f;
    public static final float ORIGIN_MARKER_INNER_RADIUS_MAX = 5.0f;

    public static final int STAGGER_MAX_PER_SECOND = 10;
    public static final float STAGGER_DELAY = 0.1f;

    public static final int COLOR_HOSTILE = 0xFF5533;
    public static final int COLOR_NEUTRAL = 0x33AAFF;

    public static final float BACKGROUND_SCALE = 0.75f;
    public static final float FOCUS_SCALE = 2.0f;
    public static final float UI_TRANSITION_SPEED = 6.0f;
    public static final float BACKGROUND_ALPHA_FACTOR = 0.4f;

    public static final int TICK_INTERVAL = 2;
    public static final float TICK_DURATION = 0.05f;

    public static final float FOCUS_DELAY_SECONDS = 2.0f;
    public static final float FOCUS_AIM_WARMUP_SECONDS = 1.0f;
    public static final float FOCUS_EXIT_COUNTDOWN_SECONDS = 2.0f;
    public static final float FOCUS_TEXT_CHARS_PER_SECOND = 32.0f;
    public static final float APPEAR_ANIM_DURATION = 1.0f;
    public static final float SCANNING_WARMUP = 0.0f;
    public static final float GAZE_FOCUS_DURATION = FOCUS_DELAY_SECONDS;
    public static final int FONT_LINE_HEIGHT = 9;
    public static final float CONTENT_PADDING_X = 4.0f;
    public static final float CONTENT_PADDING_Y = 3.0f;
    public static final float CONTENT_BAR_HEIGHT = 4.0f;
    public static final float CONTENT_BAR_SPACING = 7.0f;
    public static final float CONTENT_BAR_MARGIN = 8.0f;
    public static final float STATS_ICON_SIZE = 12.0f;
    public static final float STATS_ICON_TEXT_GAP = 3.0f;
    public static final float STATS_GROUP_GAP = 10.0f;
}
