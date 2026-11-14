package com.rheinmetal.tianshu.client.api.settings.layer;

import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.api.text.UiText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layer vocabulary on its own: a description is a value, its elements read live state, and the list
 * filters the same way everywhere.
 */
final class SettingsLayerModelTest {
    private static final UiText TITLE = UiText.key("test.layer.title");

    @Test
    void keepsElementOrderAndFindsElementsById() {
        SettingsLayerElement.Text text = SettingsLayerElements.text("name", UiText.literal("Name"),
                UiText.literal(""), () -> "v", value -> { });
        SettingsLayerElement.Status status = SettingsLayerElements.status("status", () -> UiText.literal("s"));
        SettingsLayerModel model = SettingsLayerModel.of(TITLE, text, status);

        assertEquals(java.util.List.of("name", "status"),
                model.elements().stream().map(SettingsLayerElement::id).toList());
        assertSame(text, model.element("name"));
        assertSame(status, model.element("status"));
        assertNull(model.element("missing"));
        assertSame(text, model.element("name", SettingsLayerElement.Text.class));
        assertNull(model.element("name", SettingsLayerElement.Status.class));
    }

    @Test
    void keepsAtLeastTheDescriptionAndDefaultsBothOptionalInputs() {
        SettingsLayerModel empty = SettingsLayerModel.of(null);
        assertNotNull(empty.title());
        assertEquals(java.util.List.of(), empty.elements());
        empty.onClosed().run();

        java.util.List<SettingsLayerElement> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(SettingsLayerElements.status("status", () -> UiText.literal("s")));
        SettingsLayerModel model = SettingsLayerModel.of(TITLE, withNull);
        assertEquals(1, model.elements().size(), "a null element is dropped instead of drawn");
    }

    @Test
    void closeHookRunsOnlyTheProvidedOne() {
        AtomicInteger closed = new AtomicInteger();
        SettingsLayerModel plain = SettingsLayerModel.of(TITLE);
        SettingsLayerModel model = plain.closedBy(closed::incrementAndGet);

        model.onClosed().run();

        assertEquals(1, closed.get());
        assertTrue(plain.onClosed() != model.onClosed(), "closing one description does not affect another");
    }

    @Test
    void actionReadsItsEnabledPredicateEveryTime() {
        AtomicBoolean available = new AtomicBoolean(false);
        AtomicInteger ran = new AtomicInteger();
        SettingsLayerElement.Action action = SettingsLayerElements.action("go", UiText.literal("Go"),
                available::get, ran::incrementAndGet);

        assertFalse(action.isEnabled(), "a disabled action reports it before anything changes");
        available.set(true);
        assertTrue(action.isEnabled(), "and follows the state it was given, not a snapshot");
        action.run().run();
        assertEquals(1, ran.get());
        assertFalse(action.closesLayer(), "a plain action leaves the layer alone");
    }

    @Test
    void actionNormalizesStyleAndFlags() {
        SettingsLayerElement.Action plain = new SettingsLayerElement.Action("a", null, null, null, null, false);
        assertEquals(SettingsButtonStyle.NORMAL, plain.style());
        assertTrue(plain.isEnabled());
        assertNotNull(plain.label());

        SettingsLayerElement.Action closing = SettingsLayerElements.action("b", UiText.literal("Close"),
                SettingsButtonStyle.PRIMARY, SettingsLayerElements.alwaysEnabled(), () -> { }, true);
        assertEquals(SettingsButtonStyle.PRIMARY, closing.style());
        assertTrue(closing.closesLayer());
    }

    @Test
    void listShowsEverythingWithoutASearchField() {
        SettingsLayerElement.List list = SettingsLayerElements.list("models", UiText.literal("Models"),
                () -> java.util.List.of("alpha", "beta"), () -> "alpha", value -> { });

        assertFalse(list.searchable());
        assertEquals("", list.term());
        assertEquals(java.util.List.of("alpha", "beta"), list.visibleItems());
        assertEquals("alpha", list.currentSelection());
    }

    @Test
    void listFiltersByTheSearchTermIgnoringCase() {
        AtomicReference<String> term = new AtomicReference<>("");
        SettingsLayerElement.List list = SettingsLayerElements.searchableList("models", UiText.literal("Models"),
                UiText.literal("none"), () -> java.util.List.of("Alpha", "beta", "gamma"), () -> "",
                value -> { }, UiText.literal("Search"), term::get, term::set);

        assertTrue(list.searchable());
        assertEquals(java.util.List.of("Alpha", "beta", "gamma"), list.visibleItems(), "a blank term keeps all");

        term.set("ALP");
        assertEquals(java.util.List.of("Alpha"), list.visibleItems(), "the filter ignores case");

        term.set("a");
        assertEquals(java.util.List.of("Alpha", "beta", "gamma"), list.visibleItems(),
                "a term matches a substring of every row here");

        term.set("nothing");
        assertEquals(java.util.List.of(), list.visibleItems(), "a term that matches nothing lists nothing");

        term.set("  beta  ");
        assertEquals(java.util.List.of("beta"), list.visibleItems(), "the term is trimmed");
    }

    @Test
    void listDropsBlankRowsAndForwardsClicks() {
        AtomicReference<String> picked = new AtomicReference<>("");
        SettingsLayerElement.List list = SettingsLayerElements.list("models", UiText.literal("Models"),
                () -> java.util.List.of("alpha", "", "  ", "beta"), () -> "", picked::set);

        assertEquals(java.util.List.of("alpha", "beta"), list.visibleItems());
        list.onSelect().accept("beta");
        assertEquals("beta", picked.get());
    }

    @Test
    void textAndStatusReadTheirSuppliersEveryTime() {
        AtomicReference<String> value = new AtomicReference<>("first");
        SettingsLayerElement.Text text = SettingsLayerElements.text("id", UiText.literal("Id"),
                UiText.literal(""), value::get, value::set);
        assertEquals("first", text.currentValue());
        value.set("second");
        assertEquals("second", text.currentValue());

        AtomicReference<UiText> message = new AtomicReference<>(UiText.literal("one"));
        SettingsLayerElement.Status status = SettingsLayerElements.status("status", message::get);
        assertEquals("one", status.currentText().value());
        message.set(UiText.key("test.status.failed"));
        assertEquals("test.status.failed", status.currentText().value());

        SettingsLayerElement.Text bare = new SettingsLayerElement.Text("bare", null, null, null, null);
        assertNotNull(bare.label());
        assertNotNull(bare.hint());
        assertEquals("", bare.currentValue());
        SettingsLayerElement.Status quiet = new SettingsLayerElement.Status("quiet", null);
        assertNotNull(quiet.currentText());
    }
}
