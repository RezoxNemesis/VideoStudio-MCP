package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class CommandJournalSummaryTest {
    @Test public void diagnosticSummaryRetainsStatusWithoutCopyingDocumentOrImage() throws Exception {
        CommandJournal journal = new CommandJournal(RuntimeEnvironment.getApplication());
        String id = "summary-" + java.util.UUID.randomUUID();
        JSONObject command = new JSONObject().put("id", id).put("action", "sketch_get").put("seq", 9);
        JSONObject result = new JSONObject().put("ok", true).put("document", new JSONObject()
                .put("privateDrawing", new String(new char[200000]).replace('\0','x')))
                .put("contactSheet", new JSONObject().put("base64", "PRIVATE_IMAGE_BYTES"));
        journal.finish(command, result, "completed");
        JSONArray summaries = journal.recentSummaries(1);
        JSONObject summary = summaries.getJSONObject(0);
        assertEquals(id, summary.getString("id"));
        assertEquals("completed", summary.getString("status"));
        assertTrue(summary.getBoolean("ok"));
        assertTrue(summary.getBoolean("resultRetainedLocally"));
        assertTrue(summary.getInt("resultBytes") > 200000);
        assertTrue(summaries.toString().length() < 600);
        assertFalse(summaries.toString().contains("PRIVATE_IMAGE_BYTES"));
        assertEquals(result.toString(), journal.terminal(id).getJSONObject("result").toString());
    }
}
