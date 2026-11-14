package com.rheinmetal.tianshu.client.host;

import java.util.Optional;

/**
 * Where a module teaches the host to speak its diagnostic codes.
 *
 * <p>The chat line for "recognition finished" or "answer ready" used to live in the kernel as a switch over
 * feature codes, so the kernel knew every module's vocabulary. With this catalogue the module registers its own
 * code and wording while it is assembled; the host only looks a code up and falls back to a generic "failed"
 * line when nothing matches.</p>
 */
public interface DiagnosticChatCatalogue {

    /** Registers (or replaces) the line for {@code code}; blank codes and null notices are ignored. */
    void register(String code, DiagnosticChatNotice notice);

    /** The line for {@code code}, if the module that produces it is part of this session. */
    Optional<DiagnosticChatNotice> find(String code);
}
