package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Reproduction: a reconnecting relay claimed the same command >40 times. */
public final class NativeCommandDispatchGateTest {
    @Test public void repeatedLeasesDispatchOnceUntilBoundedRetry() {
        NativeCommandDispatchGate gate = new NativeCommandDispatchGate();
        assertTrue(gate.shouldDispatch("get-state-41", 1000));
        for (int i=0;i<1000;i++)
            assertFalse("Repeated claim must not flood the main thread",
                    gate.shouldDispatch("get-state-41", 1001+i));
        assertFalse(gate.shouldDispatch("get-state-41",
                1000+NativeCommandDispatchGate.RETRY_AFTER_MS-1));
        assertTrue(gate.shouldDispatch("get-state-41",
                1000+NativeCommandDispatchGate.RETRY_AFTER_MS));
    }

    @Test public void relayAcknowledgementReleasesTheDispatchSlot() {
        NativeCommandDispatchGate gate = new NativeCommandDispatchGate();
        assertTrue(gate.shouldDispatch("render-46", 5));
        gate.acknowledged("render-46");
        assertTrue(gate.shouldDispatch("render-46", 6));
    }

    @Test public void rejectedPostCanBeRetriedAndIdsStayIndependent() {
        NativeCommandDispatchGate gate = new NativeCommandDispatchGate();
        assertTrue(gate.shouldDispatch("a", 2));
        assertTrue(gate.shouldDispatch("b", 2));
        assertFalse(gate.shouldDispatch("a", 3));
        gate.undelivered("a");
        assertTrue(gate.shouldDispatch("a", 4));
        assertFalse(gate.shouldDispatch("b", 4));
    }

    @Test public void boundedMemoryAndMonotonicClockRecovery() {
        NativeCommandDispatchGate gate = new NativeCommandDispatchGate();
        for(int i=0;i<2000;i++)gate.shouldDispatch("id-"+i,i);
        assertTrue(gate.pendingForTesting()<=256);
        assertTrue("After monotonic clock reset, retained ID is redeliverable",
                gate.shouldDispatch("id-1999", 0));
    }

    @Test public void invalidCommandsNeverOccupyCapacity(){
        NativeCommandDispatchGate gate = new NativeCommandDispatchGate();
        assertFalse(gate.shouldDispatch("",1));
        assertFalse(gate.shouldDispatch(null,1));
        assertFalse(gate.shouldDispatch("valid",-1));
        assertEquals(0,gate.pendingForTesting());
    }
}
