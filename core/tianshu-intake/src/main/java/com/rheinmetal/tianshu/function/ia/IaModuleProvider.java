package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;

import com.rheinmetal.tianshu.client.host.DiagnosticChatNotice;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerConfig;
import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerSettings;

import java.util.List;

/**
 * Speech (Intake) announces the arbitration brain to the kernel.
 *
 * <p>Arbitration exists because speech has no addressing: several participants hear the same utterance and
 * the system has to decide who it was meant for. It therefore ships with the speech module instead of the
 * kernel — with no speech module there is nothing to arbitrate, and the kernel keeps no reference to it.</p>
 *
 * <p>The default-owner setting is read from this module's own config store, the same store the settings page
 * writes to. Both sides wrap the same keys, so the page and the arbiter cannot drift apart.</p>
 */
public final class IaModuleProvider implements TianshuModuleProvider {

    private final TianshuModuleAssemblyContext context;

    public IaModuleProvider(TianshuModuleAssemblyContext context) {
        this.context = context;
    }

    @Override
    public void contribute(TianshuModuleRegistrar registrar) {
        if (context == null || context.moduleRuntime() == null) {
            return;
        }
        IaDefaultOwnerSettings settings = IaDefaultOwnerSettings.UNCONFIGURED;
        if (context.hasClientPorts()) {
            settings = new IaDefaultOwnerConfig(context.clientPorts().configStores().forModule(IaProtocolAdapter.MODULE_ID));
            // The arbitration wording belongs to this module; the host only looks the codes up.
            context.clientPorts().diagnosticChat().register("ARBITRATION_ACCEPTED",
                    new DiagnosticChatNotice("tianshu.chat.progress.ia.arbitration.accepted",
                            List.of(List.of("normalizedText", "repairedText"), List.of("owner"),
                                    List.of("claims"), List.of("reason"))));
            context.clientPorts().diagnosticChat().register("ARBITRATION_REJECTED",
                    new DiagnosticChatNotice("tianshu.chat.progress.ia.arbitration.rejected",
                            List.of(List.of("normalizedText", "repairedText"), List.of("claims"),
                                    List.of("reason"))));
            // Every session transition goes through the one publisher, so one line per transition covers
            // start, claim, owner change, completion, rejection and expiry alike.
            context.clientPorts().diagnosticChat().register("DIALOGUE_SESSION_EVENT",
                    new DiagnosticChatNotice("tianshu.chat.progress.ia.session",
                            List.of(List.of("event"), List.of("state"), List.of("owner"),
                                    List.of("releaseReason", "reason"))));
            context.clientPorts().diagnosticChat().register("DIALOGUE_DELIVERED",
                    new DiagnosticChatNotice("tianshu.chat.progress.ia.delivery",
                            List.of(List.of("owner"), List.of("sessionId"), List.of("turnId"))));
            // Registration outcomes are part of the bench's contract: "the fake participant did not appear"
            // has to be one readable line, not a silent NO_PARTICIPANT ten seconds later.
            context.clientPorts().diagnosticChat().register("PARTICIPANT_REGISTERED",
                    new DiagnosticChatNotice("tianshu.chat.progress.ia.participant.registered",
                            List.of(List.of("module"), List.of("participant"), List.of("priority"))));
            context.clientPorts().diagnosticChat().register("PARTICIPANT_REGISTER_REJECTED",
                    new DiagnosticChatNotice("tianshu.chat.progress.ia.participant.rejected",
                            List.of(List.of("module"), List.of("participant"), List.of("code"))));
            context.clientPorts().diagnosticChat().register("PARTICIPANT_UNREGISTERED",
                    new DiagnosticChatNotice("tianshu.chat.progress.ia.participant.unregistered",
                            List.of(List.of("module"), List.of("participant"))));
            // What the arbiter last told presence: a marker that does not change in game can be compared
            // against this line before blaming the renderer.
            context.clientPorts().diagnosticChat().register("PARTNER_MARKER",
                    new DiagnosticChatNotice("tianshu.chat.progress.ia.marker",
                            List.of(List.of("module"), List.of("participant"), List.of("kind"))));
        }
        registrar.addModule(new IaModuleInstaller(context.moduleRuntime(), settings));
    }
}