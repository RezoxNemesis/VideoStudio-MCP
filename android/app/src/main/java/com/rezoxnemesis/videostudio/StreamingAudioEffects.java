package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Per-clip streaming dynamics and ambience. Histories are reset at seeks/cuts;
 * tails stay inside the authored clip span. This is an algorithmic room, not a
 * measured impulse response or a speech-isolation model. */
final class StreamingAudioEffects {
    static final String[] KEYS = {"gateThresholdDb", "gateFloorDb", "gateAttackMs", "gateReleaseMs", "gateHoldMs",
            "deEsserAmount", "deEsserThresholdDb", "deEsserFrequencyHz", "delayMix", "delayMs", "delayFeedback",
            "delayPingPong", "reverbMix", "reverbRoom", "reverbDamping"};
    private static final long MAX_HISTORY_BYTES = 16L * 1024L * 1024L;
    private final boolean gateEnabled, pingPong;
    private final double gateThreshold, gateFloor, gateAttack, gateRelease, levelAttack, levelRelease;
    private final long holdFrames;
    private long held;
    private double gateEnvelope, gateGain = 1, highEnvelope;
    private final double deEsserAmount, deEsserThreshold, highAttack, highRelease;
    private final HighPass[] highPass;
    private final double delayMix, feedback, reverbMix;
    private final float[][] delay;
    private final float[] delayed;
    private int delayPosition;
    private final Room[] rooms;

    StreamingAudioEffects(JSONObject settings, int sampleRate, float clockSpeed, int channels) {
        double effectiveRate = sampleRate * (double) clockSpeed;
        if (!Double.isFinite(effectiveRate) || effectiveRate <= 0 || channels < 1 || channels > 8)
            throw new IllegalArgumentException("Invalid streaming audio clock or channels");
        JSONObject s = settings == null ? new JSONObject() : settings;
        double thresholdDb = s.optDouble("gateThresholdDb", -90);
        gateEnabled = thresholdDb > -90;
        gateThreshold = amplitude(thresholdDb);
        gateFloor = amplitude(s.optDouble("gateFloorDb", -80));
        gateGain = gateEnabled ? gateFloor : 1;
        gateAttack = coefficient(s.optDouble("gateAttackMs", 5), effectiveRate);
        gateRelease = coefficient(s.optDouble("gateReleaseMs", 100), effectiveRate);
        levelAttack = coefficient(1, effectiveRate);
        levelRelease = coefficient(20, effectiveRate);
        holdFrames = Math.round(s.optDouble("gateHoldMs", 40) * effectiveRate / 1000);
        deEsserAmount = s.optDouble("deEsserAmount", 0);
        deEsserThreshold = amplitude(s.optDouble("deEsserThresholdDb", -24));
        highAttack = coefficient(1, effectiveRate);
        highRelease = coefficient(50, effectiveRate);
        highPass = deEsserAmount > 0 ? new HighPass[channels] : null;
        if (highPass != null) for (int channel = 0; channel < channels; channel++)
            highPass[channel] = new HighPass(sampleRate, s.optDouble("deEsserFrequencyHz", 6000));
        delayMix = s.optDouble("delayMix", 0);
        feedback = s.optDouble("delayFeedback", .3);
        pingPong = s.optBoolean("delayPingPong", false);
        reverbMix = s.optDouble("reverbMix", 0);
        double roomSize = s.optDouble("reverbRoom", .5), damping = s.optDouble("reverbDamping", .5);
        int delayLength = delayMix > 0 ? frames(s.optDouble("delayMs", 300), effectiveRate) : 0;
        long historyFrames = (long) delayLength * channels;
        int[][] roomLengths = reverbMix > 0 ? new int[channels][] : null;
        if (roomLengths != null) for (int channel = 0; channel < channels; channel++) {
            roomLengths[channel] = Room.lengths(effectiveRate, roomSize, channel);
            for (int length : roomLengths[channel]) historyFrames = Math.addExact(historyFrames, length);
        }
        if (historyFrames * Float.BYTES > MAX_HISTORY_BYTES)
            throw new IllegalArgumentException("Delay/reverb history exceeds the 16 MiB per-clip budget at this playback speed and audio format");
        delay = delayLength > 0 ? new float[channels][delayLength] : null;
        delayed = new float[channels];
        rooms = roomLengths == null ? null : new Room[channels];
        if (rooms != null) for (int channel = 0; channel < channels; channel++)
            rooms[channel] = new Room(roomLengths[channel], .68 + .25 * roomSize, .1 + .8 * damping);
    }

    void process(float[] frame) {
        double peak = 0;
        for (float sample : frame) peak = Math.max(peak, Math.abs(sample));
        if (gateEnabled) {
            double c = peak > gateEnvelope ? levelAttack : levelRelease;
            gateEnvelope = c * gateEnvelope + (1 - c) * peak;
            boolean open = gateEnvelope >= gateThreshold;
            if (open) held = holdFrames;
            else if (held > 0) held--;
            double target = open || held > 0 ? 1 : gateFloor;
            c = target > gateGain ? gateAttack : gateRelease;
            gateGain = c * gateGain + (1 - c) * target;
            for (int channel = 0; channel < frame.length; channel++) frame[channel] *= gateGain;
        }
        if (highPass != null) {
            double highPeak = 0;
            for (int channel = 0; channel < frame.length; channel++) {
                delayed[channel] = (float) highPass[channel].at(frame[channel]);
                highPeak = Math.max(highPeak, Math.abs(delayed[channel]));
            }
            double c = highPeak > highEnvelope ? highAttack : highRelease;
            highEnvelope = c * highEnvelope + (1 - c) * highPeak;
            double removal = 1 - Math.pow(deEsserThreshold / Math.max(deEsserThreshold, highEnvelope), deEsserAmount);
            removal = Math.max(0, Math.min(.9, removal));
            for (int channel = 0; channel < frame.length; channel++) frame[channel] -= delayed[channel] * removal;
        }
        if (delay != null) {
            for (int channel = 0; channel < frame.length; channel++) delayed[channel] = delay[channel][delayPosition];
            for (int channel = 0; channel < frame.length; channel++) {
                int returnChannel = pingPong && frame.length == 2 ? 1 - channel : channel;
                delay[channel][delayPosition] = finite(frame[channel] + delayed[returnChannel] * feedback);
                frame[channel] = finite(frame[channel] * (1 - delayMix) + delayed[channel] * delayMix);
            }
            delayPosition = (delayPosition + 1) % delay[0].length;
        }
        if (rooms != null) for (int channel = 0; channel < frame.length; channel++)
            frame[channel] = finite(frame[channel] * (1 - reverbMix) + rooms[channel].at(frame[channel]) * reverbMix);
    }

    void reset() {
        held = 0; gateEnvelope = highEnvelope = 0; gateGain = gateEnabled ? gateFloor : 1; delayPosition = 0;
        java.util.Arrays.fill(delayed, 0);
        if (highPass != null) for (HighPass filter : highPass) filter.reset();
        if (delay != null) for (float[] channel : delay) java.util.Arrays.fill(channel, 0);
        if (rooms != null) for (Room room : rooms) room.reset();
    }

    static boolean key(String key) { return java.util.Arrays.asList(KEYS).contains(key); }
    static void validate(JSONObject s, java.util.List<String> errors) {
        if (s == null) return;
        range(s, errors, "gateThresholdDb", -90, 0); range(s, errors, "gateFloorDb", -96, 0);
        range(s, errors, "gateAttackMs", 1, 100); range(s, errors, "gateReleaseMs", 10, 1000);
        range(s, errors, "gateHoldMs", 0, 500); range(s, errors, "deEsserAmount", 0, 1);
        range(s, errors, "deEsserThresholdDb", -48, 0); range(s, errors, "deEsserFrequencyHz", 2000, 10000);
        range(s, errors, "delayMix", 0, 1); range(s, errors, "delayMs", 1, 2000);
        range(s, errors, "delayFeedback", 0, .85); range(s, errors, "reverbMix", 0, 1);
        range(s, errors, "reverbRoom", 0, 1); range(s, errors, "reverbDamping", 0, 1);
        if (s.has("delayPingPong") && !(s.opt("delayPingPong") instanceof Boolean))
            errors.add("audio.delayPingPong must be boolean");
    }
    private static void range(JSONObject s, java.util.List<String> errors, String key, double min, double max) {
        if (!s.has(key)) return;
        Object raw = s.opt(key);
        double value = raw instanceof Number ? ((Number) raw).doubleValue() : Double.NaN;
        if (!Double.isFinite(value) || value < min || value > max)
            errors.add("audio." + key + " must be a number between " + min + " and " + max);
    }
    private static double amplitude(double db) { return Math.pow(10, db / 20); }
    private static double coefficient(double ms, double rate) { return Math.exp(-1 / (ms * rate / 1000)); }
    private static int frames(double ms, double rate) {
        double count = Math.max(1, Math.round(ms * rate / 1000));
        if (count > MAX_HISTORY_BYTES / Float.BYTES) throw new IllegalArgumentException("Audio history exceeds the bounded buffer size");
        return (int) count;
    }
    private static float finite(double value) { return Double.isFinite(value) && Math.abs(value) <= Float.MAX_VALUE ? (float) value : 0; }

    private static final class HighPass {
        final double b0, b1, b2, a1, a2;
        double z1, z2;
        HighPass(double rate, double frequency) {
            double w = 2 * Math.PI * Math.min(frequency, rate * .45) / rate;
            double c = Math.cos(w), alpha = Math.sin(w) / (2 * .70710678), a0 = 1 + alpha;
            b0 = (1 + c) / (2 * a0); b1 = -(1 + c) / a0; b2 = b0;
            a1 = -2 * c / a0; a2 = (1 - alpha) / a0;
        }
        double at(double input) {
            double output = b0 * input + z1;
            z1 = b1 * input - a1 * output + z2; z2 = b2 * input - a2 * output;
            if (!Double.isFinite(output) || !Double.isFinite(z1) || !Double.isFinite(z2)) { reset(); return 0; }
            return output;
        }
        void reset() { z1 = z2 = 0; }
    }
    private static final class Room {
        final Comb[] combs = new Comb[4];
        final AllPass[] allPasses = new AllPass[2];
        Room(int[] lengths, double feedback, double damping) {
            for (int i = 0; i < combs.length; i++) combs[i] = new Comb(lengths[i], feedback, damping);
            for (int i = 0; i < allPasses.length; i++) allPasses[i] = new AllPass(lengths[i + 4]);
        }
        static int[] lengths(double rate, double size, int channel) {
            double[] ms = {29.7, 37.1, 41.1, 43.7, 5, 1.7};
            int[] lengths = new int[ms.length];
            for (int i = 0; i < ms.length; i++) lengths[i] = frames((ms[i] + (channel % 2) * .2) * (.55 + .8 * size), rate);
            return lengths;
        }
        double at(double input) {
            double wet = 0;
            for (Comb comb : combs) wet += comb.at(input) * .25;
            for (AllPass allPass : allPasses) wet = allPass.at(wet);
            return wet;
        }
        void reset() { for (Comb c : combs) c.reset(); for (AllPass a : allPasses) a.reset(); }
    }
    private static final class Comb {
        final float[] history; final double feedback, damping;
        int position; double filtered;
        Comb(int length, double feedback, double damping) { history = new float[length]; this.feedback = feedback; this.damping = damping; }
        double at(double input) {
            double delayed = history[position]; filtered = delayed * (1 - damping) + filtered * damping;
            history[position] = finite(input + filtered * feedback); position = (position + 1) % history.length;
            return delayed;
        }
        void reset() { java.util.Arrays.fill(history, 0); position = 0; filtered = 0; }
    }
    private static final class AllPass {
        final float[] history; int position;
        AllPass(int length) { history = new float[length]; }
        double at(double input) {
            double output = history[position] - .5 * input;
            history[position] = finite(input + .5 * output);
            position = (position + 1) % history.length; return output;
        }
        void reset() { java.util.Arrays.fill(history, 0); position = 0; }
    }
}
