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
}
