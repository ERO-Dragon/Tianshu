package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeSnapshot;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;

public final class NeoForgeAXWorldIdentityProvider implements AXWorldIdentityProvider {
    private volatile AXScopeSnapshot snapshot = AXScopeSnapshot.unknown();

    @Override
    public AXScopeSnapshot currentWorldIdentity() {
        return snapshot;
    }

    public void update(AXScopeSnapshot snapshot) {
        this.snapshot = snapshot == null ? AXScopeSnapshot.unknown() : snapshot;
    }

    public void clear() {
        snapshot = AXScopeSnapshot.unknown();
    }
}
