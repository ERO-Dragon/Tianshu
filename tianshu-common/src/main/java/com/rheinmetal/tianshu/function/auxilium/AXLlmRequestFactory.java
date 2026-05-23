package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.function.llm.inference.LlmMessageRole;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskUsageKind;
import com.rheinmetal.tianshu.protocol.payload.LlmUsageAuthorizationPayload;

import java.util.List;
import java.util.Objects;

public final class AXLlmRequestFactory {
    private static final String PURPOSE = "AX.dialogue";

    public LlmTaskRequestPayload create(AXInvocationPlan plan, DialogueDeliveryPayload delivery) {
        Objects.requireNonNull(plan, "plan");
        DialogueDeliveryPayload effectiveDelivery = Objects.requireNonNull(delivery, "delivery");
        List<LlmTaskMessagePayload> messages = plan.invocationRequest().messages().stream()
                .map(this::toPayload)
                .toList();
        List<String> dynamicFacts = plan.invocationRequest().ragContext().dynamicRag().stream()
                .map(entry -> entry.text())
                .filter(text -> text != null && !text.isBlank())
                .toList();
        return new LlmTaskRequestPayload(
                plan.invocationRequest().requestKey(),
                PURPOSE,
                LlmTaskUsageKind.INTERACTIVE,
                messages,
                dynamicFacts,
                plan.invocationRequest().options().taskPriority(),
                plan.invocationRequest().options().taskPreemptible(),
                plan.invocationRequest().options().stream(),
                plan.invocationRequest().options().thinking(),
                plan.invocationRequest().options().useRag(),
                plan.invocationRequest().options().maxTokens(),
                plan.invocationRequest().options().temperature(),
                effectiveDelivery.expireAtMillis(),
                AXModule.MODULE_ID,
                AXParticipantRegistrar.PARTICIPANT_ID,
                "world",
                List.of(),
                new LlmUsageAuthorizationPayload(
                        effectiveDelivery.sessionId(),
                        effectiveDelivery.turnId()
                )
        );
    }

    private LlmTaskMessagePayload toPayload(LlmInvocationMessage message) {
        LlmInvocationMessage normalized = message == null ? LlmInvocationMessage.user("") : message;
        return new LlmTaskMessagePayload(roleName(normalized.role()), normalized.content());
    }

    private String roleName(LlmMessageRole role) {
        return LlmMessageRole.normalize(role).wireName();
    }
}
