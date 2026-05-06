package com.rheinmetal.tianshu.function.MR;

public class MrAnimationController {

    public static final int STATE_APPEARING = 0;
    public static final int STATE_VISIBLE = 1;
    public static final int STATE_DISAPPEARING = 2;
    public static final int STATE_DEAD = 3;
    public static final int STATE_RECOVER_AFTER_BOX_COLLAPSE = 4;

    private int state = STATE_APPEARING;
    private float appearProgress = 0.0f;
    private float disappearProgress = 1.0f;

    private float staggerTimer = 0.0f;
    private boolean staggerReady = false;

    public void setStaggerDelay(float delay) {
        this.staggerTimer = delay;
        this.staggerReady = false;
    }

    public void restartAppear() {
        state = STATE_APPEARING;
        appearProgress = 0.0f;
        disappearProgress = 1.0f;
        staggerTimer = 0.0f;
        staggerReady = true;
    }

    public void triggerDisappear() {
        if (state == STATE_DEAD || state == STATE_DISAPPEARING) return;
        state = STATE_DISAPPEARING;
        disappearProgress = 1.0f;
    }

    public void recoverAppear() {
        if (state == STATE_DISAPPEARING) {
            if (disappearProgress > 0.5f) {
                state = STATE_RECOVER_AFTER_BOX_COLLAPSE;
            } else {
                state = STATE_APPEARING;
                appearProgress = Math.max(0.0f, Math.min(0.5f, disappearProgress));
                disappearProgress = 1.0f;
            }
            staggerReady = true;
            staggerTimer = 0.0f;
        } else if (state == STATE_RECOVER_AFTER_BOX_COLLAPSE) {
            state = STATE_APPEARING;
            appearProgress = 0.5f;
            disappearProgress = 1.0f;
            staggerReady = true;
            staggerTimer = 0.0f;
        }
    }

    public void triggerInstantKill() {
        state = STATE_DEAD;
        appearProgress = 0.0f;
        disappearProgress = 0.0f;
    }

    public boolean isFullyDead() {
        return state == STATE_DEAD;
    }

    public boolean isVisible() {
        return state == STATE_VISIBLE || state == STATE_APPEARING || state == STATE_RECOVER_AFTER_BOX_COLLAPSE;
    }

    public float getAppearProgress() {
        return appearProgress;
    }

    public float getDisappearProgress() {
        return disappearProgress;
    }

    public void tick(float deltaTime) {
        if (!staggerReady) {
            staggerTimer -= deltaTime;
            if (staggerTimer <= 0.0f) {
                staggerReady = true;
            }
            return;
        }

        switch (state) {
            case STATE_APPEARING -> {
                appearProgress += MrConstants.APPEAR_SPEED * deltaTime;
                if (appearProgress > 1.0f) {
                    appearProgress = 1.0f;
                    state = STATE_VISIBLE;
                }
            }
            case STATE_DISAPPEARING -> {
                disappearProgress -= MrConstants.DISAPPEAR_SPEED * deltaTime;
                if (disappearProgress <= 0.0f) {
                    disappearProgress = 0.0f;
                    state = STATE_DEAD;
                }
            }
            case STATE_RECOVER_AFTER_BOX_COLLAPSE -> {
                disappearProgress -= MrConstants.DISAPPEAR_SPEED * deltaTime;
                if (disappearProgress <= 0.5f) {
                    state = STATE_APPEARING;
                    appearProgress = 0.5f;
                    disappearProgress = 1.0f;
                }
            }
        }
    }

    public float getAnimationAlpha() {
        return switch (state) {
            case STATE_APPEARING -> Math.min(1.0f, appearProgress / 0.5f);
            case STATE_VISIBLE -> 1.0f;
            case STATE_DISAPPEARING, STATE_RECOVER_AFTER_BOX_COLLAPSE -> disappearProgress;
            default -> 0.0f;
        };
    }
}
