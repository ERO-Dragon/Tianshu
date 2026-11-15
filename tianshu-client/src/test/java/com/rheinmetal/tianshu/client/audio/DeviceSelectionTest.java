package com.rheinmetal.tianshu.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mixer rule, tested as a rule.
 *
 * <p>This used to be a condition written three times inside {@link AudioManager} and reachable only through a real
 * sound card. As one function it is checked here, and every caller — the automatic pick and both listings — asks the
 * same function, so they cannot disagree again.</p>
 */
class DeviceSelectionTest {

    @Test
    void loopbackAndSoftwareDevicesAreNotMicrophones() {
        assertFalse(DeviceSelection.isRealInput("立体声混音 (Realtek(R) Audio)"));
        assertFalse(DeviceSelection.isRealInput("Stereo Mix (Realtek(R) Audio)"));
        assertFalse(DeviceSelection.isRealInput("主声音捕获驱动程序"));
        assertFalse(DeviceSelection.isRealInput("VoiceMeeter Output (VB-Audio Virtual Audio Cable)"));
        assertFalse(DeviceSelection.isRealInput("CABLE Output (VB-Audio Wave Out)"));
        assertFalse(DeviceSelection.isRealInput("BlackHole Loopback"));
        assertFalse(DeviceSelection.isRealInput("Software Playback Device"));
        assertFalse(DeviceSelection.isRealInput("虚拟音频设备"));
        assertFalse(DeviceSelection.isRealInput("回环采集"), "a Chinese loopback name has to be caught too");
        assertFalse(DeviceSelection.isRealInput(null));
    }

    @Test
    void ordinaryInputDevicesAreAccepted() {
        assertTrue(DeviceSelection.isRealInput("Microphone (Logitech G Pro X)"));
        assertTrue(DeviceSelection.isRealInput("麦克风 (Realtek(R) Audio)"));
        assertTrue(DeviceSelection.isRealInput("Headset Microphone (HyperX Cloud II)"));
    }

    @Test
    void aUsbOrKnownBrandDeviceIsPreferredOverAnAnonymousOne() {
        assertTrue(DeviceSelection.isPreferredInput("Microphone (USB Audio Device)", ""));
        assertTrue(DeviceSelection.isPreferredInput("Microphone", "Realtek High Definition Audio"));
        assertTrue(DeviceSelection.isPreferredInput("麦克风", "Logitech"));
        assertFalse(DeviceSelection.isPreferredInput("Line In", "Generic audio device"));
    }
}