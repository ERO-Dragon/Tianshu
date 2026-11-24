package com.rheinmetal.tianshu.protocol;

/**
 * A payload that carries a large body — audio, a document, an image — and can say how large.
 *
 * <p>The lifecycle history keeps envelopes so a trace can be inspected after the fact. For most payloads that is a few
 * fields, but a synthesized sentence is hundreds of kilobytes, and keeping every one of them for thirty minutes is a
 * memory budget nobody chose. A payload that reports its size lets the history apply a byte budget instead of guessing
 * from counts.</p>
 *
 * <p>The default is zero: a payload that does not opt in is assumed small, which keeps every existing payload working
 * unchanged.</p>
 */
public interface SizedPayload {

    /** How many bytes this payload holds, used by the history's retention budget. */
    int retainedBytes();
}