package com.rheinmetal.tianshu.function.auxilium.core.context;


/**
 * Auxilium memory window policy.
 *
 * <p>Token budgets are derived from a single LLM {@code contextTokenBudget} total.
 * {@code totalContextTokenBudget} is the per-request total upper bound for prompt input plus
 * model output; AX intentionally uses only part of it, so output and runtime safety still have
 * room.</p>
 *
 * <h2>Prompt layout</h2>
 * <p>The layout is fixed and has no per-slot rationing on top of it:</p>
 * <pre>
 *   system（恒定，不占预算）  →  摘要块  →  压缩窗口  →  保持窗口  →  尾部（状态 / 检索 / 输入）
 * </pre>
 * <p>The runtime classification (simple vs. complex) decides who starts a turn and how it ends; it
 * does not move payload between these segments, so no budget field here is per-classification except
 * the TASK instruction/payload split, which is a TASK-lane layout of its own.</p>
 *
 * <h2>Every memory-side number is a function of {@code W}</h2>
 * <p>{@code W = totalContextTokenBudget} is the only input. There is deliberately no reference
 * budget and no linear scale factor: calibrating against a small reference made the ratios
 * meaningless at large windows — at {@code W = 102400} a "8000-reference" keep window became the
 * entire window and the compression trigger landed <em>above</em> it, so a trigger could never fire
 * before overflow. Each segment is stated directly as a fraction of {@code W}, clamped to an
 * absolute range that stays sane at both ends:</p>
 * <pre>
 *   SB              = clamp(0.05 x W, 2048, 24576)              summary block capacity
 *   KEEP_MAX        = clamp(0.10 x W, 2048, 49152)              verbatim kept window
 *   KEEP_TARGET     = round(0.8 x KEEP_MAX)                     trim target (hysteresis)
 *   WATERMARK       = 0.70 x W                                  compression trigger level
 *   COMPRESS_MAX    = max(1, WATERMARK - SB - KEEP_MAX - TAIL)  compression window capacity
 *   COMPRESS_TARGET = round(0.8 x COMPRESS_MAX)                 stop compressing below this
 *   UNIT            = min(8192, COMPRESS_MAX)                   one compression unit
 *   MAX_RAW_TOKENS  = KEEP_MAX + COMPRESS_MAX
 *   MAX_RAW_CHARS   = round(MAX_RAW_TOKENS x CHARS_PER_TOKEN)
 * </pre>
 * <p>At {@code W = 102400} that is 5,120 / 10,240 / 71,680 / 55,420 / 65,660: the compression
 * window stays strictly inside the window, which is what makes the trigger reachable at all. Only
 * the two summary-side capacities grow with {@code W}; the compression unit and the tail
 * reservation are sizes, not ratios, and stay fixed at every tier.</p>
 *
 * <h2>The tail is a size, and its shares are sizes too</h2>
 * <p>{@code TAIL} is re-sent every turn, so every one of its tokens is paid at the uncached price on
 * every turn. That is why it never scales with {@code W}: a large window buys a longer keep window
 * and a bigger summary block, never a bigger tail. Its three parts are therefore absolute sizes as
 * well:</p>
 * <pre>
 *   TAIL            = 900                                  hard cap on the whole tail, all tiers
 *     T1 state      = 256                                  the current-facts snapshot
 *     T2 retrieval  = 512                                  memory and knowledge RAG share this
 *     Q current     = 128                                  the player's own words
 *                     --- the parts add up to 896, four tokens of slack under the cap
 * </pre>
 * <p>Only two of the three are exposed as record components, because only two have consumers:
 * {@link #tailRetrievalTokenBudget} feeds {@link AXContextBudget}'s item limits and the memory
 * retriever's token pool, and {@link #tailCurrentInputTokenBudget} feeds the current-input character
 * limit. The T1 reservation is a constant because nothing reads it as a number — the state snapshot
 * is bounded by its own line selection, not by a division of a token budget.</p>
 *
 * <h2>Two lives of a policy value</h2>
 * <p>There are exactly two kinds of {@code AXMemoryWindowPolicy} in the AX pipeline, and they must
 * not be confused:</p>
 * <ol>
 *   <li><b>Construction-time fallback</b> — a value built once when the module is prepared (see
 *   {@code AXModule}), used only until the first real budget arrives and whenever the LLM budget
 *   lookup fails. {@link #DEFAULT} is the contract floor of that fallback.</li>
 *   <li><b>Per-turn real budget</b> — built by {@link #fromBudget} for every turn from the
 *   {@code contextTokenBudget} the LLM reports ({@code AXRuntimeLlmBudget.fromSnapshot}). This value
 *   always overrides the construction-time fallback for the consumers a turn touches.</li>
 * </ol>
 * <p>Consequence: no consumer may treat {@link #DEFAULT} as the truth of a turn. Anything that scales
 * with the real context size takes the per-turn policy, and only falls back when resolution fails.</p>
 */
public record AXMemoryWindowPolicy(
        int totalContextTokenBudget,
        int chatInputTokenBudget,
        int chatOutputReserveTokenBudget,
        int taskInputTokenBudget,
        int taskOutputReserveTokenBudget,
        int taskInstructionTokenBudget,
        int taskPayloadTokenBudget,
        int summaryBlockTokenBudget,
        int recentRawKeepTokenTarget,
        int recentRawKeepTokenMax,
        int shortTermCompressTokenTarget,
        int shortTermCompressTokenMax,
        int compressionUnitTokenBudget,
        int compressionWatermarkTokenBudget,
        int tailRetrievalTokenBudget,
        int tailCurrentInputTokenBudget,
        int maxRawTokenCount,
        int maxRawCharacters,
        int shortTermChatBlockLimit,
        long conversationPauseMillis
) {
    /** Smallest context total the policy will run on; anything below it is lifted to this floor. */
    static final int MIN_TOTAL_CONTEXT_TOKEN_BUDGET = 1000;

    // TASK-lane layout: instruction plus payload inside the TASK input share. TASK does not use the
    // memory-side layout, so these are the only per-segment ratios left.
    static final double RATIO_TASK_INSTRUCTION = 0.125;
    static final double RATIO_TASK_PAYLOAD = 0.75;

    /**
     * Summary block capacity ({@code SB}) as a fraction of the window, with the absolute range that
     * every tier is clamped into. The floor keeps small windows from starving the block; the ceiling
     * stops a large tier from spending more than it can usefully fill.
     */
    static final double RATIO_SUMMARY_BLOCK = 0.05;
    static final int MIN_SUMMARY_BLOCK_TOKENS = 2048;
    static final int MAX_SUMMARY_BLOCK_TOKENS = 24_576;

    /** Kept window ({@code KEEP_MAX}) as a fraction of the window, clamped like the summary block. */
    static final double RATIO_KEEP_WINDOW = 0.10;
    static final int MIN_KEEP_WINDOW_TOKENS = 2048;
    static final int MAX_KEEP_WINDOW_TOKENS = 49_152;

    /**
     * Share of a capacity that a trim target keeps. Below the capacity on purpose: trimming to a
     * target instead of to the limit is the hysteresis that stops a window from re-triggering on the
     * very next turn.
     */
    static final double RATIO_TRIM_TARGET = 0.80;

    /**
     * Compression watermark: the prompt size at which a compression trigger fires, as a fraction of
     * the window. It is the budget the memory segments have to fit into together, so it is the input
     * the compression window is derived from — not a quantity that can be set independently.
     */
    static final double RATIO_WATERMARK = 0.70;

    /** Whole tail reservation: state snapshot + retrieval + current input. Re-sent every turn. */
    static final int TAIL_TOKEN_BUDGET = 900;

    /** Tail part {@code T1}: the current-facts snapshot. A small share, not a ratio of {@code W}. */
    static final int TAIL_STATE_SNAPSHOT_TOKENS = 256;

    /** Tail part {@code Q}: the player's current words. Truncation here is a product error, not budget. */
    static final int TAIL_CURRENT_INPUT_TOKENS = 128;

    /**
     * Tail part {@code T2}: one pool shared by remembered history and knowledge RAG.
     *
     * <p>The three parts add up to 896, which sits inside {@link #TAIL_TOKEN_BUDGET} with four tokens
     * of slack: the cap is the reservation the compression window is derived from, the parts are what
     * the contributions may actually render.</p>
     */
    static final int TAIL_RETRIEVAL_TOKENS = 512;

    /** Raw history covered by one compression unit. A size, not a ratio, so it is tier-independent. */
    static final int COMPRESSION_UNIT_TOKENS = 8_192;

    /** Text density used when a capacity has to be expressed in characters instead of tokens. */
    static final int CHARS_PER_TOKEN = 4;

    /**
     * The contract floor: the construction-time fallback policy at the smallest context tier.
     *
     * <p>Used when the module is prepared and when a per-turn budget lookup fails. It is deliberately
     * the smallest sane window, so a failed lookup degrades to the most conservative behaviour instead
     * of pretending the model has a huge context. Per-turn values come from {@link #fromBudget}; see
     * the class comment for which one wins.</p>
     */
    public static final AXMemoryWindowPolicy DEFAULT =
            fromBudget(AXContextTier.COMPACT.maxTokens(), 3, 60000L);

    public AXMemoryWindowPolicy {
        totalContextTokenBudget = Math.max(MIN_TOTAL_CONTEXT_TOKEN_BUDGET, totalContextTokenBudget);
        chatInputTokenBudget = Math.max(MIN_TOTAL_CONTEXT_TOKEN_BUDGET, chatInputTokenBudget);
        chatOutputReserveTokenBudget = Math.max(0, chatOutputReserveTokenBudget);
        taskInputTokenBudget = Math.max(MIN_TOTAL_CONTEXT_TOKEN_BUDGET, taskInputTokenBudget);
        taskOutputReserveTokenBudget = Math.max(0, taskOutputReserveTokenBudget);
        taskInstructionTokenBudget = Math.max(0, taskInstructionTokenBudget);
        taskPayloadTokenBudget = Math.max(0, taskPayloadTokenBudget);
        summaryBlockTokenBudget = Math.max(0, summaryBlockTokenBudget);
        recentRawKeepTokenTarget = Math.max(0, recentRawKeepTokenTarget);
        recentRawKeepTokenMax = Math.max(recentRawKeepTokenTarget, recentRawKeepTokenMax);
        shortTermCompressTokenTarget = Math.max(0, shortTermCompressTokenTarget);
        shortTermCompressTokenMax = Math.max(shortTermCompressTokenTarget, shortTermCompressTokenMax);
        compressionUnitTokenBudget = Math.max(0, compressionUnitTokenBudget);
        compressionWatermarkTokenBudget = Math.max(0, compressionWatermarkTokenBudget);
        tailRetrievalTokenBudget = Math.max(0, tailRetrievalTokenBudget);
        tailCurrentInputTokenBudget = Math.max(0, tailCurrentInputTokenBudget);
        maxRawTokenCount = Math.max(recentRawKeepTokenMax + shortTermCompressTokenMax, maxRawTokenCount);
        maxRawCharacters = Math.max(0, maxRawCharacters);
        shortTermChatBlockLimit = Math.max(0, shortTermChatBlockLimit);
        conversationPauseMillis = Math.max(0L, conversationPauseMillis);
    }

    /**
     * 把字符数折算成近似的 token 数，用的是本策略里同一份文本密度。
     *
     * <p>只在 tokenizer 不可用、只能按字符估容量时使用——压缩窗口的累计与换代判据必须有一个不依赖
     * tokenizer 的量纲，否则云端 TOKEN_COUNT 恒不可用时水位永远到不了。</p>
     */
    public int tokensForCharacters(int characters) {
        return Math.max(0, characters) / CHARS_PER_TOKEN;
    }

    /** 同一份文本密度反过来用：token 容量折算成字符容量（tokenizer 不可用时的判据）。 */
    public int charactersForTokens(int tokens) {
        return Math.max(0, tokens) * CHARS_PER_TOKEN;
    }

    /** Context tier of this budget. Breadth-sensitive consumers resolve their mapping through it. */
    public AXContextTier tier() {
        return AXContextTier.of(totalContextTokenBudget);
    }

    /**
     * Build a policy from the LLM-provided context token budget.
     *
     * <p>Every budget field is derived from {@code contextTokenBudget} by the formulas in the class
     * comment; no field is read from a reference table and none is scaled by a leftover multiplier.</p>
     */
    public static AXMemoryWindowPolicy fromBudget(int contextTokenBudget, int shortTermChatBlockLimit, long conversationPauseMillis) {
        int totalBudget = Math.max(MIN_TOTAL_CONTEXT_TOKEN_BUDGET, contextTokenBudget);

        int summaryBlockBudget = shareOf(totalBudget, RATIO_SUMMARY_BLOCK,
                MIN_SUMMARY_BLOCK_TOKENS, MAX_SUMMARY_BLOCK_TOKENS);
        int keepBudgetMax = shareOf(totalBudget, RATIO_KEEP_WINDOW,
                MIN_KEEP_WINDOW_TOKENS, MAX_KEEP_WINDOW_TOKENS);
        int watermarkBudget = shareOf(totalBudget, RATIO_WATERMARK);

        // Derived, not a knob: whatever the watermark still leaves once the summary block, the kept
        // window and the permanent tail are paid for. It collapses to 1 on a window too small for AX
        // to run usefully, which the clamp makes explicit instead of letting a negative budget escape.
        int compressBudgetMax = Math.max(1,
                watermarkBudget - summaryBlockBudget - keepBudgetMax - TAIL_TOKEN_BUDGET);
        // One unit has to fit inside the compression window, otherwise a unit could never be closed.
        int compressionUnitBudget = Math.max(1, Math.min(COMPRESSION_UNIT_TOKENS, compressBudgetMax));
        int maxRawTokens = keepBudgetMax + compressBudgetMax;

        // The prompt input ceiling is the watermark itself. The watermark is already "the size the
        // prompt is allowed to reach before the trigger fires", so a second input share of W would
        // be a second ladder feeding the same number; there is deliberately only one.
        int inputBudget = Math.max(MIN_TOTAL_CONTEXT_TOKEN_BUDGET, watermarkBudget);
        int taskInputBudget = inputBudget;

        return new AXMemoryWindowPolicy(
                totalBudget,
                inputBudget,
                Math.max(0, totalBudget - inputBudget),
                taskInputBudget,
                Math.max(0, totalBudget - taskInputBudget),
                shareOf(taskInputBudget, RATIO_TASK_INSTRUCTION),
                shareOf(taskInputBudget, RATIO_TASK_PAYLOAD),
                summaryBlockBudget,
                shareOf(keepBudgetMax, RATIO_TRIM_TARGET),
                keepBudgetMax,
                shareOf(compressBudgetMax, RATIO_TRIM_TARGET),
                compressBudgetMax,
                compressionUnitBudget,
                watermarkBudget,
                TAIL_RETRIEVAL_TOKENS,
                TAIL_CURRENT_INPUT_TOKENS,
                maxRawTokens,
                // The character capacity is the same capacity expressed in the fallback text density.
                maxRawTokens * CHARS_PER_TOKEN,
                shortTermChatBlockLimit,
                conversationPauseMillis
        );
    }

    /** {@code base * ratio}, rounded: a fraction of a token must not disappear into a truncating cast. */
    private static int shareOf(int base, double ratio) {
        return (int) Math.round(base * ratio);
    }

    /** {@code base * ratio}, clamped into the absolute range that keeps the value sane at every tier. */
    private static int shareOf(int base, double ratio, int min, int max) {
        return Math.max(min, Math.min(max, shareOf(base, ratio)));
    }

}
