package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaAudioTimelineTest {
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    private static OmegaAudioTimeline.AudioClip clip(String id, OmegaAudioTimeline.Kind kind,
                                                     int channels, int start, int end,
                                                     long timelineStartUs, double gain,
                                                     boolean muted) {
        return new OmegaAudioTimeline.AudioClip(id, "asset_" + id, HASH, kind,
                48000, channels, start, end, timelineStartUs, gain, muted);
    }

    private static OmegaAudioTimeline.ProjectAudio project(int fps, int frameCount,
                                                            OmegaAudioTimeline.AudioClip... clips) {
        return new OmegaAudioTimeline.ProjectAudio("sketch_7", 5, fps, frameCount,
                Arrays.asList(clips));
    }

    private static OmegaAudioTimeline.Cue cue(long start, long end, String shape,
                                               OmegaAudioTimeline.CueOrigin origin) {
        return new OmegaAudioTimeline.Cue(start, end, shape, 1, origin);
    }

    @Test public void originalMonoWaveformRetainsHalfOpenRmsAndSilence() {
        short[] pcm = new short[4000];
        Arrays.fill(pcm, 0, 2000, (short) 16384);
        List<OmegaAudioTimeline.Envelope> envelope =
                OmegaAudioTimeline.waveform(pcm, 48000, 24, 3);
        assertEquals(3, envelope.size());
        assertEquals(0, envelope.get(0).startSample);
        assertEquals(2000, envelope.get(0).endSample);
        assertEquals(.5, envelope.get(0).rms, 1e-10);
        assertFalse(envelope.get(0).silent(.2));
        assertEquals(2000, envelope.get(1).startSample);
        assertEquals(4000, envelope.get(1).endSample);
        assertEquals(0, envelope.get(1).peak, 0);
        assertTrue(envelope.get(1).silent(.01));
        assertEquals(4000, envelope.get(2).startSample);
        assertEquals(4000, envelope.get(2).endSample);
        assertThrows(UnsupportedOperationException.class, () -> envelope.clear());
    }

    @Test public void sampleAndMicrosecondFrameLookupsUseBoundedClocks() {
        assertEquals(0, OmegaAudioTimeline.frameForSample(0, 48000, 24, 24));
        assertEquals(1, OmegaAudioTimeline.frameForSample(2000, 48000, 24, 24));
        assertEquals(12, OmegaAudioTimeline.frameForSample(24000, 48000, 24, 24));
        assertEquals(23, OmegaAudioTimeline.frameForSample(Long.MAX_VALUE, 48000, 24, 24));
        assertEquals(12, OmegaAudioTimeline.frameForTimeUs(500000, 24, 24));
        assertEquals(23, OmegaAudioTimeline.frameForTimeUs(Long.MAX_VALUE, 24, 24));
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.frameForTimeUs(-1, 24, 24));
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.frameForSample(-1, 48000, 24, 24));
    }

    @Test public void fractionalClockDoesNotAccumulateDriftAtAllSupportedFps() {
        for (int fps : new int[]{24, 30, 60}) {
            short[] pcm = new short[44100];
            List<OmegaAudioTimeline.Envelope> bins =
                    OmegaAudioTimeline.waveform(pcm, 44100, fps, fps);
            int last = 0;
            for (int f = 0; f < fps; f++) {
                assertEquals(last, bins.get(f).startSample);
                assertEquals((long) (f + 1) * 44100 / fps, bins.get(f).endSample);
                last = bins.get(f).endSample;
            }
            assertEquals(44100, last);
        }
    }

    @Test public void malformedClockAndBuffersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.waveform(new short[1], 48000, 25, 12));
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.waveform(null, 48000, 30, 12));
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.waveform(new short[1], 7999, 30, 12));
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.waveform(new short[1], 48000, 60, 1201));
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.waveform(new short[1], 48000, 60, 0));
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.waveform(new short[1], 48000, 24, 1).get(0).silent(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> OmegaAudioTimeline.waveform(new short[1], 48000, 24, 1).get(0).silent(1.01));
    }

    @Test public void projectPreservesExactSourceTimingAndGain() {
        OmegaAudioTimeline.AudioClip a = clip("dialogue", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 1000, 49000, 0, 1, false);
        OmegaAudioTimeline.AudioClip b = clip("fx", OmegaAudioTimeline.Kind.SFX,
                1, 0, 2400, 1000000, .5, false);
        OmegaAudioTimeline.ProjectAudio p = project(30, 60, a, b);
        assertSame(a, p.clip("dialogue"));
        assertEquals(1000000L, a.durationUs());
        assertEquals(1050000L, b.endUs());
        assertEquals(Arrays.asList(a), OmegaAudioTimeline.activeAt(p, 300000));
        assertEquals(Arrays.asList(b), OmegaAudioTimeline.activeAt(p, 1025000));
        assertTrue(OmegaAudioTimeline.activeAt(p, 1800000).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> p.clips.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> OmegaAudioTimeline.activeAt(p, 300000).clear());
    }

    @Test public void invalidClipSourceOwnershipAndNumericsFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.AudioClip("bad/uri", "asset", HASH,
                        OmegaAudioTimeline.Kind.DIALOGUE, 48000, 1, 0, 240, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.AudioClip("x", "asset", "not-a-sha",
                        OmegaAudioTimeline.Kind.DIALOGUE, 48000, 1, 0, 240, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.AudioClip("x", "asset", HASH,
                        OmegaAudioTimeline.Kind.DIALOGUE, 48000, 0, 0, 240, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.AudioClip("x", "asset", HASH,
                        OmegaAudioTimeline.Kind.DIALOGUE, 48000, 1, 0, 0, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.AudioClip("x", "asset", HASH,
                        OmegaAudioTimeline.Kind.DIALOGUE, 48000, 1, 0, 240, 0, Double.NaN, false));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.AudioClip("x", "asset", HASH,
                        OmegaAudioTimeline.Kind.DIALOGUE, 48000, 1, 0, 240, -1, 1, false));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.AudioClip("x", "asset", HASH,
                        OmegaAudioTimeline.Kind.DIALOGUE, 48000, 1, 0, 240, 0, 2.01, false));
    }

    @Test public void projectRejectsOverflowDuplicateLanesAndRevisions() {
        OmegaAudioTimeline.AudioClip one = clip("same", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 0, 48000, 0, 1, false);
        OmegaAudioTimeline.AudioClip overflow = clip("over", OmegaAudioTimeline.Kind.MUSIC,
                1, 0, 48000, 500000, 1, false);
        assertThrows(IllegalArgumentException.class, () -> project(30, 30, overflow));
        assertThrows(IllegalArgumentException.class, () -> project(30, 30, one, one));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.ProjectAudio("sketch_7", -1, 30, 30, List.of(one)));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.ProjectAudio("sketch_7", 0, 30, 30, null));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.ProjectAudio("sketch_7", 0, 30, 30,
                        Collections.nCopies(33, one)));
        assertThrows(IllegalArgumentException.class, () ->
                OmegaAudioTimeline.activeAt(project(30, 30, one), 1000000));
        assertThrows(IllegalArgumentException.class, () ->
                OmegaAudioTimeline.activeAt(project(30, 30, one), -1));
    }

    @Test public void monoEnvelopeUsesOriginalSamplesAfterTrimming() {
        short[] pcm = new short[7200];
        Arrays.fill(pcm, 0, 2400, (short) 0);
        Arrays.fill(pcm, 2400, 4800, (short) 16384);
        OmegaAudioTimeline.AudioClip clip = clip("voice", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 2400, 4800, 0, 1, false);
        OmegaAudioTimeline.ProjectAudio p = project(24, 3, clip);
        List<OmegaAudioTimeline.Envelope> bins = OmegaAudioTimeline.waveformForClip(
                p, "voice", "asset_voice", HASH, 48000, 1, pcm);
        assertEquals(2400, bins.get(0).startSample);
        assertEquals(4400, bins.get(0).endSample);
        assertEquals(.5, bins.get(0).peak, 0);
        assertEquals(.5, bins.get(0).rms, 1e-10);
        assertEquals(4400, bins.get(1).startSample);
        assertEquals(4800, bins.get(1).endSample);
        assertEquals(0, bins.get(2).peak, 0);
        assertThrows(UnsupportedOperationException.class, () -> bins.add(bins.get(0)));
    }

    @Test public void stereoWaveformsPreserveOppositePhaseEnergy() {
        short[] stereo = new short[9600];
        for (int i = 0; i < stereo.length; i += 2) {
            stereo[i] = 16384;
            stereo[i + 1] = -16384;
        }
        OmegaAudioTimeline.AudioClip c = clip("two", OmegaAudioTimeline.Kind.DIALOGUE,
                2, 0, 4800, 0, 1, false);
        List<OmegaAudioTimeline.Envelope> bins = OmegaAudioTimeline.waveformForClip(
                project(30, 6, c), "two", "asset_two", HASH, 48000, 2, stereo);
        assertEquals(.5, bins.get(0).rms, 1e-10);
        assertEquals(.5, bins.get(1).peak, 1e-10);
        assertEquals(1600, bins.get(0).endSample);
    }

    @Test public void gainAndMuteAffectPreviewEnvelopeWithoutChangingSource() {
        short[] pcm = new short[4800];
        Arrays.fill(pcm, (short) 16384);
        OmegaAudioTimeline.AudioClip loud = clip("gain", OmegaAudioTimeline.Kind.MUSIC,
                1, 0, 4800, 0, 1.5, false);
        OmegaAudioTimeline.AudioClip muted = clip("muted", OmegaAudioTimeline.Kind.MUSIC,
                1, 0, 4800, 0, 1.5, true);
        List<OmegaAudioTimeline.Envelope> a = OmegaAudioTimeline.waveformForClip(
                project(30, 6, loud), "gain", "asset_gain", HASH, 48000, 1, pcm);
        List<OmegaAudioTimeline.Envelope> b = OmegaAudioTimeline.waveformForClip(
                project(30, 6, muted), "muted", "asset_muted", HASH, 48000, 1, pcm);
        assertEquals(.75, a.get(0).peak, 1e-10);
        assertEquals(.75, a.get(0).rms, 1e-10);
        assertEquals(0, b.get(0).peak, 0);
        assertEquals(0, b.get(0).rms, 0);
        assertEquals(16384, pcm[0]);
    }

    @Test public void offsetClipProvidesTrueSilenceBeforeStartAndAfterEnd() {
        short[] pcm = new short[48000];
        Arrays.fill(pcm, (short) 16000);
        OmegaAudioTimeline.AudioClip c = clip("late", OmegaAudioTimeline.Kind.SFX,
                1, 0, 4800, 150000, 1, false);
        List<OmegaAudioTimeline.Envelope> bins = OmegaAudioTimeline.waveformForClip(
                project(60, 30, c), "late", "asset_late", HASH, 48000, 1, pcm);
        assertTrue(bins.get(8).silent(.01));
        assertEquals(0, bins.get(8).endSample);
        assertTrue(bins.get(9).peak > 0);
        assertEquals(0, bins.get(20).peak, 0);
        assertEquals(0, bins.get(20).rms, 0);
    }

    @Test public void alteredAssetOrDecoderClockCannotGenerateTrustedWaveform() {
        short[] pcm = new short[4800];
        OmegaAudioTimeline.AudioClip c = clip("secure", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 0, 4800, 0, 1, false);
        OmegaAudioTimeline.ProjectAudio p = project(30, 6, c);
        assertThrows(IllegalArgumentException.class, () -> OmegaAudioTimeline.waveformForClip(
                p, "secure", "wrong", HASH, 48000, 1, pcm));
        assertThrows(IllegalArgumentException.class, () -> OmegaAudioTimeline.waveformForClip(
                p, "secure", "asset_secure", OTHER_HASH, 48000, 1, pcm));
        assertThrows(IllegalArgumentException.class, () -> OmegaAudioTimeline.waveformForClip(
                p, "secure", "asset_secure", HASH, 44100, 1, pcm));
        assertThrows(IllegalArgumentException.class, () -> OmegaAudioTimeline.waveformForClip(
                p, "secure", "asset_secure", HASH, 48000, 2, pcm));
        assertThrows(IllegalArgumentException.class, () -> OmegaAudioTimeline.waveformForClip(
                p, "secure", "asset_secure", HASH, 48000, 1, new short[4799]));
        assertThrows(IllegalArgumentException.class, () -> OmegaAudioTimeline.waveformForClip(
                p, "missing", "asset_secure", HASH, 48000, 1, pcm));
    }

    @Test public void cuesAreExplicitImmutableAndLimitedToTheirDialogueSource() {
        OmegaAudioTimeline.AudioClip c = clip("speech", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 0, 48000, 0, 1, false);
        OmegaAudioTimeline.ProjectAudio p = project(30, 30, c);
        OmegaAudioTimeline.CueSheet sheet = new OmegaAudioTimeline.CueSheet(p, "speech",
                List.of(cue(0, 300000, "closed", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT),
                        cue(300000, 900000, "open", OmegaAudioTimeline.CueOrigin.MODEL_PREDICTED_UNALIGNED)));
        assertEquals(0, sheet.editRevision);
        assertEquals(2, sheet.cues.size());
        assertEquals("closed", sheet.atFrame(p, 0).shape);
        assertFalse(sheet.atFrame(p, 0).needsReview);
        assertEquals("open", sheet.atFrame(p, 15).shape);
        assertTrue(sheet.atFrame(p, 15).needsReview);
        assertEquals("rest", sheet.atFrame(p, 29).shape);
        assertThrows(UnsupportedOperationException.class, () -> sheet.cues.clear());
    }

    @Test public void shortConsonantsAreRepresentedAndAliasedFramesFlagged() {
        OmegaAudioTimeline.AudioClip c = clip("speech", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 0, 48000, 0, 1, false);
        OmegaAudioTimeline.ProjectAudio p = project(24, 24, c);
        OmegaAudioTimeline.CueSheet sheet = new OmegaAudioTimeline.CueSheet(p, "speech", List.of(
                cue(0, 10000, "closed", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT),
                cue(10000, 35000, "open", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT),
                cue(50000, 52000, "teeth", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT)));
        OmegaAudioTimeline.Viseme first = sheet.atFrame(p, 0);
        assertEquals("open", first.shape);
        assertTrue(first.multipleCuesInFrame);
        assertTrue(first.needsReview);
        assertEquals("teeth", sheet.atFrame(p, 1).shape);
        assertEquals("closed", sheet.atTimeUs(p, 5000).shape);
        assertEquals("open", sheet.atTimeUs(p, 10000).shape);
        assertEquals("rest", sheet.atTimeUs(p, 70000).shape);
    }

    @Test public void correctionIsCasAndCannotOverwriteAnAdjacentCue() {
        OmegaAudioTimeline.AudioClip c = clip("speech", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 0, 48000, 0, 1, false);
        OmegaAudioTimeline.ProjectAudio p = project(30, 30, c);
        OmegaAudioTimeline.CueSheet original = new OmegaAudioTimeline.CueSheet(p, "speech",
                List.of(cue(0, 200000, "open", OmegaAudioTimeline.CueOrigin.MODEL_PREDICTED_UNALIGNED),
                        cue(200000, 400000, "teeth", OmegaAudioTimeline.CueOrigin.MODEL_PREDICTED_UNALIGNED)));
        OmegaAudioTimeline.CueSheet corrected = original.correct(0, 0,
                cue(0, 200000, "closed", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT));
        assertEquals(0, original.editRevision);
        assertEquals(1, corrected.editRevision);
        assertEquals("open", original.atFrame(p, 1).shape);
        assertEquals("closed", corrected.atFrame(p, 1).shape);
        assertFalse(corrected.atFrame(p, 1).needsReview);
        assertThrows(IllegalArgumentException.class, () -> corrected.correct(0, 0,
                cue(0, 200000, "wide", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT)));
        assertThrows(IllegalArgumentException.class, () -> original.correct(0, 0,
                cue(0, 250000, "round", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT)));
        assertThrows(IllegalArgumentException.class, () -> original.correct(0, 0,
                cue(0, 200000, "open", OmegaAudioTimeline.CueOrigin.MODEL_PREDICTED_UNALIGNED)));
    }

    @Test public void speechRevisionAndSourceIdentityCannotSilentlyDrift() {
        OmegaAudioTimeline.AudioClip a = clip("speech", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 0, 48000, 0, 1, false);
        OmegaAudioTimeline.ProjectAudio original = project(30, 30, a);
        OmegaAudioTimeline.CueSheet sheet = new OmegaAudioTimeline.CueSheet(original, "speech",
                List.of(cue(0, 200000, "wide", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT)));
        OmegaAudioTimeline.ProjectAudio newRevision = new OmegaAudioTimeline.ProjectAudio(
                "sketch_7", 6, 30, 30, List.of(a));
        assertThrows(IllegalArgumentException.class, () -> sheet.atFrame(newRevision, 1));
        OmegaAudioTimeline.AudioClip shifted = clip("speech", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 0, 48000, 0, .75, false);
        assertThrows(IllegalArgumentException.class, () -> sheet.atFrame(
                project(30, 30, shifted), 1));
        OmegaAudioTimeline.ProjectAudio unchanged = project(30, 30, a);
        assertEquals("wide", sheet.atFrame(unchanged, 1).shape);
    }

    @Test public void cueValidationRejectsMalformedPredictionsAndOverlaps() {
        OmegaAudioTimeline.AudioClip dialogue = clip("dialogue", OmegaAudioTimeline.Kind.DIALOGUE,
                1, 0, 48000, 0, 1, false);
        OmegaAudioTimeline.AudioClip music = clip("music", OmegaAudioTimeline.Kind.MUSIC,
                1, 0, 48000, 0, 1, false);
        assertThrows(IllegalArgumentException.class, () -> new OmegaAudioTimeline.Cue(
                0, 0, "open", 1, OmegaAudioTimeline.CueOrigin.MANUAL_EDIT));
        assertThrows(IllegalArgumentException.class, () -> new OmegaAudioTimeline.Cue(
                0, 1000, "guessed", 1, OmegaAudioTimeline.CueOrigin.MANUAL_EDIT));
        assertThrows(IllegalArgumentException.class, () -> new OmegaAudioTimeline.Cue(
                0, 1000, "wide", Double.NaN, OmegaAudioTimeline.CueOrigin.MANUAL_EDIT));
        assertThrows(IllegalArgumentException.class, () -> new OmegaAudioTimeline.CueSheet(
                project(30, 30, music), "music", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new OmegaAudioTimeline.CueSheet(
                project(30, 30, dialogue), "dialogue",
                List.of(cue(0, 200000, "open", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT),
                        cue(199999, 300000, "wide", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT))));
        assertThrows(IllegalArgumentException.class, () -> new OmegaAudioTimeline.CueSheet(
                project(30, 30, dialogue), "dialogue",
                List.of(cue(900000, 1100000, "wide", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT))));
        assertThrows(IllegalArgumentException.class, () -> new OmegaAudioTimeline.CueSheet(
                project(30, 30, dialogue), "dialogue",
                Collections.nCopies(241, cue(0, 1000, "open", OmegaAudioTimeline.CueOrigin.MANUAL_EDIT))));
    }

    private static OmegaAudioTimeline.ExportEvidence evidence(int audioTracks, long samples,
                                                               double peak, int frames,
                                                               long durationUs) {
        return new OmegaAudioTimeline.ExportEvidence(OTHER_HASH, 1048576, durationUs,
                1, audioTracks, frames, samples, peak);
    }

    @Test public void exportProbeNeverCertifiesHumanListeningOrLipSync() {
        OmegaAudioTimeline.ProjectAudio p = project(30, 30,
                clip("speech", OmegaAudioTimeline.Kind.DIALOGUE, 1, 0, 48000, 0, 1, false));
        OmegaAudioTimeline.ExportAudit audit = OmegaAudioTimeline.auditExport(
                p, evidence(1, 48000, .3, 30, 1000000));
        assertEquals("TRACK_EVIDENCE_PRESENT_LISTENING_AND_SYNC_REVIEW_REQUIRED", audit.status);
        assertTrue(audit.blockers.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> audit.blockers.clear());
    }

    @Test public void missingAudioSilentFileAndWrongVideoFramesBlockRelease() {
        OmegaAudioTimeline.ProjectAudio p = project(30, 30,
                clip("speech", OmegaAudioTimeline.Kind.DIALOGUE, 1, 0, 48000, 0, 1, false));
        OmegaAudioTimeline.ExportAudit missing = OmegaAudioTimeline.auditExport(
                p, evidence(0, 0, 0, 29, 500000));
        assertEquals("BLOCK", missing.status);
        assertTrue(missing.blockers.contains("MISSING_DECODED_AUDIO"));
        assertTrue(missing.blockers.contains("DECODED_AUDIO_IS_SILENT"));
        assertTrue(missing.blockers.contains("VIDEO_FRAME_INVENTORY_MISMATCH"));
        assertTrue(missing.blockers.contains("EXPORT_DURATION_MISMATCH"));
        assertEquals("BLOCK", OmegaAudioTimeline.auditExport(
                project(30, 30), evidence(1, 48000, .5, 30, 1000000)).status);
        assertThrows(IllegalArgumentException.class, () ->
                evidence(1, 2000, Double.NaN, 30, 1000000));
        assertThrows(IllegalArgumentException.class, () ->
                new OmegaAudioTimeline.ExportEvidence("invalid", 100, 1000, 1, 1, 30, 300, .5));
    }
}
