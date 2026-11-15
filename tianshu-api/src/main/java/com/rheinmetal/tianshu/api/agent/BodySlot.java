package com.rheinmetal.tianshu.api.agent;

/**
 * Where an item can be equipped. {@code MAIN_HAND} is the body's active main slot (what attacks and breaks use);
 * the rest are real equipment slots the carrier applies to itself, so armour changes damage taken.
 */
public enum BodySlot {

    MAIN_HAND,
    OFF_HAND,
    HEAD,
    CHEST,
    LEGS,
    FEET
}