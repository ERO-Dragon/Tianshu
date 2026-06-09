package com.rheinmetal.tianshu.function.ia.context;

import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;

import java.util.List;

@FunctionalInterface
public interface DialogueContextProvider {
    DialogueContextProvider EMPTY = playerId -> DialogueContextFrame.empty(playerId);

    DialogueContextFrame capture(String playerId);

    default DialogueContextFrame capture(String playerId, List<DialogueParticipantDescriptor> participants) {
        return capture(playerId);
    }

    default void updateParticipants(List<DialogueParticipantDescriptor> participants) {
    }
}
