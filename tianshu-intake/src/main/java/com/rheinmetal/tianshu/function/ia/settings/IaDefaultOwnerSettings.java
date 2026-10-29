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

    /** The participant the player picked, or blank when they never picked one. */
    String participantId();

    /** Fresh install: nobody picked anything, so the out-of-box default applies. */
    IaDefaultOwnerSettings UNCONFIGURED = new IaDefaultOwnerSettings() {
        @Override
        public boolean isDisabled() {
            return false;
        }

        @Override
        public String participantId() {
            return "";
        }
    };
}