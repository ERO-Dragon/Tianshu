package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.client.host.DiagnosticChatCatalogue;
import com.rheinmetal.tianshu.client.host.DiagnosticChatNotice;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The session's chat-notice table: modules register their codes at assembly, the router reads it per event. */
public final class ClientDiagnosticChatCatalogue implements DiagnosticChatCatalogue {
    private final Map<String, DiagnosticChatNotice> notices = new ConcurrentHashMap<>();

    @Override
    public void register(String code, DiagnosticChatNotice notice) {
        if (code == null || code.isBlank() || notice == null) {
            return;
        }
        notices.put(code.trim(), notice);
    }

    @Override
    public Optional<DiagnosticChatNotice> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(notices.get(code.trim()));
    }
}
