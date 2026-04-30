package com.rheinmetal.tianshu.function.MR;

public class MrAnimationController {

    public static final int STATE_APPEARING = 0;
    public static final int STATE_VISIBLE = 1;
    public static final int STATE_DISAPPEARING = 2;
    public static final int STATE_DEAD = 3;

    private int state = STATE_APPEARING;
    private float appearProgress = 0.0f;
    private float disappearProgress = 1.0f;

    private float staggerTimer = 0.0f;
    private boolean staggerReady = false;

    private float deathTimer = 0.0f;
    private boolean deathTriggered = false;

    public void setStaggerDelay(float delay) {
        this.staggerTimer = delay;
        this.staggerReady = false;
    }

    public void triggerDisappear() {
        if (state == STATE_DEAD || state == STATE_DISAPPEARING) return;
        state = STATE_DISAPPEARING;
        disappearProgress = 1.0f;
    }

    public void triggerInstantKill() {
        state = STATE_DEAD;
        appearProgress = 0.0f;
        disappearProgress = 0.0f;
    }

    public void triggerDeath() {
        if (!deathTriggered) {
            deathTriggered = true;
            deathTimer = 0.0f;
        }
    }

    public boolean isDeathTriggered() {
        return deathTriggered;
    }

    public boolean isFullyDead() {
        return state == STATE_DEAD;
    }

    public boolean isVisible() {
        return state == STATE_VISIBLE || state == STATE_APPEARING;
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

        if (deathTriggered && state != STATE_DISAPPEARING && state != STATE_DEAD) {
            deathTimer += deltaTime;
            if (deathTimer >= MrConstants.DEATH_TIME_SECONDS) {
                triggerDisappear();
            }
        }

        switch (state) {
            case STATE_APPEARING -> {
                appearProgress += MrConstants.APPEAR_SPEED * deltaTime;
                if (appearProgress > 1.2f) {
                    appearProgress = 1.2f;
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
        }
    }

    public float getAnimationAlpha() {
        return switch (state) {
            case STATE_APPEARING -> Math.min(1.0f, appearProgress / 0.5f);
            case STATE_VISIBLE -> 1.0f;
            case STATE_DISAPPEARING -> disappearProgress;
            default -> 0.0f;
        };
    }
}
