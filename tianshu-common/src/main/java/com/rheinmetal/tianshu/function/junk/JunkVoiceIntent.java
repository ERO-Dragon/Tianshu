package com.rheinmetal.tianshu.function.junk;

public record JunkVoiceIntent(JunkVoiceAction action, String itemId, String displayName) {
    public static JunkVoiceIntent none() {
        return new JunkVoiceIntent(JunkVoiceAction.NONE, "", "");
    }

    public boolean actionable() {
        return action != JunkVoiceAction.NONE;
    }
}
