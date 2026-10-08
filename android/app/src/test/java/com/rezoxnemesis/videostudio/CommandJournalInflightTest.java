package com.rezoxnemesis.videostudio;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class CommandJournalInflightTest {
    @Test public void inflightJobBindingSurvivesUntilTerminalResult() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CommandJournal journal = new CommandJournal(context);
        JSONObject command = new JSONObject()
                .put("id", "command-long-1")
                .put("seq", 91)
                .put("action", "prompt_video");

        journal.begin(command);
        journal.linkJob(
                command,
                "job-abcdef12",
                "project-ai",
                new JSONObject().put("queued", true).put("jobId", "job-abcdef12")
        );

        JSONObject inflight = journal.inflight("command-long-1");
        assertNotNull(inflight);
        assertEquals("job-abcdef12", inflight.optString("jobId"));
        assertEquals("project-ai", inflight.optString("projectId"));

        journal.finish(command, new JSONObject().put("ok", true), "completed");
        assertNull(journal.inflight("command-long-1"));
        assertNotNull(journal.terminal("command-long-1"));
    }
}
