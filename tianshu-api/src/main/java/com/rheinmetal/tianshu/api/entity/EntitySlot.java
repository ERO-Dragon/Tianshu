package com.rheinmetal.tianshu.api.entity;

/**
 * Where an item can be equipped. {@code MAIN_HAND} is the body's active main slot (what attacks and breaks use);
 * the rest are real equipment slots the carrier applies to itself, so armour changes damage taken.
 *
 * <p>The wire name is the one thing a tool schema and a tool parser must agree on. Both sides read it from here,
 * so a slot can never be advertised under one spelling and rejected under another — the failure mode that made
 * {@code body.equip} refuse {@code main} and {@code offhand} while its own description told the model to send
 * exactly those.</p>
 */
public enum EntitySlot {

    MAIN_HAND("main"),
    OFF_HAND("offhand"),
    HEAD("head"),
    CHEST("chest"),
    LEGS("legs"),
    FEET("feet");

    private final String wireName;

    EntitySlot(String wireName) {
        this.wireName = wireName;
    }

    /** The spelling a tool schema advertises for this slot. */
    public String wireName() {
        return wireName;
    }

    /**
     * Parses a caller-supplied slot name; {@code null} when it names no slot.
     *
     * <p>Both the wire name and the enum constant are accepted, because the enum spelling is what a person
     * hand-writing JSON reaches for. Every other spelling is a refusal the caller has to see, not a silent
     * fallback to the main hand.</p>
     */
    public static EntitySlot fromWireName(String name) {
        if (name == null) {
            return null;
        }
        String candidate = name.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        for (EntitySlot slot : values()) {
            if (slot.wireName.equalsIgnoreCase(candidate) || slot.name().equalsIgnoreCase(candidate)) {
                return slot;
            }
        }
        return null;
    }

    /** Every wire name in one sentence, for a schema description or an error message. */
    public static String wireNameList() {
        EntitySlot[] slots = values();
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < slots.length; index++) {
            if (index > 0) {
                text.append(index == slots.length - 1 ? " or " : ", ");
            }
            text.append(slots[index].wireName);
        }
        return text.toString();
    }
}