package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.ir.core.ParseUnit;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrParsePayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmCommandRepairPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmCommandRepairResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmIntentClassifyPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmIntentClassifyResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmPromptPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.List;

public final class IrModule {
    private final IrProtocolAdapter adapter;
    private final IrCommandParser commandParser;

    public IrModule(ProtocolRuntime runtime, IrCommandParser commandParser) {
        this.adapter = new IrProtocolAdapter(runtime);
        this.commandParser = commandParser == null ? IrCommandParser.unavailable() : commandParser;
    }

    public void register() {
        adapter.subscribeAsrFinalText(this::handleAsrFinalText);
        adapter.subscribeIntentClassifyResult(this::handleIntentClassifyResult);
        adapter.subscribeCommandRepairResult(this::handleCommandRepairResult);
        adapter.registerParseCapability(this::handleParseRequest);
    }

    private void handleAsrFinalText(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AsrTextPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "ASR payload is invalid", null);
            return;
        }
        parseAndRoute(envelope, context, new IrParsePayload(payload.text(), payload.rawText(), payload.turnId(), payload.sessionId(), "asr", 0, true));
    }

    private void handleParseRequest(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof IrParsePayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "IR payload is invalid", null);
            return;
        }
        parseAndRoute(envelope, context, payload);
    }

    private void handleIntentClassifyResult(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LlmIntentClassifyResultPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM intent classify result payload is invalid", null);
            return;
        }
        String text = routeText(payload.normalizedText(), payload.originalText());
        if (!payload.commandLike() || payload.commandProbability() < 0.5D) {
            adapter.publishResult(new IrResultPayload(false, text, payload.guessedIntentType(), "", payload.commandProbability(), false, payload.reason(), payload.turnId(), payload.sessionId()));
            routeToChat(envelope, context, text);
            return;
        }
        context.submit(adapter.requestCommandRepair(envelope, new LlmCommandRepairPayload(payload.originalText(), text, payload.guessedIntentType(), "", "", payload.turnId(), payload.sessionId())));
    }

    private void handleCommandRepairResult(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LlmCommandRepairResultPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM command repair result payload is invalid", null);
            return;
        }
        String repairedText = routeText(payload.repairedText(), payload.normalizedText());
        if (payload.repairDepth() > 1 || repairedText.isBlank()) {
            adapter.publishResult(new IrResultPayload(false, payload.normalizedText(), "", "", 0.0D, true, payload.reason(), payload.turnId(), payload.sessionId()));
            routeToChat(envelope, context, routeText(payload.normalizedText(), payload.originalText()));
            return;
        }
        parseAndRoute(envelope, context, new IrParsePayload(repairedText, payload.originalText(), payload.turnId(), payload.sessionId(), "llm_repair", payload.repairDepth(), false));
    }

    private void parseAndRoute(TianshuEnvelope envelope, ProtocolContext context, IrParsePayload payload) {
        String normalizedText = payload.text().trim();
        if (normalizedText.isEmpty()) {
            adapter.publishResult(new IrResultPayload(false, "", "", "", 0.0D, payload.repairDepth() > 0, "EMPTY_TEXT", payload.turnId(), payload.sessionId()));
            return;
        }

        IRParseResult parseResult = commandParser.parse(normalizedText, true);
        if (parseResult != null && parseResult.hasUnits()) {
            List<ParseUnit> units = parseResult.getUnits();
            ParseUnit firstUnit = units.get(0);
            String preview = commandParser.formatPreview(parseResult);
            adapter.publishResult(new IrResultPayload(true, parseResult.getHealedRawText(), firstUnit.intent.name(), firstUnit.targetRealItemId, 1.0D, payload.repairDepth() > 0, preview, payload.turnId(), payload.sessionId()));
            return;
        }

        String reason = parseResult == null ? "IR_UNAVAILABLE" : parseResult.isReady() ? "LOCAL_PARSE_MISSED" : "IR_NOT_READY";
        String routedText = parseResult != null && parseResult.getHealedRawText() != null && !parseResult.getHealedRawText().isBlank()
                ? parseResult.getHealedRawText().trim()
                : normalizedText;
        adapter.publishResult(new IrResultPayload(false, routedText, "", "", 0.0D, payload.repairDepth() > 0, reason, payload.turnId(), payload.sessionId()));
        if (!payload.llmAllowed()) {
            routeToChat(envelope, context, routedText);
            return;
        }
        if (payload.repairDepth() > 0) {
            routeToChat(envelope, context, routedText);
            return;
        }
        if (parseResult == null || !parseResult.shouldRequestLlmReview()) {
            routeToChat(envelope, context, routedText);
            return;
        }
        context.submit(adapter.requestIntentClassify(envelope, new LlmIntentClassifyPayload(payload.rawText(), routedText, parseResult.getCandidateIntentType(), parseResult.getBestScore(), parseResult.getEntityRatio(), "", parseResult.getBestCandidateText(), payload.turnId(), payload.sessionId())));
    }

    private void routeToChat(TianshuEnvelope envelope, ProtocolContext context, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        context.submit(adapter.requestLlmChat(envelope, new LlmPromptPayload(text.trim(), "")));
    }

    private String routeText(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (fallback != null) {
            return fallback.trim();
        }
        return "";
    }
}
