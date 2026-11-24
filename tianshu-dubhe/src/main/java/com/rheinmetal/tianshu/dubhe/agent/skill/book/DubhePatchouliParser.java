package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.ModResources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads one Patchouli book out of a mod's own resources.
 *
 * <p>Layout, as every Patchouli book uses it:</p>
 *
 * <pre>
 * assets/&lt;namespace&gt;/patchouli_books/&lt;book&gt;/
 *   book.json
 *   &lt;language&gt;/entries/**.json     one file per entry, holding a title key and the pages
 * assets/&lt;namespace&gt;/lang/&lt;language&gt;.json   the text those keys point at
 * </pre>
 *
 * <p>Two spellings are both valid and both appear in the wild: an entry field may hold a translation key, or
 * the literal text. One rule covers both — <b>look the value up in the language table, and when it is not
 * there, use it as it stands</b>. That also tolerates a key that nobody translated yet.</p>
 *
 * <p>Only {@code text} pages carry prose. Recipe pages ({@code crafting}, {@code spotlight} and every
 * mod-defined page type) describe items, not behaviour, and recipes are answered from the save rather than
 * from prose, so they are skipped rather than flattened into half-sentences.</p>
 */
final class DubhePatchouliParser {

    /** Preferred first: the client ships Chinese, and a Chinese entry reads better than an English one. */
    private static final List<String> LANGUAGES = List.of("zh_cn", "en_us");

    /** Patchouli format controls: {@code $(l:link)label$(/l)}, {@code $(0)}, {@code $(k)}, … */
    private static final Pattern FORMAT_CONTROL = Pattern.compile("\\$\\([^)]*\\)");

    /** Deep enough for the deepest real book, shallow enough to stop a malformed one. */
    private static final int MAX_DEPTH = 8;

    private DubhePatchouliParser() {
    }

    /** The book ids a namespace ships, verified by the presence of {@code book.json}. */
    static List<String> bookIds(ModResources resources, String modId, String namespace) {
        String root = "assets/" + namespace + "/patchouli_books";
        List<String> books = new ArrayList<>();
        for (String name : resources.children(modId, root)) {
            if (!name.endsWith(".json")
                    && resources.read(modId, root + "/" + name + "/book.json").isPresent()) {
                books.add(name);
            }
        }
        return books;
    }

    /** Every readable entry of one book, in file order; empty when the book has no text pages at all. */
    static List<DubheBookEntry> parse(String modId, String namespace, String bookId,
                                      ModResources resources, LogSink log) {
        String root = "assets/" + namespace + "/patchouli_books/" + bookId;
        for (String language : LANGUAGES) {
            List<String> files = new ArrayList<>();
            collectEntries(resources, modId, root + "/" + language + "/entries", files, 0);
            if (files.isEmpty()) {
                continue;
            }
            Map<String, String> table = languageTable(modId, namespace, language, resources, log);
            List<DubheBookEntry> entries = new ArrayList<>();
            for (String path : files) {
                readEntry(modId, path, table, resources, log).ifPresent(entries::add);
            }
            return List.copyOf(entries);
        }
        return List.of();
    }

    private static void collectEntries(ModResources resources, String modId, String directory,
                                       List<String> sink, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (String name : resources.children(modId, directory)) {
            String path = directory + "/" + name;
            if (name.endsWith(".json")) {
                sink.add(path);
            } else if (!name.contains(".")) {
                collectEntries(resources, modId, path, sink, depth + 1);
            }
        }
    }

    private static java.util.Optional<DubheBookEntry> readEntry(String modId, String path,
                                                                Map<String, String> table,
                                                                ModResources resources, LogSink log) {
        String json = resources.read(modId, path).orElse(null);
        if (json == null) {
            return java.util.Optional.empty();
        }
        try {
            JsonObject entry = JsonParser.parseString(json).getAsJsonObject();
            String title = plain(resolve(table, string(entry, "name")));
            if (title.isEmpty()) {
                return java.util.Optional.empty();
            }
            String text = pages(entry, table);
            return text.isEmpty() ? java.util.Optional.empty()
                    : java.util.Optional.of(new DubheBookEntry(modId, title, text));
        } catch (RuntimeException failure) {
            // One malformed entry must not cost the mod its whole book.
            log.warn("book.entry.invalid mod=" + modId + " path=" + path, failure);
            return java.util.Optional.empty();
        }
    }

    private static String pages(JsonObject entry, Map<String, String> table) {
        JsonElement pages = entry.get("pages");
        if (pages == null || !pages.isJsonArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement element : pages.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject page = element.getAsJsonObject();
            if (!"text".equals(string(page, "type"))) {
                continue;
            }
            String paragraph = plain(resolve(table, string(page, "text")));
            if (paragraph.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(paragraph);
        }
        return text.toString();
    }

    private static Map<String, String> languageTable(String modId, String namespace, String language,
                                                     ModResources resources, LogSink log) {
        String json = resources.read(modId, "assets/" + namespace + "/lang/" + language + ".json").orElse(null);
        if (json == null) {
            return Map.of();
        }
        try {
            JsonObject table = JsonParser.parseString(json).getAsJsonObject();
            Map<String, String> values = new HashMap<>(table.size() * 2);
            for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
                values.put(entry.getKey(), entry.getValue().isJsonPrimitive()
                        ? entry.getValue().getAsString() : "");
            }
            return values;
        } catch (RuntimeException failure) {
            // A broken language file only costs the translations, not the keys' own text.
            log.warn("book.lang.invalid mod=" + modId + " language=" + language, failure);
            return Map.of();
        }
    }

    /** A value is a key when the table knows it, and the text itself when it does not. */
    private static String resolve(Map<String, String> table, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String resolved = table.get(value);
        return resolved == null ? value : resolved;
    }

    /** Drops Patchouli format controls, keeping the visible label of a link. */
    static String plain(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw
                .replace("$(br2)", "\n")
                .replace("$(br)", "\n")
                .replace("$(p)", "\n\n");
        return FORMAT_CONTROL.matcher(text).replaceAll("").trim();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }
}