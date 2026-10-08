package com.rezoxnemesis.videostudio;

import android.content.Context;

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
public class ActivityLogWorkTest {
    @Test public void workFeedFiltersConnectionNoise() {
        Context context = RuntimeEnvironment.getApplication();
        ActivityLog.clear(context);

        ActivityLog.add(context, "system", "MCP connected",
                "heartbeat", "success", null, null, null);
        ActivityLog.add(context, "chatgpt", "Prompt video",
                "Rendering frame sequence", "running", 40,
                "command-123", "project-a");

        JSONArray work = ActivityLog.recentWork(context, 5);
        assertEquals(1, work.length());
        JSONObject item = work.optJSONObject(0);
        assertNotNull(item);
        assertEquals("Prompt video", item.optString("action"));
        assertEquals("command-123", item.optString("commandId"));
    }

    @Test public void backgroundJobProgressUpdatesItsCommandCardInsteadOfCreatingAnotherCard() {
        Context context = RuntimeEnvironment.getApplication();
        ActivityLog.clear(context);

        ActivityLog.add(context, "chatgpt", "Executing CreativeIR graph",
                "Queued inside VideoStudio • job 7f5a81be", "queued", 0,
                "command-live-2", "project-a");
        ActivityLog.bindJob(context, "command-live-2", "job-live-2");
        ActivityLog.progress(context, "job-live-2", "Creative Runtime",
                "7/18 DAG nodes complete", 42, "project-a");

        JSONArray work = ActivityLog.recentWork(context, 10);
        assertEquals(1, work.length());
        JSONObject item = work.optJSONObject(0);
        assertNotNull(item);
        assertEquals("command-live-2", item.optString("commandId"));
        assertEquals("job-live-2", item.optString("jobId"));
        assertEquals("Creative Runtime", item.optString("action"));
        assertEquals(42, item.optInt("progress"));
    }

    @Test public void repeatedCommandUpdatesCollapseIntoOneLiveCard() {
        Context context = RuntimeEnvironment.getApplication();
        ActivityLog.clear(context);

        ActivityLog.add(context, "chatgpt", "Executing CreativeIR graph",
                "Queued inside VideoStudio • job 7f5a81be", "queued", 0,
                "command-live-1", "project-a");
        ActivityLog.add(context, "chatgpt", "Executing CreativeIR graph",
                "Existing native job still running • 7f5a81be", "running", 0,
                "command-live-1", "project-a");
        ActivityLog.add(context, "chatgpt", "Waiting for phone to cool",
                "Thermal governor paused heavy work; checkpoint preserved until the phone cools",
                "queued", 0, "command-live-1", "project-a");

        JSONArray work = ActivityLog.recentWork(context, 10);
        assertEquals(1, work.length());
        JSONObject item = work.optJSONObject(0);
        assertNotNull(item);
        assertEquals("command-live-1", item.optString("commandId"));
        assertEquals("Waiting for phone to cool", item.optString("action"));
        assertEquals("queued", item.optString("status"));
        assertEquals(0, item.optInt("progress"));
    }
}
