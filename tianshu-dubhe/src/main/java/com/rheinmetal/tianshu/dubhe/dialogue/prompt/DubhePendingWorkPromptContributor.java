package com.rheinmetal.tianshu.dubhe.dialogue.prompt;

import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateRegistry;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateTicket;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptTexts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Renders the in-flight delegations as a tail section, so the character can answer "还在办" without being told
 * again by the player.
 *
 * <p>Only the pending question and the waited seconds are shown: a report's facts and materials never belong in
 * this block (A2), and the block is capped so a burst of delegations cannot grow the tail without bound.</p>
 */
public final class DubhePendingWorkPromptContributor implements DubhePromptContributor {

    /** Matches {@code pending_work} in the profile's section order. */
    public static final String SECTION_ID = "pending_work";

    private static final int MAX_LINES = 3;
    private static final int MAX_QUERY_CHARS = 48;

    private final DubheDelegateRegistry registry;
    private final LongSupplier nowMillis;

    public DubhePendingWorkPromptContributor(DubheDelegateRegistry registry) {
        this(registry, System::currentTimeMillis);
    }

    public DubhePendingWorkPromptContributor(DubheDelegateRegistry registry, LongSupplier nowMillis) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
    }

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(DubhePromptBuildContext context, DubhePromptAssemblyBuilder builder) {
        List<DubheDelegateTicket> open = registry.inFlight(context.context().scope());
        if (open.isEmpty()) {
            return;
        }
        long now = nowMillis.getAsLong();
        int shown = Math.min(open.size(), MAX_LINES);
        List<String> lines = new ArrayList<>(shown + 1);
        for (int index = 0; index < shown; index++) {
            DubheDelegateTicket ticket = open.get(index);
            long waitedSeconds = Math.max(0L, (now - ticket.createdAtMillis()) / 1_000L);
            lines.add(DubhePromptSectionRenderer.render(
                    context,
                    DubhePromptTexts.PENDING_WORK_LINE,
                    Map.of("query", shorten(ticket.query()), "seconds", Long.toString(waitedSeconds))));
        }
        if (open.size() > shown) {
            lines.add(DubhePromptSectionRenderer.render(
                    context,
                    DubhePromptTexts.PENDING_WORK_MORE,
                    Map.of("count", Integer.toString(open.size() - shown))));
        }
        String section = DubhePromptSectionRenderer.renderContent(
                context, DubhePromptTexts.SECTION_PENDING_WORK, String.join("\n", lines));
        if (!section.isBlank()) {
            builder.addTailSection(section);
        }
    }

    private static String shorten(String query) {
        String trimmed = query == null ? "" : query.strip();
        return trimmed.length() <= MAX_QUERY_CHARS ? trimmed : trimmed.substring(0, MAX_QUERY_CHARS) + "…";
    }
}