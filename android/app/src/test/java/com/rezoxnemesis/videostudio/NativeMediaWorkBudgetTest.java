package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeMediaWorkBudgetTest {
    private static final long GIB=1024L*1024L*1024L;
    private static final long MINUTE=60_000L;

    @Test public void shortFilmsRetainOrdinaryThirtyMinuteAdmissionWindow(){
        assertEquals(30*MINUTE,NativeMediaWorkBudget.renderDeadlineMs(5000,64L*1024*1024));
    }
    @Test public void fiveAndTenGiBInputsNoLongerHitThirtyMinuteTimeout(){
        long five=NativeMediaWorkBudget.renderDeadlineMs(10_000,5*GIB);
        long ten=NativeMediaWorkBudget.renderDeadlineMs(10_000,10*GIB);
        assertTrue(five>30*MINUTE);
        assertTrue(ten>five);
        assertTrue(ten<24L*60*MINUTE);
    }
    @Test public void longFormTimelineUsesBoundedAdaptiveWindow(){
        assertEquals(8L*3*60*MINUTE,NativeMediaWorkBudget.renderDeadlineMs(3*60*MINUTE,0));
        assertEquals(24L*60*MINUTE,NativeMediaWorkBudget.renderDeadlineMs(Long.MAX_VALUE,10*GIB));
        assertThrows(IllegalArgumentException.class,()->
            NativeMediaWorkBudget.renderDeadlineMs(-1,0));
    }
    @Test public void lowStorageReportsShortfallInsteadOfRenderingIntoFullDisk(){
        StorageBudget.Check denied=NativeMediaWorkBudget.outputPreflight(8*GIB,10*GIB);
        assertFalse(denied.allowed);
        assertEquals(10*GIB+NativeMediaWorkBudget.RESERVED_FREE_BYTES,
                denied.requiredWithReserveBytes);
        StorageBudget.Check ready=NativeMediaWorkBudget.outputPreflight(12*GIB,10*GIB);
        assertTrue(ready.allowed);
        assertThrows(IllegalArgumentException.class,()->
            NativeMediaWorkBudget.outputPreflight(20*GIB,10*GIB+1));
    }
}
