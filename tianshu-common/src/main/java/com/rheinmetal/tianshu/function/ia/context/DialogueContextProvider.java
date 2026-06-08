package com.rheinmetal.tianshu.function.ia.context;

@FunctionalInterface
public interface DialogueContextProvider {
    DialogueContextProvider EMPTY = playerId -> DialogueContextFrame.empty(playerId);

    DialogueContextFrame capture(String playerId);
}
