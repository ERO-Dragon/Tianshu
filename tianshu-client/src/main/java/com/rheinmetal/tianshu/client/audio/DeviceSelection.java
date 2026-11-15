package com.rheinmetal.tianshu.client.audio;

import java.util.List;
import java.util.Locale;

/**
 * Decides which of the system's audio mixers is a real microphone.
 *
 * <p>The same long condition used to be written out three times inside {@link AudioManager} — once in the automatic
 * pick and twice in the two "list the microphones" methods. Three copies of one rule is how a device ends up in the
 * list but never chosen, or chosen but absent from the list; the rule now lives here and every caller asks it.</p>
 *
 * <p>A mixer is rejected when its name looks like a loopback, a software device or a virtual cable: those carry the
 * game's own output, so selecting one gives the agent its own voice instead of the room.</p>
 */
final class DeviceSelection {

    /** Name fragments that mean "this is not the room": system capture, loopback, software mixing, virtual cables. */
    private static final List<String> NOT_A_MICROPHONE = List.of(
            "主声音捕获",
            "软件", "software",
            "回环", "loopback",
            "立体声混音", "stereo mix",
            "虚拟音频", "virtual audio cable",
            "wave out"
    );

    /** Description fragments that mark a device worth choosing first when several are usable. */
    private static final List<String> PREFERRED_MICROPHONE = List.of(
            "high definition audio", "usb audio", "realtek",
            "logitech", "razer", "hyperx", "steelseries"
    );

    private DeviceSelection() {
    }

    /** Whether this mixer may be offered or selected as the microphone. */
    static boolean isRealInput(String mixerName) {
        if (mixerName == null) {
            return false;
        }
        String name = mixerName.toLowerCase(Locale.ROOT);
        for (String fragment : NOT_A_MICROPHONE) {
            if (name.contains(fragment.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** Whether this mixer is one of the devices to try before falling back to "anything usable". */
    static boolean isPreferredInput(String mixerName, String mixerDescription) {
        String name = mixerName == null ? "" : mixerName.toLowerCase(Locale.ROOT);
        String description = mixerDescription == null ? "" : mixerDescription.toLowerCase(Locale.ROOT);
        if (name.contains("usb")) {
            return true;
        }
        for (String fragment : PREFERRED_MICROPHONE) {
            if (description.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}