package com.rezoxnemesis.videostudio;

import org.junit.Test;

import static org.junit.Assert.*;

public class StorageBudgetTest {
    private static final long GB=1024L*1024L*1024L;

    @Test public void fiveGigabyteTransferUsesLongArithmeticAndReserve() {
        StorageBudget.Check ok=StorageBudget.check(8L*GB,5L*GB,1L*GB);
        assertTrue(ok.allowed);
        assertEquals(0L,ok.shortfallBytes);
        assertEquals(6L*GB,ok.requiredWithReserveBytes);
    }

    @Test public void insufficientStorageReportsExactShortfallWithoutOverflow() {
        StorageBudget.Check no=StorageBudget.check(5L*GB+512L*1024L*1024L,5L*GB,1L*GB);
        assertFalse(no.allowed);
        assertEquals(512L*1024L*1024L,no.shortfallBytes);

        StorageBudget.Check overflow=StorageBudget.check(
                Long.MAX_VALUE-100L,
                Long.MAX_VALUE-50L,
                500L
        );
        assertFalse(overflow.allowed);
        assertEquals(Long.MAX_VALUE,overflow.requiredWithReserveBytes);
    }

    @Test public void resumedTransferBudgetsOnlyRemainingBytesPlusReserve() {
        StorageBudget.Check resumed=StorageBudget.checkTransfer(
                4L*GB+256L*1024L*1024L,
                8L*GB,
                5L*GB,
                1L*GB
        );
        assertTrue(resumed.allowed);
        assertEquals(3L*GB,resumed.requiredBytes);
        assertEquals(4L*GB,resumed.requiredWithReserveBytes);
    }

    @Test public void unknownLengthRequiresSafetyReserveWithoutInventingFileSize() {
        StorageBudget.Check unknown=StorageBudget.checkTransfer(
                2L*GB,
                -1L,
                0L,
                768L*1024L*1024L
        );
        assertTrue(unknown.allowed);
        assertEquals(0L,unknown.requiredBytes);
        assertEquals(768L*1024L*1024L,unknown.requiredWithReserveBytes);
    }
}
