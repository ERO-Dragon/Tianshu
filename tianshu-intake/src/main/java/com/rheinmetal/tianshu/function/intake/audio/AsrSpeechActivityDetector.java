package com.rheinmetal.tianshu.function.intake.audio;

/**
 * Voice activity detector for the continuous listening mode.
 *
 * <h2>Deliberately simple</h2>
 * <p>The whole model is three rules:</p>
 * <ol>
 *   <li>the noise floor is a slow average of what the microphone hears;</li>
 *   <li>speech starts when the level exceeds the floor times {@code START_RATIO};</li>
 *   <li>speech ends when the level stays below the floor times {@code STOP_RATIO} for the hold time.</li>
 * </ol>
 *
 * <p>Earlier revisions layered calibration windows, dispersion estimates, envelope modulation and
 * self-correction on top of this. Each layer interacted with the others, which made the behaviour
 * hard to predict and hard to tune. The simple version is easier to reason about and its two
 * ratios can be adjusted by hand. It is marked experimental in the settings.</p>
 *
 * <h2>Tuning</h2>
 * <ul>
 *   <li>{@code START_RATIO} is the one that matters most. Raise it if ambience is being taken for
 *       speech; lower it if quiet speech is missed. It also sets the required headroom between the
 *       voice and the room, so a device whose voice is only just above its own noise needs a small
 *       value, and no threshold rule can do better than the physical separation allows.</li>
 *   <li>{@code STOP_RATIO} must stay below {@code START_RATIO}. The gap between them is the
 *       hysteresis that stops the state from flapping; a wider gap ends segments more readily.</li>
 *   <li>{@code FLOOR_ADAPT_PER_SECOND} controls how fast the floor follows a change in the room.
 *       The floor only follows <em>quiet</em> input, so a voice cannot lift it.</li>
 * </ul>
 */
public final class AsrSpeechActivityDetector {
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int DEFAULT_SAMPLE_RATE = 16000;

    /** Level below which nothing counts as audible at all. */
    /**
     * Level below which nothing counts as audible at all. Kept very low so the ratios, not this
     * constant, decide the outcome on every device.
     */
    private static final double MIN_LEVEL = 1.0E-4D;
    /** Floor used before the opening measurement has finished. */
    private static final double INITIAL_FLOOR = 0.002D;
    /**
     * The detector measures the room before it decides anything. Without this the floor starts at a
     * nominal constant, and on a device whose idle level is louder than that constant the very first
     * frame already looks like speech, so the thresholds never get a chance to be learned.
     */
    private static final long STARTUP_MEASURE_MILLIS = 500L;

    /** Speech starts above floor * this. Raise it to make the detector less eager. */
    private static final double START_RATIO = 1.15D;
    /** Speech ends below floor * this. Must stay below START_RATIO. */
    private static final double STOP_RATIO = 1.05D;

    /** How fast the floor follows quiet input, per second. */
    private static final double FLOOR_ADAPT_PER_SECOND = 2.0D;
    /** The floor never sits below this, so a silent room cannot drive it to zero. */
    private static final double FLOOR_FLOOR = 1.0E-4D;
    /** The floor never sits above this. */
    private static final double FLOOR_CEILING = 0.9D;

    /** Shortest segment worth reporting; anything briefer is discarded as a click or a blip. */
    private static final long MIN_SEGMENT_MILLIS = 250L;
    /** Silence that ends a segment. */
    private static final long SILENCE_TO_STOP_MILLIS = 500L;

    private final int sampleRate;
    private final AsrSpeechActivityListener listener;

    private double floor;
    private boolean inSegment;
    private boolean announced;
    private long segmentMillis;
    private long silentMillis;
    private long measuredMillis;
    private double measuredSquareSum;
    private int measuredFrames;
    private boolean measured;
    private long sessionId;
    private double latestRms;
    private long latestOccurredAtMillis;

    public AsrSpeechActivityDetector(AsrSpeechActivityListener listener) {
        this(DEFAULT_SAMPLE_RATE, listener);
    }

    public AsrSpeechActivityDetector(int sampleRate, AsrSpeechActivityListener listener) {
        this.sampleRate = sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
        this.listener = listener == null ? AsrSpeechActivityListener.noop() : listener;
        reset();
    }

    public synchronized void start(long sessionId) {
        reset();
        this.sessionId = Math.max(0L, sessionId);
    }

    public synchronized void accept(byte[] pcm16le) {
        if (pcm16le == null || pcm16le.length < BYTES_PER_SAMPLE || sessionId <= 0L) {
            return;
        }
        double rms = rms(pcm16le);
        long chunkMillis = chunkMillis(pcm16le);

        latestRms = rms;
        latestOccurredAtMillis = System.currentTimeMillis();

        if (!measured) {
            measureRoom(rms, chunkMillis);
            return;
        }
        if (inSegment) {
            acceptWhileInSegment(rms, chunkMillis);
            return;
        }
        acceptWhileIdle(rms, chunkMillis);
    }

    public synchronized void stop() {
        if (announced && sessionId > 0L) {
            listener.onSpeechActivity(false, sessionId, System.currentTimeMillis());
        }
        reset();
    }

    /** Clears the current segment but keeps the learned floor for the next one. */
    public synchronized void resetForSegmentBoundary(long sessionId) {
        if (announced && this.sessionId > 0L) {
            listener.onSpeechActivity(false, this.sessionId, System.currentTimeMillis());
        }
        inSegment = false;
        announced = false;
        segmentMillis = 0L;
        silentMillis = 0L;
        this.sessionId = Math.max(0L, sessionId);
    }

    /** True once a segment has been reported, and false again once it closes. */
    public synchronized boolean speaking() {
        return announced;
    }

    public synchronized AsrSpeechActivitySnapshot snapshot() {
        return new AsrSpeechActivitySnapshot(
                latestRms,
                floor,
                startThreshold(),
                stopThreshold(),
                announced,
                sessionId,
                latestOccurredAtMillis
        );
    }

    private void reset() {
        floor = INITIAL_FLOOR;
        inSegment = false;
        announced = false;
        segmentMillis = 0L;
        silentMillis = 0L;
        measuredMillis = 0L;
        measuredSquareSum = 0.0D;
        measuredFrames = 0;
        measured = false;
        sessionId = 0L;
        latestRms = 0.0D;
        latestOccurredAtMillis = 0L;
    }

    /** Averages the opening frames to learn the room, then starts deciding. */
    private void measureRoom(double rms, long chunkMillis) {
        measuredSquareSum += rms * rms;
        measuredFrames++;
        floor = clamp(Math.sqrt(measuredSquareSum / Math.max(1, measuredFrames)), FLOOR_FLOOR, FLOOR_CEILING);
        measuredMillis += chunkMillis;
        if (measuredMillis >= STARTUP_MEASURE_MILLIS) {
            measured = true;
        }
    }

    private void acceptWhileIdle(double rms, long chunkMillis) {
        if (rms >= startThreshold()) {
            enterSegment(chunkMillis);
            return;
        }
        adaptFloor(rms, chunkMillis);
    }

    /**
     * Opens a candidate segment. Nothing is reported yet: the caller is told only once the sound has
     * lasted long enough to be worth recognising, so a click never reaches the recogniser.
     */
    private void enterSegment(long chunkMillis) {
        inSegment = true;
        announced = false;
        segmentMillis = chunkMillis;
        silentMillis = 0L;
        announceIfLongEnough();
    }

    private void acceptWhileInSegment(double rms, long chunkMillis) {
        if (rms >= stopThreshold()) {
            // Only audible time counts toward the segment, so a click followed by a long silence is
            // never long enough to be reported.
            segmentMillis += chunkMillis;
            silentMillis = 0L;
        } else {
            silentMillis += chunkMillis;
        }
        // The floor keeps following quiet input even inside a segment, so a segment opened by a
        // level that turns out to be ambience can still fall below the release threshold.
        adaptFloor(rms, chunkMillis);
        announceIfLongEnough();
        if (silentMillis >= SILENCE_TO_STOP_MILLIS) {
            closeSegment();
        }
    }

    /** Reports the segment once it is long enough, and only once. */
    private void announceIfLongEnough() {
        if (!announced && segmentMillis >= MIN_SEGMENT_MILLIS) {
            announced = true;
            listener.onSpeechActivity(true, sessionId, System.currentTimeMillis());
        }
    }

    private void closeSegment() {
        boolean wasAnnounced = announced;
        inSegment = false;
        announced = false;
        segmentMillis = 0L;
        silentMillis = 0L;
        if (wasAnnounced) {
            listener.onSpeechActivity(false, sessionId, System.currentTimeMillis());
        }
    }

    /**
     * Moves the floor toward the input, but only for input that is below the start threshold. A
     * voice therefore cannot raise the floor onto its own level, which is what previously dragged
     * the thresholds up until they met the voice.
     */
    private void adaptFloor(double rms, long chunkMillis) {
        if (rms >= startThreshold()) {
            return;
        }
        double rate = 1.0D - Math.exp(-FLOOR_ADAPT_PER_SECOND * (chunkMillis / 1000.0D));
        floor = clamp(floor + (rms - floor) * rate, FLOOR_FLOOR, FLOOR_CEILING);
    }

    private double startThreshold() {
        return Math.max(MIN_LEVEL, floor * START_RATIO);
    }

    private double stopThreshold() {
        return Math.max(MIN_LEVEL, floor * STOP_RATIO);
    }

    private long chunkMillis(byte[] pcm16le) {
        int samples = pcm16le.length / BYTES_PER_SAMPLE;
        return Math.max(1L, Math.round(samples * 1000.0D / (double) sampleRate));
    }

    private static double rms(byte[] pcm16le) {
        int samples = pcm16le.length / BYTES_PER_SAMPLE;
        if (samples <= 0) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int index = 0; index < samples; index++) {
            int offset = index * BYTES_PER_SAMPLE;
            int low = pcm16le[offset] & 0xFF;
            int high = pcm16le[offset + 1];
            double normalized = (short) ((high << 8) | low) / 32768.0D;
            sum += normalized * normalized;
        }
        return Math.sqrt(sum / samples);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
