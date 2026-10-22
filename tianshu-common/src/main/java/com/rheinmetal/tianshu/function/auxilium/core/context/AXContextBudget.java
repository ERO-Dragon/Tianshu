package com.rheinmetal.tianshu.function.auxilium.core.context;

/**
 * What a single turn may actually put into the prompt: item counts and character limits.
 *
 * <h2>Every limit here is a tail or keep-window division</h2>
 * <p>The prompt layout has one budget ladder (see {@link AXMemoryWindowPolicy}): the tail is a fixed
 * size at every tier and holds the state snapshot, the retrieval pool and the current input, while
 * the kept window is a fraction of the window. The counts below are that budget divided by an
 * estimate of one item, and nothing else — the retired per-layout slot ratios are gone, so a count
 * cannot be derived from a second, unrelated ladder.</p>
 * <pre>
 *   T2 retrieval pool           = tailRetrievalTokenBudget                      (512, all tiers)
 *     memory share              = round(0.85 x pool)                            (435)
 *       retrieved blocks        = memoryShare / 40                             (10)
 *     knowledge share           = pool - memoryShare                            (77)
 *       knowledge RAG facts     = knowledgeShare / 20                            (3)
 *   Q current input             = tailCurrentInputTokenBudget x 2 chars         (>= 1000 chars)
 *   KEEP kept window            = clamp(0.10 x W, 2048, 49152) / 130            (25 .. 378 turns)
 * </pre>
 *
 * <h2>Why the counts do not grow with the window</h2>
 * <p>The tail does not grow with the window — every one of its tokens is paid at the uncached price
 * on every turn, so a bigger model buys a longer kept window and a bigger summary block instead. The
 * retrieval counts therefore have to stay put as well; a count derived from a share of {@code W}
 * would inflate on the first turn after a tier change (at the 32K floor the pre-tail formula already
 * gave 49 retrieved blocks for a tail that renders in a fraction of that). Only the kept window,
 * which is a genuine fraction of {@code W}, scales — and it is capped at
 * {@link #MAX_RECENT_RAW_DIALOGUE_TURNS}.</p>
 *
 * <p>The retired "recent memory blocks" slot is gone: window-boundary summaries are no longer a
 * per-turn tail item, they are the cacheable summary block in front of the kept window. Its share of
 * the retrieval pool therefore goes to the retrieved blocks, which are the only memory items the tail
 * still renders.</p>
 *
 * <p>{@link #MAX_RETRIEVAL_ITEMS} is a joint cap: retrieved blocks, recent blocks and knowledge
 * facts compete for one retrieval pool, so their sum is bounded rather than each of them
 * separately.</p>
 *
 * <p>Construction-time fallback: see {@link AXMemoryWindowPolicy#DEFAULT}. A resolved turn carries
 * its own budget through {@code AXRuntimeLlmBudget}; {@link #DEFAULT} covers only the window before
 * the first resolution and the failed-lookup path.</p>
 */
public record AXContextBudget(
        int maxRetrievedMemoryItems,
        int maxRecentRawDialogueTurns,
        int maxKnowledgeRagItems,
        int maxCurrentInputChars,
        int memoryRetrievalTokenBudget
) {
    /** Estimated tokens one retrieved or recent memory block occupies when rendered into the prompt. */
    static final int TOKENS_PER_MEMORY_ITEM = 40;

    /** Estimated tokens one knowledge RAG fact occupies. Facts are single short sentences. */
    static final int TOKENS_PER_KNOWLEDGE_ITEM = 20;

    /** Estimated tokens one verbatim dialogue turn occupies in the kept window. */
    static final int TOKENS_PER_RAW_DIALOGUE_TURN = 130;

    /** Share of the tail's retrieval pool the memory side owns; knowledge RAG takes the remainder. */
    static final double RATIO_RETRIEVAL_MEMORY_SHARE = 0.85;


    /**
     * Joint cap over retrieved blocks, recent blocks and knowledge facts. A pool of 516 tokens does
     * not reach it; it exists so that no future tier or estimate change can turn one turn's retrieval
     * into an unbounded fan-out.
     */
    static final int MAX_RETRIEVAL_ITEMS = 20;

    /**
     * Cap on rendered dialogue turns. Set above the largest tier's kept window (378 turns at
     * {@code W = 512000}) so that it never truncates a legitimate keep window, and still bounds a
     * misconfigured window.
     */
    static final int MAX_RECENT_RAW_DIALOGUE_TURNS = 400;

    /** Floor for the current input: truncating the player's own words is a product error. */
    static final int MIN_CURRENT_INPUT_CHARS = 1000;

    /** Characters per token for the current input, which is plain player text. */
    static final int CURRENT_INPUT_CHARS_PER_TOKEN = 2;

    public AXContextBudget {
        maxRetrievedMemoryItems = Math.max(0, maxRetrievedMemoryItems);
        maxRecentRawDialogueTurns = Math.max(0, maxRecentRawDialogueTurns);
        maxKnowledgeRagItems = Math.max(0, maxKnowledgeRagItems);
        maxCurrentInputChars = Math.max(MIN_CURRENT_INPUT_CHARS, maxCurrentInputChars);
        memoryRetrievalTokenBudget = Math.max(0, memoryRetrievalTokenBudget);
    }

    /**
     * Construction-time fallback budget, derived from the {@link AXMemoryWindowPolicy#DEFAULT} floor.
     *
     * <p>Not the truth of a turn: the prompt side of a resolved turn uses the budget carried by
     * {@code AXRuntimeLlmBudget}, which is derived from the LLM's reported context total. This value
     * covers the window before the first resolution and the failed-lookup path.</p>
     */
    public static final AXContextBudget DEFAULT = fromPolicy(AXMemoryWindowPolicy.DEFAULT);

    public static AXContextBudget fromPolicy(AXMemoryWindowPolicy policy) {
        AXMemoryWindowPolicy effectivePolicy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;

        int retrievalPool = Math.max(0, effectivePolicy.tailRetrievalTokenBudget());
        int memoryPool = shareOf(retrievalPool, RATIO_RETRIEVAL_MEMORY_SHARE);
        int knowledgePool = Math.max(0, retrievalPool - memoryPool);

        int memoryItems = memoryPool / TOKENS_PER_MEMORY_ITEM;
        int retrievedMemoryItems = Math.min(memoryItems, MAX_RETRIEVAL_ITEMS);
        int knowledgeRagItems = Math.min(
                knowledgePool / TOKENS_PER_KNOWLEDGE_ITEM,
                Math.max(0, MAX_RETRIEVAL_ITEMS - retrievedMemoryItems)
        );

        // The kept window is the one count that follows W, because the segment it fills does.
        int recentRawDialogueTurns = Math.min(
                MAX_RECENT_RAW_DIALOGUE_TURNS,
                effectivePolicy.recentRawKeepTokenMax() / TOKENS_PER_RAW_DIALOGUE_TURN
        );

        return new AXContextBudget(
                retrievedMemoryItems,
                recentRawDialogueTurns,
                knowledgeRagItems,
                Math.max(MIN_CURRENT_INPUT_CHARS,
                        effectivePolicy.tailCurrentInputTokenBudget() * CURRENT_INPUT_CHARS_PER_TOKEN),
                memoryPool
        );
    }

    /** {@code base * ratio}, rounded: a fraction of an item must not disappear into a truncating cast. */
    private static int shareOf(int base, double ratio) {
        return (int) Math.round(base * ratio);
    }
}
