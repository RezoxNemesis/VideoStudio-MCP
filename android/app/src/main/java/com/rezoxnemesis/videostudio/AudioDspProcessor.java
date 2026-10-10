package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded streaming EQ, pan, dynamics, delay, algorithmic room and ceiling.
 * This is deterministic signal processing, not voice isolation or denoising.
 */
@UnstableApi
final class AudioDspProcessor extends BaseAudioProcessor {
    private final boolean enabled;
    private final boolean dspEnabled;
    private final AudioGainEnvelope gain;
    private final float clockSpeed;
    private final boolean metering;
    static final class Meter {
        final float left, right;
        final boolean limited;
        final long atMs;
        Meter(float left, float right, boolean limited, long atMs) { this.left=left; this.right=right; this.limited=limited; this.atMs=atMs; }
    }
    private volatile Meter meter = new Meter(0, 0, false, 0);
    private final AtomicLong clockVersion = new AtomicLong();
    private volatile long requestedSeekBaseUs;
    private long appliedClockVersion, seekBaseUs, frameIndex;
    private final double pan, bassDb, midDb, trebleDb, thresholdDb, ratio, ceiling;
    private Biquad[] bass, mid, treble;
    private double envelope, attack, release;
    private int outputChannels;
    private float[] frame;
    private final JSONObject ambienceSettings;
    private StreamingAudioEffects ambience;

    AudioDspProcessor(ProjectStore.Clip clip) { this(settings(clip), gainForClip(clip), 1f, false); }

    AudioDspProcessor(ProjectStore.Clip clip, float sourceClockSpeed) {
        this(settings(clip), gainForClip(clip), sourceClockSpeed, true);
    }

    AudioDspProcessor(JSONObject settings) { this(settings, null, 1f, false); }
    AudioDspProcessor(JSONObject settings, boolean metering) { this(settings, null, 1f, metering); }

    private AudioDspProcessor(JSONObject settings, AudioGainEnvelope gain, float clockSpeed, boolean metering) {
        List<String> invalid = validate(settings);
        if (!invalid.isEmpty()) throw new IllegalArgumentException(String.join(", ", invalid));
        this.gain = gain;
        this.metering = metering;
        if (!Float.isFinite(clockSpeed) || clockSpeed <= 0) throw new IllegalArgumentException("Audio clock speed must be positive");
        this.clockSpeed = clockSpeed;
        dspEnabled = settings != null && settings.optBoolean("enabled", true);
        enabled = metering || dspEnabled || gain != null && !gain.isConstantUnity();
        JSONObject values = settings == null ? new JSONObject() : settings;
        pan = dspEnabled ? values.optDouble("pan", 0) : 0;
        bassDb = dspEnabled ? values.optDouble("bassDb", 0) : 0;
        midDb = dspEnabled ? values.optDouble("midDb", 0) : 0;
        trebleDb = dspEnabled ? values.optDouble("trebleDb", 0) : 0;
        thresholdDb = values.optDouble("compressorThresholdDb", -18);
        ratio = dspEnabled ? values.optDouble("compressorRatio", 1) : 1;
        ceiling = Math.pow(10, (dspEnabled ? values.optDouble("limiterCeilingDb", 0) : 0) / 20);
        try { ambienceSettings = dspEnabled ? new JSONObject(values.toString()) : null; }
        catch (Exception error) { throw new IllegalArgumentException("Could not capture audio settings", error); }
    }

    void seekOutputTimeUs(long outputTimeUs) {
        requestedSeekBaseUs = Math.max(0L, outputTimeUs);
        clockVersion.incrementAndGet();
    }
    Meter meter() {
        Meter latest=meter;
        return android.os.SystemClock.elapsedRealtime()-latest.atMs>250 ? new Meter(0,0,false,0) : latest;
    }

    static JSONObject settings(ProjectStore.Clip clip) {
        if (clip.effects == null || !clip.effects.has("audio")) return null;
        JSONObject settings = clip.effects.optJSONObject("audio");
        if (settings == null) throw new IllegalArgumentException("audio processing settings must be an object");
        return settings;
    }
    private static AudioGainEnvelope gainForClip(ProjectStore.Clip clip) {
        if (clip == null || !Float.isFinite(clip.volume) || clip.volume < 0 || clip.volume > 2)
            throw new IllegalArgumentException("Volume must be between 0 and 2");
        return new AudioGainEnvelope(clip);
    }

    static List<String> validate(JSONObject settings) {
        ArrayList<String> errors = new ArrayList<>();
        if (settings == null) return errors;
        for (String key : new String[]{"pan", "bassDb", "midDb", "trebleDb", "compressorThresholdDb", "compressorRatio", "limiterCeilingDb"}) {
            if (!settings.has(key)) continue;
            Object raw = settings.opt(key);
            double value = raw instanceof Number ? ((Number) raw).doubleValue() : Double.NaN;
            double min = -18, max = 18;
            if ("pan".equals(key)) { min = -1; max = 1; }
            if ("compressorThresholdDb".equals(key)) { min = -60; max = 0; }
            if ("compressorRatio".equals(key)) { min = 1; max = 20; }
            if ("limiterCeilingDb".equals(key)) { min = -24; max = 0; }
            if (!Double.isFinite(value) || value < min || value > max)
                errors.add("audio." + key + " must be between " + min + " and " + max);
        }
        java.util.Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!java.util.Arrays.asList("enabled", "pan", "bassDb", "midDb", "trebleDb", "compressorThresholdDb", "compressorRatio", "limiterCeilingDb").contains(key)
                    && !StreamingAudioEffects.key(key))
                errors.add("Unsupported audio processor setting: " + key);
        }
        if (settings.has("enabled") && !(settings.opt("enabled") instanceof Boolean)) errors.add("audio.enabled must be boolean");
        StreamingAudioEffects.validate(settings, errors);
        return errors;
    }

    @Override protected AudioFormat onConfigure(AudioFormat format) throws UnhandledAudioFormatException {
        if (!enabled) return AudioFormat.NOT_SET;
        if ((format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT)
                || format.channelCount < 1 || format.channelCount > 8 || format.sampleRate < 8000)
            throw new UnhandledAudioFormatException(format);
        outputChannels = dspEnabled && format.channelCount == 1 ? 2 : format.channelCount;
        ambience = dspEnabled ? new StreamingAudioEffects(ambienceSettings, format.sampleRate, clockSpeed, outputChannels) : null;
        frame = new float[outputChannels];
        bass = new Biquad[outputChannels]; mid = new Biquad[outputChannels]; treble = new Biquad[outputChannels];
        for (int channel = 0; channel < outputChannels; channel++) {
            bass[channel] = Biquad.shelf(format.sampleRate, 120, bassDb, false);
            mid[channel] = Biquad.peak(format.sampleRate, 1000, midDb);
            treble[channel] = Biquad.shelf(format.sampleRate, 8000, trebleDb, true);
        }
        attack = Math.exp(-1 / (.010 * format.sampleRate * clockSpeed));
        release = Math.exp(-1 / (.120 * format.sampleRate * clockSpeed));
        return new AudioFormat(format.sampleRate, outputChannels, format.encoding);
    }

    @Override public void queueInput(ByteBuffer input) {
        long version = clockVersion.get();
        if (version != appliedClockVersion) {
            appliedClockVersion = version; seekBaseUs = requestedSeekBaseUs; frameIndex = 0;
            resetFilters();
        }
        input.order(ByteOrder.nativeOrder());
        int bytes = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ? Float.BYTES : Short.BYTES;
        int frameBytes = bytes * inputAudioFormat.channelCount;
        if (input.remaining() % frameBytes != 0) throw new IllegalArgumentException("Unaligned PCM frames");
        ByteBuffer output = replaceOutputBuffer(Math.multiplyExact(input.remaining() / frameBytes, bytes * outputChannels));
        float meterLeft=0, meterRight=0;
        boolean limited=false;
        while (input.hasRemaining()) {
            for (int channel = 0; channel < inputAudioFormat.channelCount; channel++) {
                float sample = bytes == Float.BYTES ? input.getFloat() : input.getShort() / 32768f;
                frame[channel] = Float.isFinite(sample) ? sample : 0;
            }
            boolean mono = outputChannels == 2 && inputAudioFormat.channelCount == 1;
            if (mono) frame[1] = frame[0];
            long outputTimeUs = seekBaseUs + (long) (frameIndex * 1000000. / inputAudioFormat.sampleRate / clockSpeed);
            double authoredGain = gain == null ? 1 : gain.at(outputTimeUs);
            frameIndex++;
            double peak = 0;
            for (int channel = 0; channel < outputChannels; channel++) {
                double sample = treble[channel].at(mid[channel].at(bass[channel].at(frame[channel])));
                if (!Double.isFinite(sample)) sample = 0;
                if (channel == 0) sample *= mono ? Math.cos((pan + 1) * Math.PI / 4) : pan <= 0 ? 1 : Math.cos(pan * Math.PI / 2);
                if (channel == 1) sample *= mono ? Math.sin((pan + 1) * Math.PI / 4) : pan >= 0 ? 1 : Math.cos(-pan * Math.PI / 2);
                frame[channel] = (float) sample;
            }
            if (ambience != null) ambience.process(frame);
            for (float sample : frame) peak = Math.max(peak, Math.abs(sample));
            double coefficient = peak > envelope ? attack : release;
            envelope = coefficient * envelope + (1 - coefficient) * peak;
            double levelDb = 20 * Math.log10(Math.max(1e-12, envelope));
            double reductionDb = levelDb > thresholdDb ? (thresholdDb + (levelDb - thresholdDb) / ratio) - levelDb : 0;
            // Clip volume/fades govern the audible output, including delayed
            // samples captured before a fade. Limit once after that final gain.
            double compressorGain = Math.pow(10, reductionDb / 20);
            for (int channel = 0; channel < outputChannels; channel++) {
                double gained = frame[channel] * compressorGain * authoredGain;
                if (Math.abs(gained)>ceiling) limited=true;
                double sample = Math.max(-ceiling, Math.min(ceiling, gained));
                if (channel==0) meterLeft=Math.max(meterLeft,(float)Math.abs(sample));
                if (channel==1) meterRight=Math.max(meterRight,(float)Math.abs(sample));
                if (bytes == Float.BYTES) output.putFloat((float) sample);
                else output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * 32768))));
            }
        }
        if (metering) meter=new Meter(meterLeft, outputChannels==1 ? meterLeft : meterRight, limited, android.os.SystemClock.elapsedRealtime());
        output.flip();
    }

    @Override protected void onFlush() {
        appliedClockVersion = clockVersion.get(); seekBaseUs = requestedSeekBaseUs; frameIndex = 0;
        resetFilters();
    }

    private void resetFilters() {
        envelope = 0;
        meter = new Meter(0,0,false,0);
        if (bass != null) for (int i = 0; i < bass.length; i++) { bass[i].reset(); mid[i].reset(); treble[i].reset(); }
        if (ambience != null) ambience.reset();
    }

    @Override protected void onReset() {
        ambience = null; frame = null; bass = mid = treble = null;
        meter = new Meter(0,0,false,0);
    }

    private static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double z1, z2;
        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0;
        }
        double at(double sample) {
            double result = b0 * sample + z1;
            z1 = b1 * sample - a1 * result + z2;
            z2 = b2 * sample - a2 * result;
            if (!Double.isFinite(result) || !Double.isFinite(z1) || !Double.isFinite(z2)) { reset(); return 0; }
            return result;
        }
        void reset() { z1 = 0; z2 = 0; }
        static Biquad peak(int rate, double frequency, double db) {
            double a = Math.pow(10, db / 40), omega = 2 * Math.PI * Math.min(frequency, rate * .45) / rate;
            double cosine = Math.cos(omega), alpha = Math.sin(omega) / (2 * .70710678);
            return new Biquad(1 + alpha * a, -2 * cosine, 1 - alpha * a, 1 + alpha / a, -2 * cosine, 1 - alpha / a);
        }
        static Biquad shelf(int rate, double frequency, double db, boolean high) {
            double a = Math.pow(10, db / 40), omega = 2 * Math.PI * Math.min(frequency, rate * .45) / rate;
            double c = Math.cos(omega), beta = Math.sin(omega) * Math.sqrt(a) * Math.sqrt(2);
            if (high) return new Biquad(a * ((a + 1) + (a - 1) * c + beta), -2 * a * ((a - 1) + (a + 1) * c),
                    a * ((a + 1) + (a - 1) * c - beta), (a + 1) - (a - 1) * c + beta,
                    2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - beta);
            return new Biquad(a * ((a + 1) - (a - 1) * c + beta), 2 * a * ((a - 1) - (a + 1) * c),
                    a * ((a + 1) - (a - 1) * c - beta), (a + 1) + (a - 1) * c + beta,
                    -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - beta);
        }
    }
}
