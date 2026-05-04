package com.rheinmetal.tianshu.function.MR;

public interface MrTuningProvider {

    float getMinCardScale();

    float getMaxCardScale();

    float getSegmentLength();

    float getCardDamping();

    float getCardMinDamping();

    float getCardMaxDamping();

    float getDayAlphaFactor();

    float getNightAlphaFactor();

    static MrTuningProvider defaults() {
        return DefaultMrTuningProvider.INSTANCE;
    }

    final class DefaultMrTuningProvider implements MrTuningProvider {
        private static final DefaultMrTuningProvider INSTANCE = new DefaultMrTuningProvider();

        private DefaultMrTuningProvider() {}

        @Override
        public float getMinCardScale() {
            return MrConstants.CARD_MIN_SCALE;
        }

        @Override
        public float getMaxCardScale() {
            return MrConstants.CARD_MAX_SCALE;
        }

        @Override
        public float getSegmentLength() {
            return MrConstants.RIGID_SEGMENT_LENGTH;
        }

        @Override
        public float getCardDamping() {
            return MrConstants.DAMPING_FACTOR;
        }

        @Override
        public float getCardMinDamping() {
            return MrConstants.DAMPING_FACTOR;
        }

        @Override
        public float getCardMaxDamping() {
            return MrConstants.DAMPING_MAX_FACTOR;
        }

        @Override
        public float getDayAlphaFactor() {
            return MrConstants.DAY_ALPHA_FACTOR;
        }

        @Override
        public float getNightAlphaFactor() {
            return MrConstants.NIGHT_ALPHA_FACTOR;
        }
    }
}
