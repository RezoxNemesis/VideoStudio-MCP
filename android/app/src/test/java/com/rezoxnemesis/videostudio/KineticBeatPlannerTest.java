package com.rezoxnemesis.videostudio;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Deterministic scheduling and measured-audio honesty regression tests. */
public class KineticBeatPlannerTest {
    @Test public void userTempoIsExplicitAndExactlyBounded() {
        List<Long> beats = KineticBeatPlanner.manualTempo(2200, 120, 100);
        assertEquals(Arrays.asList(100L, 600L, 1100L, 1600L, 2100L), beats);
        assertThrows(IllegalArgumentException.class,
                () -> KineticBeatPlanner.manualTempo(6000, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> KineticBeatPlanner.manualTempo(6000, 221, 0));
    }

    @Test public void silenceNeverPretendsToContainMusicalBeats() {
        assertTrue(KineticBeatPlanner.envelopeOnsets(new double[120], 0, 6000).isEmpty());
    }

    @Test public void decodedRmsPulseGridProducesBoundedDistinctOnsets() {
        double[] rms = new double[200];
        for (int bucket : new int[]{20, 40, 60, 80, 100, 120, 140, 160}) {
            rms[bucket] = .98;
            rms[bucket + 1] = .80;
        }
        List<Long> candidates = KineticBeatPlanner.envelopeOnsets(rms, 0, 6000);
        assertTrue("Transient onset detector should identify source pulse candidates",
                candidates.size() >= 5);
        for (int i = 1; i < candidates.size(); i++)
            assertTrue("No duplicate-neighbor peaks", candidates.get(i) - candidates.get(i - 1) >= 220);
    }

    @Test public void authoredMarkersCannotEscapeClipOrHideDuplicates() {
        assertEquals(Arrays.asList(0L, 600L, 1200L),
                KineticBeatPlanner.authoredMarkers(Arrays.asList(0L, 600L, 1200L), 1500));
        assertThrows(IllegalArgumentException.class,
                () -> KineticBeatPlanner.authoredMarkers(Arrays.asList(0L, 500L, 500L), 1800));
        assertThrows(IllegalArgumentException.class,
                () -> KineticBeatPlanner.authoredMarkers(Arrays.asList(0L, 2000L), 1800));
    }

    @Test public void titleCuesAreAlwaysContainedAndNonOverlapping() {
        List<KineticBeatPlanner.Cue> cues = KineticBeatPlanner.cues(
                KineticBeatPlanner.manualTempo(36000, 128, 0), 18, 36000);
        assertTrue(cues.size() > 8);
        long end = 0;
        for (KineticBeatPlanner.Cue cue : cues) {
            assertTrue(cue.startMs >= end);
            assertTrue(cue.durationMs >= 190);
            assertTrue(cue.startMs + cue.durationMs <= 36000);
            end = cue.startMs + cue.durationMs;
        }
    }

    @Test public void longClipBoundaryTimingsDoNotManufactureAudioMetadata() {
        List<Long> cuts = KineticBeatPlanner.clipBoundaries(
                Arrays.asList(2000L, 3000L, 1800L), 6800);
        assertEquals(Arrays.asList(0L, 2000L, 5000L), cuts);
    }
}
