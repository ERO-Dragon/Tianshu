package com.rheinmetal.tianshu.function.ir.core;

record CommandParserPolicy(
        double primaryWeight,
        double fallbackWeight,
        double fastIrInterceptHighRatio,
        double fastIrInterceptHighThreshold,
        double fastIrInterceptMidRatioLow,
        double fastIrInterceptMidThreshold,
        double finalIrFixedThreshold,
        double healPinyinOverlapThreshold,
        int healMaxCandidates
) {
    static final CommandParserPolicy DEFAULT = new CommandParserPolicy(
            1.0D,
            0.85D,
            0.5D,
            0.40D,
            0.2D,
            0.25D,
            0.50D,
            0.4D,
            5
    );

    CommandParserPolicy {
        primaryWeight = positive(primaryWeight, DEFAULT_VALUE_PRIMARY_WEIGHT);
        fallbackWeight = positive(fallbackWeight, DEFAULT_VALUE_FALLBACK_WEIGHT);
        fastIrInterceptHighRatio = nonNegative(fastIrInterceptHighRatio, DEFAULT_VALUE_FASTIR_INTERCEPT_HIGH_RATIO);
        fastIrInterceptHighThreshold = nonNegative(fastIrInterceptHighThreshold, DEFAULT_VALUE_FASTIR_INTERCEPT_HIGH_THRESHOLD);
        fastIrInterceptMidRatioLow = nonNegative(fastIrInterceptMidRatioLow, DEFAULT_VALUE_FASTIR_INTERCEPT_MID_RATIO_LOW);
        fastIrInterceptMidThreshold = nonNegative(fastIrInterceptMidThreshold, DEFAULT_VALUE_FASTIR_INTERCEPT_MID_THRESHOLD);
        finalIrFixedThreshold = nonNegative(finalIrFixedThreshold, DEFAULT_VALUE_FINALIR_FIXED_THRESHOLD);
        healPinyinOverlapThreshold = nonNegative(healPinyinOverlapThreshold, DEFAULT_VALUE_HEAL_PINYIN_OVERLAP_THRESHOLD);
        healMaxCandidates = healMaxCandidates <= 0 ? DEFAULT_VALUE_HEAL_MAX_CANDIDATES : healMaxCandidates;
    }

    private static final double DEFAULT_VALUE_PRIMARY_WEIGHT = 1.0D;
    private static final double DEFAULT_VALUE_FALLBACK_WEIGHT = 0.85D;
    private static final double DEFAULT_VALUE_FASTIR_INTERCEPT_HIGH_RATIO = 0.5D;
    private static final double DEFAULT_VALUE_FASTIR_INTERCEPT_HIGH_THRESHOLD = 0.40D;
    private static final double DEFAULT_VALUE_FASTIR_INTERCEPT_MID_RATIO_LOW = 0.2D;
    private static final double DEFAULT_VALUE_FASTIR_INTERCEPT_MID_THRESHOLD = 0.25D;
    private static final double DEFAULT_VALUE_FINALIR_FIXED_THRESHOLD = 0.50D;
    private static final double DEFAULT_VALUE_HEAL_PINYIN_OVERLAP_THRESHOLD = 0.4D;
    private static final int DEFAULT_VALUE_HEAL_MAX_CANDIDATES = 5;

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }

    private static double nonNegative(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0D ? value : fallback;
    }
}
