package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;

import java.util.List;
import java.util.Locale;

/**
 * 一次逻辑请求投几遍、下一遍之前等多久、哪些失败值得再投。
 *
 * <p>这是纯粹的判定，不持有状态、不发包、不看时钟：{@link AXLlmClient} 用它决定"再投一遍"还是"到此为止"。
 *
 * <p>三类失败永不重投，因为再投一遍的结论必然相同：
 * <ul>
 *   <li><b>参数错误</b>（400/401/403/404/422、invalid、unauthorized、forbidden …）：请求本身是错的，
 *       重发只是把同一个错误再问一遍；吐出来的必定是同一张错误单；</li>
 *   <li><b>上下文超限</b>（context_length、too long、max_token、token limit …）：提示词比模型的窗口长，
 *       窗口不会因为重发而变大，压缩才是修法；</li>
 *   <li><b>内容被拒</b>（content_filter、moderation、safety、refusal、policy violation …）：这是服务端的
 *       策略结论，重复询问只会得到同样的拒绝。</li>
 * </ul>
 *
 * <p>其余失败——429、5xx、超时、传输中断，以及认不出来的失败——一律重投：它们说的是"对方此刻不行"，
 * 不是"这个请求不行"，退避之后重发是唯一能把这一轮救回来的办法。认不出来时选择重投，是因为错误码
 * {@code LLM_INFERENCE_FAILED} 把所有推理失败压在同一个码里，真正的因果（HTTP 429、传输中断、流超时）
 * 只出现在错误正文里；因此判定只能看正文里的标记词，不能做错误码等值比较。
 *
 * <p>标记词只认小写英文 token，全部来自本工程的错误文案（{@code REMOTE_LLM_HTTP_STATUS status=429}、
 * {@code REMOTE_LLM_TRANSPORT_FAILED}、{@code REMOTE_LLM_STREAM_TIMEOUT} 等）与 OpenAI 兼容接口的
 * 标准错误体。命中任何一条"不可重投"标记即放弃；一条都不命中才重投。
 */
final class AXLlmRetryPolicy {
    /** 一次逻辑请求最多投递的遍数：首发一遍，重投四遍。 */
    static final int MAX_ATTEMPTS = 5;

    /**
     * 第 n 遍失败后、投第 n+1 遍之前等的毫秒数：曲线翻倍。
     *
     * <p>表长是 {@code MAX_ATTEMPTS - 1}，不是随手截断：第 {@value #MAX_ATTEMPTS} 遍就是最后一遍，
     * 它失败之后没有下一遍可等，所以没有 8 秒那一档。把 {@link #MAX_ATTEMPTS} 改成 6 时，
     * 这里必须同时补一档，否则第十五秒的等待无从表达。
     */
    private static final long[] RETRY_DELAYS_MS = {500L, 1_000L, 2_000L, 4_000L};

    /** 再投也不会变的失败标记：参数错误、上下文超限、内容拒绝。 */
    private static final List<String> FINAL_MARKERS = List.of(
            // 参数与凭证错误
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
            "status=403",
            "status=404",
            "status=422",
            // 上下文超限
            "context_length",
            "context length",
            "context window",
            "too long",
            "max_token",
            "token limit",
            "too many tokens",
            // 内容拒绝。只认 refusal，不认 refused：后者会误伤 "connection refused" 这条必须重投的传输失败。
            "content_filter",
            "content filter",
            "moderation",
            "safety",
            "refusal",
            "policy violation"
    );

    private AXLlmRetryPolicy() {
    }

    /**
     * 已经投出去 {@code attemptsMade} 遍之后，下一遍之前等多久。
     *
     * @param attemptsMade 已经投出去的遍数，至少为 1；超出退避表时取最后一档
     */
    static long delayMillis(int attemptsMade) {
        int index = Math.min(Math.max(attemptsMade, 1) - 1, RETRY_DELAYS_MS.length - 1);
        return RETRY_DELAYS_MS[index];
    }

    /** 这次失败值得再投一遍吗。取消与成功都不是失败，永远不重投。 */
    static boolean isRetryable(LLMPromptResultPayload result) {
        if (result == null || !result.isFailed()) {
            return false;
        }
        String haystack = (text(result.errorCode()) + " " + text(result.errorMessage())).toLowerCase(Locale.ROOT);
        for (String marker : FINAL_MARKERS) {
            if (haystack.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
