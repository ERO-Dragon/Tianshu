package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.InstalledModIds;
import com.rheinmetal.tianshu.api.assembly.ModResources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** A book much larger than this is not a book; the cap keeps one pathological mod from owning memory. */
    private static final int MAX_ENTRIES = 20_000;

    private final List<DubheBookEntry> entries;
    private final Map<String, List<DubheBookEntry>> byMod;

    private DubheBookCatalog(List<DubheBookEntry> entries) {
        this.entries = List.copyOf(entries);
        Map<String, List<DubheBookEntry>> grouped = new LinkedHashMap<>();
        for (DubheBookEntry entry : entries) {
            grouped.computeIfAbsent(entry.modId(), modId -> new ArrayList<>()).add(entry);
        }
        Map<String, List<DubheBookEntry>> frozen = new LinkedHashMap<>();
        grouped.forEach((modId, list) -> frozen.put(modId, List.copyOf(list)));
        this.byMod = java.util.Collections.unmodifiableMap(frozen);
    }

    /**
     * Reads every installed mod's books. A mod without books, or with a broken one, simply contributes nothing.
     *
     * @param languages the reader's languages, best first
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

    /** The mods that ship at least one readable entry, in load order. */
    public List<String> mods() {
        return List.copyOf(byMod.keySet());
    }

    public int entryCount(String modId) {
        List<DubheBookEntry> entries = byMod.get(modId);
        return entries == null ? 0 : entries.size();
    }

    /** One page of a mod's titles, in book order. */
    public List<String> titles(String modId, int page, int pageSize) {
        List<DubheBookEntry> entries = byMod.get(modId);
        if (entries == null) {
            return List.of();
        }
        int from = Math.max(0, (page - 1) * pageSize);
        if (from >= entries.size()) {
            return List.of();
        }
        int to = Math.min(entries.size(), from + pageSize);
        List<String> titles = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) {
            titles.add(entries.get(index).title());
        }
        return List.copyOf(titles);
    }

    /** An entry by its exact title, for the "the model recognised it in the listing" path. */
    public Optional<DubheBookEntry> find(String modId, String title) {
        List<DubheBookEntry> entries = byMod.get(modId);
        if (entries == null || title == null) {
            return Optional.empty();
        }
        String wanted = title.trim().toLowerCase(Locale.ROOT);
        for (DubheBookEntry entry : entries) {
            if (entry.title().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
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
        return search(query, limit, null);
    }

    /** As {@link #search(String, int)}, restricted to one mod when {@code modId} is given. */
    public List<DubheBookEntry> search(String query, int limit, String modId) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<DubheBookEntry> pool = modId == null ? entries : byMod.getOrDefault(modId, List.of());
        if (pool.isEmpty()) {
            return List.of();
        }
        List<String> terms = terms(normalized);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        for (DubheBookEntry entry : pool) {
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