package com.rheinmetal.tianshu.function.ia.settings;

/**
 * The player's choice of default owner, as the arbiter reads it.
 *
 * <p>Two independent answers rather than one enum, because "off" is not the same as "never configured": an
 * explicit off must survive a module being installed later, while the unconfigured state follows the participant
 * that declared itself the out-of-box default.</p>
 */
public interface IaDefaultOwnerSettings {
    /** Whether the player turned the default owner off. */
    boolean isDisabled();

    /**
     * The participant the player picked, encoded as {@code moduleId:participantId}, or blank when they never
     * picked one.
     *
     * <p>A participant id is only unique inside its module, so the module is part of the selection. An
     * install that predates this encoding may still hold a bare participant id; a resolver must accept it
     * only when it names exactly one registered candidate.</p>
     */
    String selection();

    /** Fresh install: nobody picked anything, so the out-of-box default applies. */
    IaDefaultOwnerSettings UNCONFIGURED = new IaDefaultOwnerSettings() {
        @Override
        public boolean isDisabled() {
            return false;
        }

        @Override
        public String selection() {
            return "";
        }
    };
}