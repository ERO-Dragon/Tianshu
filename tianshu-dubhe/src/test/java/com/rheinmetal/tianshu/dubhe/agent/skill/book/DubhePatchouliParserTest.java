package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.ModResources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubhePatchouliParserTest {

    private static final String BOOK = "assets/testmod/patchouli_books/guide";

    @Test
    void resolvesTranslationKeysAndKeepsLiteralText() {
        ModResources mods = FakeModResources.builder()
                .put("testmod", BOOK + "/book.json", "{}")
                .put("testmod", BOOK + "/en_us/entries/basics/altar.json",
                        "{\"name\":\"mod.altar\",\"pages\":["
                                + "{\"type\":\"text\",\"text\":\"mod.altar.p0\"},"
                                + "{\"type\":\"crafting\",\"recipe\":\"mod:altar\"},"
                                + "{\"type\":\"text\",\"text\":\"直接写的中文\"}]}")
                .put("testmod", "assets/testmod/lang/en_us.json",
                        "{\"mod.altar\":\"Runic Altar\","
                                + "\"mod.altar.p0\":\"$(l:basics/x)Altar$(/l) needs a stone.$(br)Place it.\"}")
                .build();

        List<DubheBookEntry> entries =
                DubhePatchouliParser.parse("testmod", "testmod", "guide", mods, LogSink.SYSTEM);

        assertEquals(1, entries.size(), "one entry file is one entry");
        DubheBookEntry entry = entries.get(0);
        assertEquals("Runic Altar", entry.title());
        // Form controls are dropped, the link label survives, and the untranslated page stays as written.
        assertEquals("Altar needs a stone.\nPlace it.\n\n直接写的中文", entry.text());
        assertEquals("testmod", entry.modId());
    }

    @Test
    void prefersChineseEntryTreeWhenTheBookShipsOne() {
        ModResources mods = FakeModResources.builder()
                .put("testmod", BOOK + "/book.json", "{}")
                .put("testmod", BOOK + "/en_us/entries/altar.json",
                        "{\"name\":\"Altar\",\"pages\":[{\"type\":\"text\",\"text\":\"English body\"}]}")
                .put("testmod", BOOK + "/zh_cn/entries/altar.json",
                        "{\"name\":\"祭坛\",\"pages\":[{\"type\":\"text\",\"text\":\"中文正文\"}]}")
                .build();

        List<DubheBookEntry> entries =
                DubhePatchouliParser.parse("testmod", "testmod", "guide", mods, LogSink.SYSTEM);

        assertEquals(1, entries.size());
        assertEquals("祭坛", entries.get(0).title());
        assertEquals("中文正文", entries.get(0).text());
    }

    @Test
    void skipsEntriesWithoutProseAndSurvivesMalformedOnes() {
        ModResources mods = FakeModResources.builder()
                .put("testmod", BOOK + "/book.json", "{}")
                .put("testmod", BOOK + "/en_us/entries/only_recipes.json",
                        "{\"name\":\"Recipes\",\"pages\":[{\"type\":\"crafting\",\"recipe\":\"a\"}]}")
                .put("testmod", BOOK + "/en_us/entries/broken.json", "{not json")
                .put("testmod", BOOK + "/en_us/entries/real.json",
                        "{\"name\":\"Real\",\"pages\":[{\"type\":\"text\",\"text\":\"body\"}]}")
                .build();

        List<DubheBookEntry> entries =
                DubhePatchouliParser.parse("testmod", "testmod", "guide", mods, LogSink.SYSTEM);

        assertEquals(1, entries.size(), "a recipe-only entry and a broken one contribute nothing");
        assertEquals("Real", entries.get(0).title());
    }

    @Test
    void findsBooksByTheirBookJson() {
        ModResources mods = FakeModResources.builder()
                .put("testmod", "assets/testmod/patchouli_books/guide/book.json", "{}")
                .put("testmod", "assets/testmod/patchouli_books/guide/en_us/entries/a.json", "{}")
                .put("testmod", "assets/other/patchouli_books/notabook/en_us/entries/a.json", "{}")
                .build();

        assertEquals(List.of("guide"), DubhePatchouliParser.bookIds(mods, "testmod", "testmod"));
        assertTrue(DubhePatchouliParser.bookIds(mods, "testmod", "other").isEmpty(),
                "a directory without book.json is not a book");
        assertTrue(DubhePatchouliParser.bookIds(mods, "testmod", "absent").isEmpty());
    }

    @Test
    void stripsFormatControlsButKeepsParagraphBreaks() {
        assertEquals("a\nb", DubhePatchouliParser.plain("a$(br)b"));
        assertEquals("a\n\nb", DubhePatchouliParser.plain("a$(p)b"));
        assertEquals("label", DubhePatchouliParser.plain("$(l:somewhere)label$(/l)"));
        assertEquals("", DubhePatchouliParser.plain("$(0)$(k)"));
    }
}