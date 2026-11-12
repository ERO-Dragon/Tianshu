package com.rheinmetal.tianshu.api.game;

/** What one machine operation moved, or why it did nothing. */
public record MachineOpResult(String machineId, String operation, String itemId, int moved, String message) {
}
