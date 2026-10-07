package com.rezoxnemesis.videostudio;

import org.junit.Test;

import static org.junit.Assert.*;

public class NativeAgentWatchdogTest {
    @Test public void watchdogDelayIsBoundedWithoutExactAlarmPermission() {
        assertEquals(NativeAgentWatchdog.MIN_DELAY_MS, NativeAgentWatchdog.normalizeDelay(0L));
        assertEquals(10_000L, NativeAgentWatchdog.normalizeDelay(10_000L));
        assertEquals(NativeAgentWatchdog.MAX_DELAY_MS,
                NativeAgentWatchdog.normalizeDelay(Long.MAX_VALUE));
    }

    @Test public void localAgentRearmsUnlessUserPausedAutonomy() {
        assertTrue(NativeAgentWatchdog.shouldRearm(false));
        assertFalse(NativeAgentWatchdog.shouldRearm(true));
    }
}
