package com.rheinmetal.tianshu.dubhe.external;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityContext;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegatePromptRepository;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmRequestHandler;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 天枢对外只暴露两个能力（G10）：{@code DUBHE.SEARCH} 与 {@code DUBHE.RELAY}。
 *
 * <p>执行层（代理、技能目录、记忆、世界动作）一律不对外，也没有"提交任务给我规划"的通用入口（G38 已取消）
 * ；两个能力各有明确输入输出：</p>
 *
 * <ul>
 *   <li>{@code DUBHE.SEARCH}（只读）：入 {@code intent}，出 {@code conclusion} + {@code sources}；不读会话上下文、
 *       不做多步规划，预算固定。返回结论需要一次固定的 LLM 查证调用。</li>
 *   <li>{@code DUBHE.RELAY}（只转发）：入 {@code target} + {@code task} 投递；或 {@code target} + {@code taskId}
 *       查状态。它不执行任何世界动作，只把请求转给目标模组已注册的 capability / 读工具。</li>
 * </ul>
 *
 * <p>能力 id 用 {@code dubhe:search} / {@code dubhe:relay} 的注册形式，对外文档名写作 {@code DUBHE.SEARCH} /
 * {@code DUBHE.RELAY}。</p>
 */
public final class DubheExternalCapabilities {

    public static final CapabilityId SEARCH = CapabilityId.of("dubhe", "search");
    public static final CapabilityId RELAY = CapabilityId.of("dubhe", "relay");
    private static final int SEARCH_MAX_TOKENS = 512;
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private static final String PROVIDER = "dubhe";
    private static final DubheCallerQuota QUOTA = new DubheCallerQuota();

    /** 对外的是功能，不是技能：kind 用一个中性的 function，agent 技能永远不注册成能力。 */
    private static final CapabilityKind FUNCTION_KIND = CapabilityKind.of("function");

    /** The one model call the research capability is allowed to make. */
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
                QUOTA.submit(quotaKey(context, SEARCH), () -> research(llm, prompts, args)));
        CapabilityCatalog.register(relayDescriptor(), (context, args) ->
                QUOTA.submit(quotaKey(context, RELAY), () -> relay(context.caller(), args)));
    }

    /** Removes both rows; called when the module stops so a stopped module is not callable. */
    public static void unregister() {
        CapabilityCatalog.unregister(SEARCH);
        CapabilityCatalog.unregister(RELAY);
    }

    public static CapabilityDescriptor searchDescriptor() {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("schemaVersion", 1);
        attributes.addProperty("readOnly", true);
        attributes.addProperty("maxTokens", SEARCH_MAX_TOKENS);
        return new CapabilityDescriptor(
                SEARCH,
                FUNCTION_KIND,
                CapabilityBinding.LOCAL,
                "bounded read-only fact check: input intent, output conclusion and sources",
                ToolSide.CLIENT,
                ToolPermission.EVERYONE,
                PROVIDER,
                attributes
        );
    }

    public static CapabilityDescriptor relayDescriptor() {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("schemaVersion", 1);
        attributes.addProperty("forwardsOnly", true);
        return new CapabilityDescriptor(
                RELAY,
                FUNCTION_KIND,
                CapabilityBinding.LOCAL,
                "forward a task to another module, or query the state of a forwarded task",
                ToolSide.CLIENT,
                ToolPermission.EVERYONE,
                PROVIDER,
                attributes
        );
    }

    private static String quotaKey(CapabilityContext context, CapabilityId id) {
        return context.caller().kind() + ":" + context.caller().id() + "|" + id;
    }

    private static CompletableFuture<JsonObject> research(SearchLlm llm, DubheDelegatePromptRepository prompts,
                                                         JsonObject args) {
        String intent = text(args, "intent");
        if (intent.isBlank()) {
            throw new IllegalArgumentException("intent is required");
        }
        if (llm == null || prompts == null) {
            throw new IllegalStateException("research is not wired");
        }
        List<LLMPromptRequestPayload.MessageItemPayload> messages = new ArrayList<>();
        String system = prompts.externalSearchSystem();
        if (!system.isBlank()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.system(system));
        }
        messages.add(LLMPromptRequestPayload.MessageItemPayload.user(prompts.externalSearchUser(intent)));
        LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
                "dubhe.research." + Integer.toHexString(intent.hashCode()),
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
            String conclusion = payload1 == null || !payload1.isCompleted() ? "" : payload1.text();
            result.addProperty("conclusion", conclusion == null ? "" : conclusion.trim());
            result.add("sources", new com.google.gson.JsonArray());
            future.complete(result);
        }, SEARCH_TIMEOUT);
        return future;
    }

    private static CompletableFuture<JsonObject> relay(
            com.rheinmetal.tianshu.api.tool.ToolCaller caller, JsonObject args) {
        String target = text(args, "target");
        int separator = target.indexOf(':');
        if (separator <= 0 || separator >= target.length() - 1) {
            throw new IllegalArgumentException("target must be namespace:name");
        }
        String namespace = target.substring(0, separator);
        String name = target.substring(separator + 1);
        String task = text(args, "task");
        String taskId = text(args, "taskId");
        if (!task.isBlank()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("task", task);
            return CapabilityCatalog.invoke(CapabilityId.of(namespace, name), payload, caller, ToolSide.CLIENT)
                    .thenApply(result -> {
                        JsonObject answer = new JsonObject();
                        answer.addProperty("taskId", text(result, "taskId"));
                        return answer;
                    });
        }
        if (!taskId.isBlank()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("taskId", taskId);
            return TianshuTools.callJson(ToolId.of(namespace, name), payload, caller)
                    .thenApply(envelope -> {
                        if (envelope.has("ok") && !envelope.get("ok").getAsBoolean()) {
                            throw new IllegalStateException("relay target refused: " + text(envelope, "code"));
                        }
                        JsonElement result = envelope.has("result") ? envelope.get("result") : envelope;
                        String state = result != null && result.isJsonObject()
                                ? text(result.getAsJsonObject(), "state").toUpperCase(Locale.ROOT) : "";
                        JsonObject answer = new JsonObject();
                        answer.addProperty("state", switch (state) {
                            case "RUNNING", "DONE", "UNKNOWN" -> state;
                            default -> "UNKNOWN";
                        });
                        return answer;
                    });
        }
        throw new IllegalArgumentException("relay needs either task or taskId");
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }
}
