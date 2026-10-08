package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class DeferredActivityStateTest {
    @Test public void thermalPauseIsPresentedAsWaitingInsteadOfExecuting() throws Exception {
        JSONObject job = new JSONObject()
                .put("state", "waiting_thermal")
                .put("progress", 0)
                .put("stage", "queued")
                .put("detail", "Thermal governor paused heavy work; checkpoint preserved until the phone cools");

        JSONObject activity = ExecutionTruthPolicy.presentDeferredJob(
                "Executing CreativeIR graph", job);

        assertEquals("Waiting for phone to cool", activity.optString("action"));
        assertEquals("queued", activity.optString("status"));
        assertEquals(0, activity.optInt("progress"));
        assertTrue(activity.optString("detail").contains("Thermal governor"));
    }

    @Test public void runningJobUsesRealStageProgressAndDetail() throws Exception {
        JSONObject job = new JSONObject()
                .put("state", "running")
                .put("progress", 42)
                .put("stage", "Creative Runtime")
                .put("detail", "7/18 DAG nodes complete");

        JSONObject activity = ExecutionTruthPolicy.presentDeferredJob(
                "Executing CreativeIR graph", job);

        assertEquals("Creative Runtime", activity.optString("action"));
        assertEquals("running", activity.optString("status"));
        assertEquals(42, activity.optInt("progress"));
        assertEquals("7/18 DAG nodes complete", activity.optString("detail"));
    }
}
