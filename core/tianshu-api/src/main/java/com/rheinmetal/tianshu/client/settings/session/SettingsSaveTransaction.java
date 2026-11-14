package com.rheinmetal.tianshu.client.settings.session;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Stages one module's writes. Drafts are accepted only after persistence succeeds. */
public final class SettingsSaveTransaction {
    private final List<MutableSettingsValue<?>> drafts;
    private final List<Write> writes = new ArrayList<>();

    public SettingsSaveTransaction(MutableSettingsValue<?>... drafts) {
        this.drafts = List.of(drafts);
        for (var draft : drafts) {
            writes.add(new Write(draft::write, draft.rollbackAction()));
        }
    }

    /** Registers a value whose display representation differs from the persisted value. */
    public <T> SettingsSaveTransaction write(Supplier<T> source, Consumer<T> sink, T value) {
        T previous = source.get();
        writes.add(new Write(() -> sink.accept(value), () -> sink.accept(previous)));
        return this;
    }

    public void commit(Runnable persist) {
        int attempted = 0;
        try {
            for (var write : writes) {
                attempted++;
                write.apply().run();
            }
            persist.run();
        } catch (RuntimeException failure) {
            for (int i = attempted - 1; i >= 0; i--) {
                try {
                    writes.get(i).rollback().run();
                } catch (RuntimeException rollbackFailure) {
                    if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        drafts.forEach(MutableSettingsValue::accept);
    }

    private record Write(Runnable apply, Runnable rollback) {}
}
