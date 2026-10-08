package com.rezoxnemesis.videostudio;

import org.junit.Test;

import static org.junit.Assert.*;

public class ServiceOwnedUiWorkTest {
    @Test public void longRunningUiWorkHasForegroundServiceActions() {
        assertEquals(
                "com.rezoxnemesis.videostudio.LOCAL_PROMPT_VIDEO",
                ControlService.ACTION_LOCAL_PROMPT_VIDEO
        );
        assertEquals(
                "com.rezoxnemesis.videostudio.LOCAL_EXPORT_PROJECT",
                ControlService.ACTION_LOCAL_EXPORT
        );
    }
}
