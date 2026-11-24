package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.InstalledModIds;
import com.rheinmetal.tianshu.api.assembly.ModResources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every readable guide entry the installed mods ship, searched as one text.
 *
 * <p>Books are the best source a module can have: they ship inside the jar the player is actually running, so
 * they cannot be out of date, cannot go offline, and need no network at all. The price is that they are large
 * — a single tech mod's guide runs to tens of thousands of characters — so the whole book never goes to a
 * model. The catalog holds the entries in memory and hands back only what matched.</p>
 *
 * <p>Loading is deliberately lazy: the caller builds the catalog on first use and keeps it. Most sessions
 * never ask about a guide at all, and walking every jar at startup would cost every player for a feature most
 * of them never touch.</p>
 */
public final class DubheBookCatalog {

    private static final int MAX_ENTRIES = 20_000;

    private final List<DubheBookEntry> entries;

    private DubheBookCatalog(List<DubheBookEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Reads every installed mod's books. A mod without books, or with a broken one, simply contributes nothing.
     *
     * @param languages the reader's languages, best first; a book that ships the first one is read in it
     */
    public static DubheBookCatalog load(InstalledModIds ids, ModResources resources,
                                        List<String> languages, LogSink log) {
        List<DubheBookEntry> all = new ArrayList<>();
        for (String modId : ids.all()) {
            if (all.size() >= MAX_ENTRIES) {
                break;
            }
            for (String namespace : resources.children(modId, "assets")) {
                if (namespace.contains(".")) {
                    continue;
                }
                for (String book : DubhePatchouliParser.bookIds(resources, modId, namespace)) {
                    all.addAll(DubhePatchouliParser.parse(modId, namespace, book, resources, languages, log));
                }
            }
        }
        return new DubheBookCatalog(all.size() > MAX_ENTRIES ? all.subList(0, MAX_ENTRIES) : all);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * The entries that best match {@code query}, strongest first.
     *
     * <p>Matching is term based rather than substring based: Chinese has no spaces, so a query is cut into
     * overlapping two-character terms and ASCII words, and each term found in a title weighs more than one
     * found in a body. A model that gets the wrong entry can rephrase and ask again, so the matcher favours
     * recall over precision and never hides an entry because of a near miss.</p>
     */
    public List<DubheBookEntry> search(String query, int limit) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || entries.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> terms = terms(normalized);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        for (DubheBookEntry entry : entries) {
            int score = score(entry, terms, normalized);
            if (score > 0) {
                scored.add(new Scored(entry, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(scored1 -> scored1.entry().title()));
        int size = Math.min(limit, scored.size());
        List<DubheBookEntry> matches = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            matches.add(scored.get(index).entry());
        }
        return List.copyOf(matches);
    }

    /** ASCII words plus overlapping Chinese pairs; one-character Chinese runs stay whole. */
    static List<String> terms(String query) {
        if (query == null) {
            return List.of();
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        Matcher words = Pattern.compile("[a-z0-9_]{2,}").matcher(normalized);
        while (words.find()) {
            terms.add(words.group());
        }
        Matcher han = Pattern.compile("[\\u4e00-\\u9fff]+").matcher(normalized);
        while (han.find()) {
            String run = han.group();
            if (run.length() == 1) {
                terms.add(run);
                continue;
            }
            for (int index = 0; index + 1 < run.length(); index++) {
                terms.add(run.substring(index, index + 2));
            }
        }
        return terms;
    }

    private static int score(DubheBookEntry entry, List<String> terms, String query) {
        String title = entry.title().toLowerCase(Locale.ROOT);
        String body = entry.text().toLowerCase(Locale.ROOT);
        int score = title.equals(query) ? 50 : title.contains(query) ? 20 : 0;
        for (String term : terms) {
            if (title.contains(term)) {
                score += 3;
            } else if (body.contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    private record Scored(DubheBookEntry entry, int score) {
    }
}