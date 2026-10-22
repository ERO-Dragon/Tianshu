package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NeoForgeAXWorldIdentityProviderTest {
    @Test
    void backgroundConsumersOnlyReadTheLatestCapturedWorldIdentity() {
        NeoForgeAXWorldIdentityProvider provider = new NeoForgeAXWorldIdentityProvider();
        AXScopeSnapshot captured = new AXScopeSnapshot(
                AXScopeKind.SERVER_WORLD,
                "server|example.test",
                "Example",
                "minecraft:overworld",
                true
        );

        provider.update(captured);

        assertEquals(captured, provider.currentWorldIdentity());

        provider.clear();

        assertEquals(AXScopeSnapshot.unknown(), provider.currentWorldIdentity());
    }
}
