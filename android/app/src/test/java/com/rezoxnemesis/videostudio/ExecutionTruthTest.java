package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ExecutionTruthTest {
    @Test public void transportHousekeepingIsNotAutonomousWork() {
        assertTrue(ExecutionTruthPolicy.isTransportActivity("system", "MCP connected"));
        assertTrue(ExecutionTruthPolicy.isTransportActivity("system", "MCP reconnecting"));
        assertFalse(ExecutionTruthPolicy.isTransportActivity("chatgpt", "Prompt video"));
        assertFalse(ExecutionTruthPolicy.shouldSurfaceAsWork("system", "MCP connected"));
        assertTrue(ExecutionTruthPolicy.shouldSurfaceAsWork("chatgpt", "Prompt video"));
    }

    @Test public void emptyEditorFollowsServiceCreatedActiveProjectButRealWorkIsNotStolen() {
        assertTrue(ExecutionTruthPolicy.shouldAdoptStoreActive(
                true, "empty-project", "ai-project"));
        assertFalse(ExecutionTruthPolicy.shouldAdoptStoreActive(
                false, "edited-project", "ai-project"));
        assertFalse(ExecutionTruthPolicy.shouldAdoptStoreActive(
                true, "same-project", "same-project"));
    }

    @Test public void queuedJobsAreDeferredUntilTheirNativeJobActuallyFinishes() throws Exception {
        JSONObject queued = new JSONObject()
                .put("ok", true)
                .put("queued", true)
                .put("jobId", "job-12345678")
                .put("projectId", "project-a");
        assertTrue(ExecutionTruthPolicy.isDeferredResult(queued));
        assertTrue(ExecutionTruthPolicy.requiresValidatedMediaOutput("prompt_video"));
        assertTrue(ExecutionTruthPolicy.requiresValidatedMediaOutput("animate_images"));
        assertTrue(ExecutionTruthPolicy.requiresValidatedMediaOutput("export_project"));
        assertFalse(ExecutionTruthPolicy.requiresValidatedMediaOutput("generate_image"));
    }

    @Test public void liveStatusAndRecoveryJournalUseDifferentPreferenceKeys() {
        assertNotEquals(
                ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY,
                ExecutionTruthPolicy.LIVE_JOB_PREF_KEY
        );
    }
}
