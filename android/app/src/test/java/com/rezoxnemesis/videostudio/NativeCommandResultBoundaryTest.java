package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Future regression source; deliberately not executed in the implementation phase. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class NativeCommandResultBoundaryTest {
    private static String text(int length) { char[] chars = new char[length]; java.util.Arrays.fill(chars, 'x'); return new String(chars); }
    private static JSONObject command(int id) throws Exception { return new JSONObject().put("id", "result-" + id).put("seq", id); }

    @Test public void oversizedFinalResultHasDeterministicExplicitDeliveryLimit() throws Exception {
        JSONObject first = new JSONObject().put("ok", true).put("completed", true).put("assetId", "owned-output").put("blob", text(600 * 1024));
        JSONObject second = new JSONObject().put("blob", first.getString("blob")).put("assetId", "owned-output").put("ok", true).put("completed", true);
        JSONObject receipt = NativeCommandResults.bounded(first, "completed");
        assertTrue(receipt.getBoolean("actualOperationCompleted"));
        assertTrue(receipt.getBoolean("resultTruncated")); assertFalse(receipt.getBoolean("resultDeliveryComplete"));
        assertEquals("owned-output", receipt.getString("assetId"));
        assertEquals(receipt.getString("fullResultSha256"), NativeCommandResults.bounded(second, "completed").getString("fullResultSha256"));
        assertTrue(receipt.toString().getBytes(StandardCharsets.UTF_8).length < NativeCommandResults.MAX_RESULT_BYTES);
        first.put("queued", true).put("jobId", "still-running");
        assertFalse(NativeCommandResults.bounded(first, "completed").getBoolean("actualOperationCompleted"));
    }

    @Test public void deepResultProducesBoundedHonestEncodingReceipt() throws Exception {
        JSONObject root = new JSONObject().put("ok", true), cursor = root;
        for (int i = 0; i < 70; i++) { JSONObject nested = new JSONObject(); cursor.put("nested", nested); cursor = nested; }
        JSONObject receipt = NativeCommandResults.bounded(root, "completed");
        assertEquals("result_encoding_limit", receipt.getString("code"));
        assertFalse(receipt.getBoolean("fullResultSha256Available"));
        assertTrue(receipt.getBoolean("resultTruncated"));
    }

    @Test public void reservationsBackpressureBeforeEffectsAndExactReplayCannotOverwrite() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); context.deleteDatabase("mcp_result_outbox.db");
        CommandOutbox outbox = new CommandOutbox(context);
        try {
            for (int i = 1; i <= CommandOutbox.MAX_UNACKNOWLEDGED; i++) assertTrue(outbox.reserve(command(i)));
            assertFalse(outbox.reserve(command(17))); assertEquals(16, outbox.acceptedCount());
            JSONObject result = new JSONObject().put("ok", true).put("revision", 7);
            outbox.put(command(1), result, "completed"); outbox.put(command(1), result, "completed");
            try { outbox.put(command(1), new JSONObject().put("ok", true).put("revision", 8), "completed"); fail("Conflicting unacknowledged result replaced"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
            assertEquals(7, outbox.pending().getJSONObject(0).getJSONObject("result").getInt("revision"));
            outbox.acknowledge("result-1"); assertTrue(outbox.reserve(command(17)));
        } finally { outbox.close(); context.deleteDatabase("mcp_result_outbox.db"); }
    }

    @Test public void legacyOversizedReceiptIsRetainedAndBlocksNewEffectAdmission() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); context.deleteDatabase("mcp_result_outbox.db");
        CommandOutbox outbox = new CommandOutbox(context);
        try {
            ContentValues legacy = new ContentValues(); legacy.put("id", "legacy"); legacy.put("seq", 1);
            legacy.put("body", new JSONObject().put("status", "completed").put("result", new JSONObject().put("legacy", text(600 * 1024))).toString());
            outbox.getWritableDatabase().insertOrThrow("results", null, legacy);
            assertFalse(outbox.reserve(command(2))); assertEquals(1, outbox.count());
            assertEquals(1, outbox.storageStatus().getInt("oversizedLegacyResults"));
        } finally { outbox.close(); context.deleteDatabase("mcp_result_outbox.db"); }
    }
}
