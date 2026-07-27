package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.protocol.Priority;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

final class TtsSpeechSession {
    final TtsSpeechSessionKey key;
    final TtsPlaybackPlacement placement;
    final Priority priority;
    final long admissionSequence;
    final ArrayDeque<String> sentences = new ArrayDeque<>();
    final ArrayDeque<TtsSpeechSessionCoordinator.SentenceWork> reservedWorks = new ArrayDeque<>();
    final Set<TtsSpeechSessionCoordinator.SentenceWork> readyWorks = new HashSet<>();
    final ArrayDeque<TtsSpeechSession> afterSentence = new ArrayDeque<>();
    final ArrayDeque<TtsSpeechSession> afterSession = new ArrayDeque<>();
    boolean ended;
    boolean cancelled;
    boolean firstWorkIssued;

    TtsSpeechSession(TtsSpeechSessionKey key, TtsPlaybackPlacement placement, Priority priority, long admissionSequence) {
        this.key = key;
        this.placement = placement;
        this.priority = priority == null ? Priority.NORMAL : priority;
        this.admissionSequence = admissionSequence;
    }

    boolean resumable() {
        return !cancelled && (!ended || !sentences.isEmpty() || !reservedWorks.isEmpty());
    }
}
