package com.rheinmetal.tianshu.dubhe.external;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityContext;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegatePromptRepository;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmRequestHandler;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 天枢对外发布的**技能**（G10 修订）：{@code dubhe:search}。
 *
 * <p>舟楫的 {@code SKILL} 是**可读材料**：调用方 {@code read} 它，拿到一段给模型看的正文；它不是可执行工具，
 * 也不是天枢内部代理循环里的执行单元。搜索这件事整体就是一个技能——主入口（这篇说明）告诉模型"什么时候、
 * 按什么步骤去网上找资料"，更细的用法放在 references 里按需取（渐进式披露，G58）。</p>
 *
 * <p>天枢没有单独的 {@code relay} 能力：relay 是外交技能里的一篇 reference，说明"要委托别人时怎么发现目标、
 * 投递、查进度"，它只在天枢内部被代理阅读，不注册成舟楫能力。</p>
 *
 * <p>当前实现仍是**模型自身的知识 + 固定提示词**，尚未接联网检索模块；接通后替换的是 handler 内部的检索实现，
 * 技能对外的 id、kind、读写契约不变。</p>
 */
public final class DubheExternalCapabilities {

    public static final CapabilityId SEARCH = CapabilityId.of("dubhe", "search");
    private static final int SEARCH_MAX_TOKENS = 512;
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private static final String PROVIDER = "dubhe";
    private static final DubheCallerQuota QUOTA = new DubheCallerQuota();

    /** The one model call the search skill is allowed to make. */
    @FunctionalInterface
    public interface SearchLlm {
        void submit(LLMPromptRequestPayload payload, DubheLlmRequestHandler handler, Duration timeout);
    }

    private DubheExternalCapabilities() {
    }

    public static void register(SearchLlm llm, DubheDelegatePromptRepository prompts) {
        // Replace on re-registration: the module can be prepared again in the same process (tests, reload).
        unregister();
        CapabilityCatalog.register(searchDescriptor(), (context, args) ->
                QUOTA.submit(quotaKey(context), () -> search(llm, prompts, args)));
    }

    /** Removes the skill row; a stopped module must not stay readable. */
    public static void unregister() {
        CapabilityCatalog.unregister(SEARCH);
    }

    /** The search skill row: readable material, not a tool. */
    public static CapabilityDescriptor searchDescriptor() {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("schemaVersion", 1);
        attributes.addProperty("readOnly", true);
        attributes.addProperty("maxTokens", SEARCH_MAX_TOKENS);
        attributes.addProperty("entry", "search");
        attributes.addProperty("references", "on demand");
        return new CapabilityDescriptor(
                SEARCH,
                CapabilityKind.SKILL,
                CapabilityBinding.LOCAL,
                "search skill: when and how to look things up online; read it for the entry, references on demand",
                ToolSide.CLIENT,
                ToolPermission.EVERYONE,
                PROVIDER,
                attributes
        );
    }

    private static String quotaKey(CapabilityContext context) {
        return context.caller().kind() + ":" + context.caller().id() + "|" + SEARCH;
    }

    private static CompletableFuture<JsonObject> search(SearchLlm llm, DubheDelegatePromptRepository prompts,
                                                        JsonObject args) {
        String intent = text(args, "intent");
        if (intent.isBlank()) {
            throw new IllegalArgumentException("intent is required");
        }
        if (llm == null || prompts == null) {
            throw new IllegalStateException("search is not wired");
        }
        List<LLMPromptRequestPayload.MessageItemPayload> messages = new ArrayList<>();
        String system = prompts.externalSearchSystem();
        if (!system.isBlank()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.system(system));
        }
        messages.add(LLMPromptRequestPayload.MessageItemPayload.user(prompts.externalSearchUser(intent)));
        LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
                "dubhe.search." + Integer.toHexString(intent.hashCode()),
                messages,
                false,
                false,
                SEARCH_MAX_TOKENS,
                1,
                new LLMPromptRequestPayload.SamplingPayload(0.2F, null, null, null)
        );
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        llm.submit(payload, payload1 -> {
            JsonObject result = new JsonObject();
            String text = payload1 == null || !payload1.isCompleted() ? "" : payload1.text();
            // SKILL reads return text; the body is what a model consumes.
            result.addProperty("text", text == null ? "" : text.trim());
            result.add("sources", new com.google.gson.JsonArray());
            future.complete(result);
        }, SEARCH_TIMEOUT);
        return future;
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }
}