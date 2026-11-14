package com.rheinmetal.tianshu.function.intake;

public interface AsrModuleRuntimeControl {
    void releaseInputResources();

    void reconfigureAudioPipeline();

    /**
     * Debug only: makes one text turn look like a recognised sentence.
     *
     * <p>It goes through the repair lane and the arbiter exactly as speech does, which is what lets the
     * settings page exercise IR and IA on a machine with no usable microphone. The transcript itself is
     * the caller's: the module does not invent one.</p>
     *
     * @return false when the text is blank or the module is not bound
     */
    boolean injectRecognizedText(String text);
}