package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class JobRecoveryTest {
    @Test public void canonicalTransitionsRejectTerminalResurrection() {
        assertTrue(JobManager.canTransition(JobManager.STATE_QUEUED, JobManager.STATE_PREPARING));
        assertTrue(JobManager.canTransition(JobManager.STATE_RUNNING, JobManager.STATE_WAITING_THERMAL));
        assertTrue(JobManager.canTransition(JobManager.STATE_WAITING_THERMAL, JobManager.STATE_RUNNING));
        assertTrue(JobManager.canTransition(JobManager.STATE_RUNNING, JobManager.STATE_CHECKPOINTED));
        assertTrue(JobManager.canTransition(JobManager.STATE_CHECKPOINTED, JobManager.STATE_RUNNING));
        assertTrue(JobManager.canTransition(JobManager.STATE_RUNNING, JobManager.STATE_COMPLETED));

        assertFalse(JobManager.canTransition(JobManager.STATE_COMPLETED, JobManager.STATE_RUNNING));
        assertFalse(JobManager.canTransition(JobManager.STATE_FAILED, JobManager.STATE_RUNNING));
        assertFalse(JobManager.canTransition(JobManager.STATE_CANCELLED, JobManager.STATE_RUNNING));
    }

    @Test public void legacyInterruptedWorkRestoresAsCheckpointedWithoutLosingProgress() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        JSONObject row=new JSONObject()
                .put("id","job-restore")
                .put("name","Heavy render")
                .put("kind","heavy")
                .put("state","running")
                .put("progress",67)
                .put("detail","Encoding")
                .put("stage","encode")
                .put("recoverable",true)
                .put("retryCount",1)
                .put("lastCheckpointAt",now)
                .put("createdAt",now-1000)
                .put("updatedAt",now);
        prefs.edit().putString("job_recovery_snapshot",new JSONArray().put(row).toString()).commit();

        JobManager manager=new JobManager(context);
        try {
            JSONObject state=manager.get("job-restore").getJSONObject("job");
            assertEquals(JobManager.STATE_CHECKPOINTED,state.getString("state"));
            assertEquals("encode",state.getString("stage"));
            assertEquals(67,state.getInt("progress"));
            assertTrue(state.getBoolean("recoverable"));
        } finally {
            manager.shutdown();
            prefs.edit().remove("job_recovery_snapshot").commit();
        }
    }

    @Test public void waitStatesAreNonTerminalAndCancellationIsTerminal() {
        assertFalse(JobManager.isTerminal(JobManager.STATE_WAITING_MEMORY));
        assertFalse(JobManager.isTerminal(JobManager.STATE_WAITING_STORAGE));
        assertFalse(JobManager.isTerminal(JobManager.STATE_WAITING_NETWORK));
        assertFalse(JobManager.isTerminal(JobManager.STATE_WAITING_NATIVE));
        assertTrue(JobManager.isTerminal(JobManager.STATE_CANCELLED));
        assertTrue(JobManager.isTerminal(JobManager.STATE_FAILED));
        assertTrue(JobManager.isTerminal(JobManager.STATE_COMPLETED));
    }
}
