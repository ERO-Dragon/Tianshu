package com.rheinmetal.tianshu.function.auxilium.core.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityExposure;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.api.tool.ToolCall;
import com.rheinmetal.tianshu.api.tool.ToolCallParser;
import com.rheinmetal.tianshu.api.tool.ToolCatalogText;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload.MessageItemPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One conversation turn with tools, as a pure state machine.
 *
 * <p>It owns the part the loop sees as "still going": append the catalogue once, and after a reply that is a tool
 * call, ask the model again with the call and its result in the conversation. The orchestrator keeps the transport,
 * the streaming and the output; this class only decides what the next request looks like, which makes the protocol
 * testable without a game or a provider.</p>
 *
 * <p>The class is single-use: create it per turn.</p>
 */
public final class AXToolTurn {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 3;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ToolCaller caller;
    private final ToolExecutor executor;
    private final String catalogue;
    private final List<MessageItemPayload> conversation;
    private final int maxToolRounds;
    private int toolRounds;

    private AXToolTurn(ToolCaller caller, ToolExecutor executor, String catalogue,
                       List<MessageItemPayload> conversation, int maxToolRounds) {
        this.caller = caller;
        this.executor = executor;
        this.catalogue = catalogue;
        this.conversation = new ArrayList<>(conversation);
        this.maxToolRounds = maxToolRounds;
    }

    /**
     * Starts a turn from the prompt the caller already assembled plus the registry catalogue.
     *
     * @param executor runs one call; production wraps {@link AXToolExecutor}, tests inject a stub
     */
    public static AXToolTurn start(List<ToolSpec> catalogue, LLMPromptRequestPayload base, ToolExecutor executor) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(executor, "executor");
        List<MessageItemPayload> messages = base.messages();
        boolean hasTools = catalogue != null && catalogue.stream().anyMatch(ToolSpec::enabled);
        ToolCaller caller = ToolCaller.agent(false);
        String readable = CapabilityExposure.describe(CapabilityExposure.exposed(caller, ToolSide.CLIENT).stream()
                .filter(descriptor -> !descriptor.kind().isTool())
                .toList());
        List<MessageItemPayload> conversation = new ArrayList<>();
        StringBuilder system = new StringBuilder();
        if (hasTools) {
            system.append(ToolCatalogText.describe(catalogue));
        }
        if (!readable.isBlank()) {
            if (!system.isEmpty()) {
                system.append("\n\n");
            }
            system.append("Readable skills (call tianshu:capability.read with an id):\n").append(readable);
        }
        if (!system.isEmpty()) {
            conversation.add(new MessageItemPayload("system", system.toString()));
        }
        conversation.addAll(messages);
        return new AXToolTurn(ToolCaller.agent(false), executor,
                hasTools ? ToolCatalogText.describe(catalogue) : "", conversation, DEFAULT_MAX_TOOL_ROUNDS);
    }

    /** The request payload for the next model call. */
    public LLMPromptRequestPayload payload(String requestId, boolean stream, Integer maxTokens, Integer reasoningTier,
                                           LLMPromptRequestPayload.SamplingPayload sampling) {
        return new LLMPromptRequestPayload(requestId, List.copyOf(conversation), stream, false, maxTokens,
                reasoningTier, sampling);
    }

    public boolean toolsAvailable() {
        return !catalogue.isEmpty();
    }

    public int toolRounds() {
        return toolRounds;
    }

    /**
     * Interprets one assistant reply.
     *
     * <p>{@link Decision.Finish} carries the reply verbatim: the caller decides what the player hears.
     * {@link Decision.Continue} carries the next payload, already containing the call and its result.</p>
     */
    public Decision onReply(String assistantText, String requestId, boolean stream, Integer maxTokens,
                            Integer reasoningTier, LLMPromptRequestPayload.SamplingPayload sampling) {
        if (!toolsAvailable() || toolRounds >= maxToolRounds) {
            return new Decision.Finish(assistantText);
        }
        Optional<ToolCall> call = ToolCallParser.parse(assistantText);
        if (call.isEmpty()) {
            return new Decision.Finish(assistantText);
        }
        toolRounds++;
        JsonObject result = executor.execute(call.get());
        conversation.add(new MessageItemPayload("assistant", assistantText));
        conversation.add(new MessageItemPayload("user",
                "tool result for " + call.get().toolId() + ": " + GSON.toJson(result)));
        return new Decision.Continue(call.get(), result,
                payload(requestId, stream, maxTokens, reasoningTier, sampling));
    }

    /** Runs one parsed call; production is {@link AXToolExecutor}. */
    @FunctionalInterface
    public interface ToolExecutor {
        JsonObject execute(ToolCall call);
    }

    /** What the orchestrator should do with one assistant reply. */
    public sealed interface Decision {
        record Finish(String text) implements Decision {
        }

        record Continue(ToolCall call, JsonObject result, LLMPromptRequestPayload next) implements Decision {
        }
    }
}