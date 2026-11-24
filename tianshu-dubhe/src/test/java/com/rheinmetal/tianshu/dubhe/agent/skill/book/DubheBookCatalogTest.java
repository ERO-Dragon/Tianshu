package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.InstalledModIds;
import com.rheinmetal.tianshu.api.assembly.ModResources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheBookCatalogTest {

    private static final InstalledModIds ONE_MOD = () -> List.of("testmod");
    private static final List<String> LANGUAGES = List.of("zh_cn", "en_us");

    private static DubheBookCatalog catalog() {
        ModResources mods = FakeModResources.builder()
                .put("testmod", "assets/testmod/patchouli_books/guide/book.json", "{}")
                .put("testmod", "assets/testmod/patchouli_books/guide/en_us/entries/mana.json",
                        "{\"name\":\"Mana Pool\",\"pages\":[{\"type\":\"text\",\"text\":\"stores mana\"}]}")
                .put("testmod", "assets/testmod/patchouli_books/guide/en_us/entries/flowers.json",
                        "{\"name\":\"Flowers\",\"pages\":[{\"type\":\"text\",\"text\":\"feed the mana pool\"}]}")
                .put("testmod", "assets/testmod/patchouli_books/guide/en_us/entries/unrelated.json",
                        "{\"name\":\"Unrelated\",\"pages\":[{\"type\":\"text\",\"text\":\"nothing here\"}]}")
                .build();
        return DubheBookCatalog.load(ONE_MOD, mods, LANGUAGES, LogSink.SYSTEM);
    }

    @Test
    void readsEveryBookOfEveryInstalledMod() {
        assertEquals(3, catalog().size());
    }

    @Test
    void ranksTitleMatchesAboveBodyMatches() {
        List<DubheBookEntry> matches = catalog().search("mana", 2);

        assertEquals(2, matches.size());
        assertEquals("Mana Pool", matches.get(0).title(), "a title hit outranks a body hit");
        assertEquals("Flowers", matches.get(1).title());
    }

    @Test
    void returnsNothingWhenNoTermMatches() {
        assertTrue(catalog().search("netherite", 3).isEmpty());
    }

    @Test
    void matchesChineseWithoutSpaces() {
        assertEquals(List.of("魔力", "力池"), DubheBookCatalog.terms("魔力池"));
        assertEquals(List.of("create", "and", "goggles"), DubheBookCatalog.terms("Create and Goggles"));
    }

    @Test
    void emptyInstalledModsYieldsEmptyCatalog() {
        assertTrue(DubheBookCatalog.load(InstalledModIds.none(), ModResources.none(), LANGUAGES, LogSink.SYSTEM)
                .isEmpty());
    }
}