package com.rezoxnemesis.videostudio;

import android.os.PowerManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public final class ThermalQueuePolicyTest {

    @Test public void normalAndModerateHeatNeverArtificiallyBlockRender() {
        for (int status=PowerManager.THERMAL_STATUS_NONE;
                 status<=PowerManager.THERMAL_STATUS_MODERATE; status++) {
            assertFalse(ThermalQueuePolicy.shouldPause(JobManager.Kind.HEAVY, status));
            assertFalse(ThermalQueuePolicy.shouldPause(JobManager.Kind.TRANSFER, status));
        }
    }

    @Test public void severeDoesNotImposeAnArtificialRenderHold() {
        int s=PowerManager.THERMAL_STATUS_SEVERE;
        assertFalse(ThermalQueuePolicy.shouldPause(JobManager.Kind.HEAVY, s));
        assertFalse(ThermalQueuePolicy.shouldPause(JobManager.Kind.TRANSFER, s));
        assertFalse(ThermalQueuePolicy.shouldPause(JobManager.Kind.LIGHT, s));
        assertEquals("system_throttled",ThermalQueuePolicy.schedulingMode(s));
    }

    @Test public void androidRetainsThermalControlWithoutAppQueueHolds() {
        for (int s=PowerManager.THERMAL_STATUS_CRITICAL;
                s<=PowerManager.THERMAL_STATUS_SHUTDOWN; s++) {
            assertFalse(ThermalQueuePolicy.shouldPause(JobManager.Kind.HEAVY,s));
            assertFalse(ThermalQueuePolicy.shouldPause(JobManager.Kind.TRANSFER,s));
            assertEquals("system_throttled",ThermalQueuePolicy.schedulingMode(s));
        }
    }

    @Test public void memorySafetyAndQueueCancellationAreNotRelaxed() {
        assertTrue(JobManager.canTransition(JobManager.STATE_RUNNING,JobManager.STATE_WAITING_MEMORY));
        assertTrue(JobManager.canTransition(JobManager.STATE_RUNNING,JobManager.STATE_WAITING_THERMAL));
        assertTrue(JobManager.canTransition(JobManager.STATE_WAITING_THERMAL,JobManager.STATE_CANCELLED));
        assertFalse(JobManager.canTransition(JobManager.STATE_CANCELLED,JobManager.STATE_RUNNING));
    }

    @Test public void reportedPolicyIsHonestAboutRenderAndTransferDifference() {
        assertTrue(ThermalQueuePolicy.canRunRender(PowerManager.THERMAL_STATUS_SEVERE));
        assertTrue(ThermalQueuePolicy.canRunTransfer(PowerManager.THERMAL_STATUS_SEVERE));
        assertTrue(ThermalQueuePolicy.canRunTransfer(PowerManager.THERMAL_STATUS_CRITICAL));
        assertTrue(ThermalQueuePolicy.waitingMessage(JobManager.Kind.HEAVY,
                PowerManager.THERMAL_STATUS_CRITICAL).contains("continues"));
    }
}
