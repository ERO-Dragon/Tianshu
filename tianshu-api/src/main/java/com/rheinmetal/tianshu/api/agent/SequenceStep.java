package com.rheinmetal.tianshu.api.agent;

/**
 * One step of a bounded body sequence: a data description, never a program.
 *
 * <p>The platform executes steps in order and stops at the first failure. There are no variables, conditions or
 * loops here: choosing and repairing a plan is the orchestration layer's job, and keeping the step list inert keeps
 * the platform from growing an interpreter.</p>
 */
public sealed interface SequenceStep {

    String kind();

    record Move(ActorPosition target, MoveMode mode) implements SequenceStep {
        @Override
        public String kind() {
            return "move";
        }
    }

    record Look(ActorPosition target) implements SequenceStep {
        @Override
        public String kind() {
            return "look";
        }
    }

    record Mine(BlockTarget target) implements SequenceStep {
        @Override
        public String kind() {
            return "mine";
        }
    }

    record Place(BlockTarget target, String itemId, BlockFace face) implements SequenceStep {
        @Override
        public String kind() {
            return "place";
        }
    }

    record Interact(BlockTarget target) implements SequenceStep {
        @Override
        public String kind() {
            return "interact";
        }
    }

    record ItemUse(ItemUseTarget target, int holdTicks) implements SequenceStep {
        @Override
        public String kind() {
            return "item_use";
        }
    }

    record Pickup(int radius) implements SequenceStep {
        @Override
        public String kind() {
            return "pickup";
        }
    }

    record Equip(EquipRequest request) implements SequenceStep {
        @Override
        public String kind() {
            return "equip";
        }
    }
}