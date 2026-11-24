package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.entity.EntityTypeSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BodyCarriersTest {
    @Test
    void standaloneFerryRegistersNoNetworkedEntityTypes() {
        assertTrue(BodyCarriers.registrationTypes(List.of()).isEmpty());
        assertTrue(BodyCarriers.available(BodyKind.MARKER));
        assertTrue(BodyCarriers.available(BodyKind.STAND));
        assertFalse(BodyCarriers.available(BodyKind.WALKER));
    }

    @Test
    void contentModulesShareOneWalkerAndRetainTheirOwnTypes() {
        var first = new EntityTypeSpec("first", "orb", 0.5F, 0.5F, false);
        var second = new EntityTypeSpec("second", "orb", 0.7F, 0.8F, true);
        var types = BodyCarriers.registrationTypes(List.of(first, second));
        assertEquals(List.of(ModIdentity.MOD_ID + ":walker", first.id(), second.id()),
                types.stream().map(EntityTypeSpec::id).toList());
        assertEquals(List.of(first, second), types.subList(1, types.size()));
    }
}