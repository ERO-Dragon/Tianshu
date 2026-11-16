package com.rheinmetal.tianshu.api.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTypeDeclarationsTest {

    @Test
    void aNameAloneIsEnoughAndTheDefaultsMatchTheCarrier() {
        List<EntityTypeSpec> specs = EntityTypeDeclarations.parse("lab", "[{\"name\":\"orb\"}]");

        assertEquals(1, specs.size());
        EntityTypeSpec spec = specs.get(0);
        assertEquals("lab:orb", spec.id());
        assertEquals(0.6F, spec.width());
        assertEquals(1.8F, spec.height());
        assertFalse(spec.fireImmune());
    }

    @Test
    void everyFieldIsRead() {
        EntityTypeSpec spec = EntityTypeDeclarations.parse("lab",
                "[{\"name\":\"orb\",\"width\":0.5,\"height\":0.25,\"fireImmune\":true}]").get(0);

        assertEquals("lab:orb", spec.id());
        assertEquals(0.5F, spec.width());
        assertEquals(0.25F, spec.height());
        assertTrue(spec.fireImmune());
    }

    @Test
    void anEmptyFileMeansNoDeclarations() {
        assertTrue(EntityTypeDeclarations.parse("lab", "[]").isEmpty());
        assertTrue(EntityTypeDeclarations.parse("lab", "").isEmpty());
        assertTrue(EntityTypeDeclarations.parse("lab", null).isEmpty());
    }

    @Test
    void aMalformedDeclarationNamesItsIndexInsteadOfBeingIgnored() {
        assertThrows(IllegalArgumentException.class,
                () -> EntityTypeDeclarations.parse("lab", "[{\"width\":1.0}]"));
        assertThrows(IllegalArgumentException.class,
                () -> EntityTypeDeclarations.parse("lab", "[{\"name\":\"orb\",\"width\":\"wide\"}]"));
        assertThrows(IllegalArgumentException.class,
                () -> EntityTypeDeclarations.parse("lab", "{\"name\":\"orb\"}"));
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> EntityTypeDeclarations.parse("lab", "[{\"name\":\"orb\"},{\"name\":\"orb\"}]"));
        assertTrue(duplicate.getMessage().contains("orb"));
    }

    @Test
    void aBadDimensionIsRefusedByTheSpecRatherThanAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> EntityTypeDeclarations.parse("lab", "[{\"name\":\"orb\",\"width\":0}]"));
        assertThrows(IllegalArgumentException.class,
                () -> EntityTypeDeclarations.parse("lab", "[{\"name\":\"bad name\"}]"));
    }
}