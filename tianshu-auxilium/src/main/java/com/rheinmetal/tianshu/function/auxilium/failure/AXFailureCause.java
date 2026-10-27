package com.rheinmetal.tianshu.function.auxilium.failure;

import java.util.List;
import java.util.Locale;

/**
 * 一次失败属于哪一类，以及"值不值得再试一遍"。
 *
 * <p>这是全模块唯一的一份失败词表：重投判定（{@code AXLlmRetryPolicy}）、玩家看到的那句话
 * （{@code AXTurnStatusPublisher}）、失败账本里记的原因（{@code AXRetryEntry}）都从这里取，不允许各自
 * 维护一份标记词——三份词表迟早会说出三种结论。</p>
 *
 * <p>为什么按标记词而不是按错误码分类：织的 {@code LLM_INFERENCE_FAILED} 把所有推理失败压在同一个码里，
 * 真正的因果（HTTP 429、传输中断、流超时、内容拒绝）只出现在错误正文里。错误码只用来兜底，正文才是证据。</p>
 *
 * <p>四类失败不再重投，因为再试一遍的结论必然相同：参数与凭证错了、上下文比窗口长、内容被策略拒绝、
 * 这一次是取消而不是失败。其余（限流、服务端故障、超时、网络、以及认不出来的）都算"对方此刻不行"，
 * 过一会儿可能就行。</p>
 */
public enum AXFailureCause {
    /** 被取消：玩家打断、回合换代、模块卸载。不是失败，重试只会把一句没人要的话再发一遍。 */
    CANCELLED(false),
    /** 参数或凭证错误：请求本身是错的，重发得到同一张错误单。 */
    PARAMETER(false),
    /** 上下文超限：窗口不会因为重发而变大，压缩才是修法。 */
    CONTEXT_OVERFLOW(false),
    /** 内容被拒：服务端的策略结论，重复询问只会得到同样的拒绝。 */
    CONTENT_REFUSED(false),
    /** 网络层失败：连接、DNS、TLS、套接字断开。 */
    NETWORK(true),
    /** 超时：请求发出去了，答复没在期限内回来。 */
    TIMEOUT(true),
    /** 限流：请求太频繁，退避之后再来。 */
    RATE_LIMITED(true),
    /** 服务商侧故障：5xx、模型不可用、推理失败但原因不在参数上。 */
    PROVIDER_ERROR(true),
    /** 认不出来的失败。缺省选择重试：对方此刻不行比"请求本身不行"更常见。 */
    UNKNOWN(true);

    private static final String STATUS_KEY_PREFIX = "tianshu.presence.module.ax.failed";
    /** 认不出原因时用通用那一句；{@code AXTurnStatusPublisher.KEY_FAILED} 指向同一个键。 */
    public static final String STATUS_KEY_UNKNOWN = STATUS_KEY_PREFIX;

    /** 取消优先：一个被取消的请求，正文里写什么都还是取消。 */
    private static final List<String> CANCELLED_MARKERS = List.of("cancel", "preempt", "interrupt");

    private static final List<String> PARAMETER_MARKERS = List.of(
            "invalid",
            "bad request",
            "bad_request",
            "unauthorized",
            "forbidden",
            "not found",
            "not_found",
            "unprocessable",
            "status=400",
            "status=401",
            "status=402",
            "status=403",
            "status=404",
            "status=422",
            "credential",
            "api key",
            "insufficient",
            "balance",
            "billing",
            "missing"
    );

    private static final List<String> CONTEXT_OVERFLOW_MARKERS = List.of(
            "context_length",
            "context length",
            "context window",
            "too long",
            "max_token",
            "max token",
            "token limit",
            "too many tokens"
    );

    /**
     * 内容拒绝的标记词。
     *
     * <p>只认 {@code refusal}，不认 {@code refused}：后者会误伤 {@code Connection refused}——
     * 那是一条必须重试的传输失败，把它说成"内容被拒"会让玩家对着一个网络问题去找自己的措辞。</p>
     */
    private static final List<String> CONTENT_REFUSED_MARKERS = List.of(
            "content_filter",
            "content filter",
            "moderation",
            "safety",
            "refusal",
            "policy violation"
    );

    private static final List<String> NETWORK_MARKERS = List.of(
            "transport",
            "connect",
            "network",
            "unknownhost",
            "socket",
            "broken pipe",
            "eof",
            "dns",
            "tls",
            "ssl"
    );

    private static final List<String> TIMEOUT_MARKERS = List.of("timeout", "timed out");

    private static final List<String> RATE_LIMITED_MARKERS = List.of(
            "status=429",
            "rate limit",
            "rate_limit",
            "too many requests",
            "overloaded",
            "quota"
    );

    private static final List<String> PROVIDER_ERROR_MARKERS = List.of(
            "http_status",
            "status=5",
            "inference_failed",
            "server error",
            "internal",
            "unavailable"
    );

    private final boolean retryable;

    AXFailureCause(boolean retryable) {
        this.retryable = retryable;
    }

    /** 再投一遍是否有意义。取消不是失败，所以它也不算"值得重试"。 */
    public boolean retryable() {
        return retryable;
    }

    /** 玩家看到的那句话的语言键。未知原因退回通用失败键，不把机器码念给玩家。 */
    public String messageKey() {
        return this == UNKNOWN ? STATUS_KEY_UNKNOWN : STATUS_KEY_PREFIX + "." + name().toLowerCase(Locale.ROOT);
    }

    /** 落盘与诊断用的稳定英文标识；改名会让已存的账本读不出原因，所以它和枚举名保持一致。 */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 把一份结果的原因归到本词表。{@code code} 与 {@code message} 都参与判定，正文才是证据。 */
    public static AXFailureCause classify(String code, String message) {
        String haystack = ((code == null ? "" : code.trim()) + " " + (message == null ? "" : message.trim()))
                .toLowerCase(Locale.ROOT);
        if (containsAny(haystack, CANCELLED_MARKERS)) {
            return CANCELLED;
        }
        if (containsAny(haystack, PARAMETER_MARKERS)) {
            return PARAMETER;
        }
        if (containsAny(haystack, CONTEXT_OVERFLOW_MARKERS)) {
            return CONTEXT_OVERFLOW;
        }
        if (containsAny(haystack, CONTENT_REFUSED_MARKERS)) {
            return CONTENT_REFUSED;
        }
        if (containsAny(haystack, NETWORK_MARKERS)) {
            return NETWORK;
        }
        if (containsAny(haystack, TIMEOUT_MARKERS)) {
            return TIMEOUT;
        }
        if (containsAny(haystack, RATE_LIMITED_MARKERS)) {
            return RATE_LIMITED;
        }
        if (containsAny(haystack, PROVIDER_ERROR_MARKERS)) {
            return PROVIDER_ERROR;
        }
        return UNKNOWN;
    }

    /** 已落盘的标识读回枚举；认不出来按未知处理，旧账本不会因为多了一个新词就读不动。 */
    public static AXFailureCause fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (AXFailureCause cause : values()) {
            if (cause.name().equals(normalized)) {
                return cause;
            }
        }
        return UNKNOWN;
    }

    private static boolean containsAny(String haystack, List<String> markers) {
        for (String marker : markers) {
            if (haystack.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
