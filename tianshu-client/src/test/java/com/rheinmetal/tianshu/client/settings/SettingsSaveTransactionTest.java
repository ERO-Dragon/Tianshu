package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.client.settings.session.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SettingsSaveTransactionTest {
    @Test
    void failedPersistenceRestoresCurrentConfigAndTransformedValuesWithoutAcceptingDraft() {
        var config = new AtomicReference<>("old");
        var device = new AtomicReference<>("device-before");
        var draft = new MutableSettingsValue<>(config::get, config::set);
        draft.set("edit");
        config.set("external-update");
        var transaction = new SettingsSaveTransaction(draft)
                .write(device::get, device::set, "device-after");
        assertThrows(IllegalStateException.class, () -> transaction.commit(() -> {
            assertTrue(draft.dirty());
            assertEquals("edit", config.get());
            assertEquals("device-after", device.get());
            throw new IllegalStateException("disk failure");
        }));
        assertEquals("external-update", config.get());
        assertEquals("device-before", device.get());
        assertEquals("edit", draft.get());
        assertTrue(draft.dirty());
        new SettingsSaveTransaction(draft).commit(() -> assertTrue(draft.dirty()));
        assertFalse(draft.dirty());
    }

    @Test
    void setterFailureAlsoRestoresEarlierWrites() {
        var first = new AtomicReference<>("before");
        var second = new AtomicReference<>("before");
        var draft = new MutableSettingsValue<>(first::get, first::set);
        draft.set("after");
        var transaction = new SettingsSaveTransaction(draft)
                .write(second::get, value -> {
                    second.set(value);
                    if (value.equals("after")) throw new IllegalStateException("setter failed");
                }, "after");
        assertThrows(IllegalStateException.class, () -> transaction.commit(() -> fail("Must not persist")));
        assertEquals("before", first.get());
        assertEquals("before", second.get());
        assertTrue(draft.dirty());
    }
}
