package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.core.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.llm.engine.LlmEngine;
import com.rheinmetal.tianshu.function.llm.engine.LlmEngine.ChatMessage;
import com.rheinmetal.tianshu.function.llm.server.LlmServerProcessManager;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LlmCommandRepairPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmCommandRepairResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmIntentClassifyPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmIntentClassifyResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmPromptPayload;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmModule implements TianshuManagedModule {
    private static final Pattern COMMAND_LIKE_PATTERN = Pattern.compile("\\\"commandLike\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMAND_PROBABILITY_PATTERN = Pattern.compile("\\\"commandProbability\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUESSED_INTENT_PATTERN = Pattern.compile("\\\"guessedIntentType\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern REASON_PATTERN = Pattern.compile("\\\"reason\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPAIRED_TEXT_PATTERN = Pattern.compile("\\\"repairedText\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"", Pattern.CASE_INSENSITIVE);

    private final LlmEngine llmEngine;
    private final LlmProtocolAdapter adapter;

    public LlmModule(LlmEngine llmEngine, ProtocolRuntime runtime) {
        this.llmEngine = llmEngine;
        this.adapter = new LlmProtocolAdapter(runtime);
    }

    @Override
    public String moduleId() {
        return "module.llm";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        adapter.registerChatCapability(this::handleNaturalLanguageRequest);
        adapter.registerFeedbackCapability(this::handleNaturalLanguageRequest);
        adapter.registerIntentClassifyCapability(this::handleIntentClassify);
        adapter.registerCommandRepairCapability(this::handleCommandRepair);
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        context.services().require(LlmServerProcessManager.class).startLlmServer();
    }

    @Override
    public void stop() {
    }

    private void handleNaturalLanguageRequest(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LlmPromptPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM payload is invalid", null);
            return;
        }
        LlmSentenceSegmenter segmenter = new LlmSentenceSegmenter();
        AtomicInteger index = new AtomicInteger();
        long requestId = llmEngine.beginStreamRequest(error -> context.fail(envelope.envelopeId(), "LLM_FAILED", error, null));
        if (requestId <= 0L) {
            return;
        }
        adapter.submitLlmIoTask(envelope.envelopeId(), () -> llmEngine.streamChatBlocking(
                requestId,
                buildChatMessages(payload),
                0.6D,
                true,
                false,
                chunk -> publishSegment(envelope, segmenter.accept(chunk), index),
                finishReason -> {
                    publishSegment(envelope, segmenter.finish(), index);
                    adapter.publishStreamEnd(envelope, index.get());
                    context.complete(envelope.envelopeId());
                },
                error -> context.fail(envelope.envelopeId(), "LLM_FAILED", error, null)
        ));
    }

    private List<ChatMessage> buildChatMessages(LlmPromptPayload payload) {
        String latestUserMessage = payload.text().trim();
        String context = payload.context().trim();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", defaultNpcPersona()));
        messages.add(new ChatMessage("system", context.isEmpty() ? "当前世界信息：暂无额外上下文。" : "当前世界信息：\n" + context));
        messages.add(new ChatMessage("system", "当前 NPC 状态：待接入。"));
        messages.add(new ChatMessage("user", "玩家历史信息：待接入。"));
        messages.add(new ChatMessage("assistant", ""));
        messages.add(new ChatMessage("user", latestUserMessage));
        return messages;
    }

    private String defaultNpcPersona() {
        return "NPC 人设：你是天枢 Minecraft 模组中的随行 NPC 助手。保持沉浸感，回答自然、简洁，并优先结合当前游戏上下文。";
    }

    private void handleIntentClassify(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LlmIntentClassifyPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM intent classify payload is invalid", null);
            return;
        }
        collectChat(
                buildIntentClassifyPrompt(payload),
                result -> {
                    adapter.publishIntentClassifyResult(envelope, parseIntentClassifyResult(payload, result));
                    context.complete(envelope.envelopeId());
                },
                error -> context.fail(envelope.envelopeId(), "LLM_INTENT_CLASSIFY_FAILED", error, null)
        );
    }

    private void handleCommandRepair(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LlmCommandRepairPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM command repair payload is invalid", null);
            return;
        }
        collectChat(
                buildCommandRepairPrompt(payload),
                result -> {
                    adapter.publishCommandRepairResult(envelope, parseCommandRepairResult(payload, result));
                    context.complete(envelope.envelopeId());
                },
                error -> context.fail(envelope.envelopeId(), "LLM_COMMAND_REPAIR_FAILED", error, null)
        );
    }

    private void collectChat(String prompt, java.util.function.Consumer<String> onComplete, java.util.function.Consumer<String> onError) {
        StringBuilder builder = new StringBuilder();
        long requestId = llmEngine.beginStreamRequest(onError);
        if (requestId <= 0L) {
            return;
        }
        adapter.submitLlmIoTask(null, () -> llmEngine.streamChatBlocking(
                requestId,
                List.of(new ChatMessage("user", prompt)),
                0.6D,
                true,
                false,
                builder::append,
                finishReason -> onComplete.accept(builder.toString()),
                onError
        ));
    }

    private String buildIntentClassifyPrompt(LlmIntentClassifyPayload payload) {
        return "You are the Minecraft voice command intent classifier for Tianshu. "
                + "Return only one compact JSON object with fields commandLike, commandProbability, guessedIntentType, reason. "
                + "commandLike must be true only when the user text is probably a game operation command, not normal chat. "
                + "Text: " + payload.normalizedText() + "\n"
                + "Original: " + payload.originalText() + "\n"
                + "Local confidence: " + payload.localConfidence() + "\n"
                + "Command word ratio: " + payload.commandWordRatio() + "\n"
                + "Available commands: " + payload.availableCommands() + "\n"
                + "Known Minecraft names: " + payload.knownMcNames();
    }

    private String buildCommandRepairPrompt(LlmCommandRepairPayload payload) {
        return "You are the Minecraft voice command repair module for Tianshu. "
                + "Return only one compact JSON object with fields repairedText, reason. "
                + "Repair ASR homophones, item names, and word order so the text can be parsed as a Minecraft command. "
                + "Do not answer conversationally and do not execute the command. "
                + "Text: " + payload.normalizedText() + "\n"
                + "Original: " + payload.originalText() + "\n"
                + "Guessed intent: " + payload.guessedIntentType() + "\n"
                + "Available commands: " + payload.availableCommands() + "\n"
                + "Known Minecraft names: " + payload.knownMcNames();
    }

    private LlmIntentClassifyResultPayload parseIntentClassifyResult(LlmIntentClassifyPayload payload, String result) {
        String text = result == null ? "" : result;
        boolean commandLike = extractBoolean(text, COMMAND_LIKE_PATTERN, guessCommandLike(text));
        double probability = extractDouble(text, COMMAND_PROBABILITY_PATTERN, commandLike ? 0.75D : 0.25D);
        String guessedIntent = extractString(text, GUESSED_INTENT_PATTERN, payload.candidateIntentType());
        String reason = extractString(text, REASON_PATTERN, text.trim());
        return new LlmIntentClassifyResultPayload(payload.originalText(), payload.normalizedText(), commandLike, probability, guessedIntent, trim(reason, 240), payload.turnId(), payload.sessionId());
    }

    private LlmCommandRepairResultPayload parseCommandRepairResult(LlmCommandRepairPayload payload, String result) {
        String text = result == null ? "" : result;
        String repairedText = extractString(text, REPAIRED_TEXT_PATTERN, text.trim());
        repairedText = stripCodeFence(repairedText).trim();
        String reason = extractString(text, REASON_PATTERN, "LLM_COMMAND_REPAIR");
        boolean changed = !repairedText.equals(payload.normalizedText());
        return new LlmCommandRepairResultPayload(payload.originalText(), payload.normalizedText(), repairedText, changed, trim(reason, 240), 1, payload.turnId(), payload.sessionId());
    }

    private boolean extractBoolean(String text, Pattern pattern, boolean fallback) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return fallback;
        }
        return Boolean.parseBoolean(matcher.group(1));
    }

    private double extractDouble(String text, Pattern pattern, double fallback) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String extractString(String text, Pattern pattern, String fallback) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return fallback == null ? "" : fallback;
        }
        return matcher.group(1).replace("\\\\n", "\n").replace("\\\"", "\"");
    }

    private boolean guessCommandLike(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("commandlike true") || lower.contains("command-like true") || lower.contains("is a command") || lower.contains("game command");
    }

    private String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, lastFence);
            }
        }
        return trimmed;
    }

    private String trim(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = stripCodeFence(text).trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private void publishSegment(TianshuEnvelope envelope, String text, AtomicInteger index) {
        if (text == null || text.isBlank()) {
            return;
        }
        adapter.publishStreamChunk(envelope, new StreamTextPayload(text, index.getAndIncrement(), false));
    }
}
