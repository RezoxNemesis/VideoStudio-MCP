package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exercises persisted native transport data, without accepting a synthetic HTTP ACK. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class BoundedRelayTransportTest {
    @Test public void largeResultUsesExplicitBoundedReceiptInsteadOfCopyingArtworkIntoRelay() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        JSONObject result=new JSONObject().put("ok",true).put("projectId","project-owned")
                .put("artwork","🎨".repeat(800000));
        try(CommandOutbox outbox=new CommandOutbox(context)) {
            outbox.put(command(),result,"completed");
            JSONObject delivered=outbox.pending().getJSONObject(0).getJSONObject("result");
            assertTrue("One ACK must fit its transport budget",bytes(delivered)<=2048);
            assertTrue(delivered.getBoolean("ok"));
            assertEquals("project-owned",delivered.getString("projectId"));
            assertTrue(delivered.getBoolean("resultExternalized"));
            assertEquals(bytes(result),delivered.getInt("resultBytes"));
            assertEquals(64,delivered.getString("resultSha256").length());
            assertEquals("get_native_result_page",delivered.getJSONObject("resultLocator").getString("action"));
            assertEquals("🎨".repeat(800000),result.getString("artwork"));
        }
    }

    @Test public void registrationKeepsBoundedProjectIdentityAndCountsWithoutPromptOrExportPayloads() throws Exception {
        AppProtocol protocol=new AppProtocol(RuntimeEnvironment.getApplication(),null);
        try {
            JSONArray projects=new JSONArray();
            for(int i=0;i<130;i++) projects.put(new JSONObject().put("id","project-"+i)
                    .put("name","🎨".repeat(200)).put("assetCount",23).put("clipCount",2)
                    .put("durationMs",2000).put("updatedAt",i)
                    .put("sourcePrompt","private prompt".repeat(10000))
                    .put("latestExportUri","content://private/export".repeat(1000)));
            protocol.setLocalState("everything",new JSONObject().put("projects",projects));
            java.lang.reflect.Method meta=AppProtocol.class.getDeclaredMethod("registrationMeta");
            meta.setAccessible(true);JSONObject registration=(JSONObject)meta.invoke(protocol);
            assertTrue("Registration cannot grow with project text payloads",bytes(registration)<=36*1024);
            JSONArray summaries=registration.getJSONArray("projects");
            assertTrue(summaries.length()<=100);
            assertEquals("project-0",summaries.getJSONObject(0).getString("id"));
            assertEquals(23,summaries.getJSONObject(0).getInt("assetCount"));
            assertFalse(summaries.getJSONObject(0).has("sourcePrompt"));
            assertFalse(summaries.getJSONObject(0).has("latestExportUri"));
        } finally { closeProtocol(protocol); }
    }

    @Test public void fullUnicodeResultRemainsRetrievableAfterAckAndProcessRestart() throws Exception {
        Context context=RuntimeEnvironment.getApplication();JSONObject command=command();
        JSONObject result=new JSONObject().put("ok",false).put("error","Native provider unavailable")
                .put("details","🎨مرحبا drawing".repeat(400));
        byte[] expected=result.toString().getBytes(StandardCharsets.UTF_8);String id=command.getString("id"),frozen;
        try(CommandOutbox first=new CommandOutbox(context)) {
            first.put(command,result,"failed");frozen=first.pending().getJSONObject(0).getJSONObject("result").toString();
            first.acknowledge(id);assertFalse(first.contains(id));
        }
        try(CommandOutbox reopened=new CommandOutbox(context)) {
            JSONObject page=reopened.readResultPage(id,0);assertEquals("failed",page.getString("status"));
            ByteArrayOutputStream joined=new ByteArrayOutputStream();
            for(int index=0;index<page.getInt("pageCount");index++) {
                JSONObject fragment=reopened.readResultPage(id,index);
                assertTrue("Result page must travel without being externalized again",bytes(fragment)<=2048);
                assertEquals(page.getString("resultSha256"),fragment.getString("resultSha256"));
                assertTrue("Encoded chunks must survive the relay's binary-field redaction",fragment.has("encodedChunk"));
                assertFalse(fragment.has("base64"));assertEquals("base64",fragment.getString("encoding"));
                joined.write(android.util.Base64.decode(fragment.getString("encodedChunk"),android.util.Base64.NO_WRAP));
            }
            assertArrayEquals(expected,joined.toByteArray());
            assertEquals(RelayTransportPolicy.sha256(expected),page.getString("resultSha256"));
            // A duplicate lease must restore precisely the first frozen receipt,
            // even after delivery, instead of constructing a conflicting ACK.
            reopened.put(new JSONObject(command.toString()).put("seq",999),new JSONObject().put("ok",true).put("changed",true),"completed");
            JSONObject repeated=reopened.pending().getJSONObject(0);
            assertEquals("failed",repeated.getString("status"));
            assertEquals("Original sequence is part of the frozen receipt",1,repeated.getInt("seq"));
            assertEquals(frozen,repeated.getJSONObject("result").toString());
        }
    }

    @Test public void upgradePreservesPreviouslyAttemptedLegacyWireAndArchivesItsFullResult() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("mcp_result_outbox.db");
        JSONObject result=new JSONObject().put("ok",true).put("legacy","x".repeat(2100000));
        String wire=new JSONObject().put("status","completed").put("result",result).toString();
        android.database.sqlite.SQLiteDatabase legacy=context.openOrCreateDatabase("mcp_result_outbox.db",Context.MODE_PRIVATE,null);
        legacy.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, seq INTEGER NOT NULL, body TEXT NOT NULL, retry_after_ms INTEGER NOT NULL DEFAULT 0, attempt_count INTEGER NOT NULL DEFAULT 0)");
        legacy.execSQL("INSERT INTO results (id,seq,body) VALUES (?,?,?)",new Object[]{"legacy-accepted",3,wire});
        legacy.setVersion(2);legacy.close();
        try(CommandOutbox upgraded=new CommandOutbox(context)) {
            assertEquals(result.toString(),upgraded.pending().getJSONObject(0).getJSONObject("result").toString());
            upgraded.acknowledge("legacy-accepted");
            JSONObject page=upgraded.readResultPage("legacy-accepted",0);
            assertEquals(bytes(result),page.getInt("resultBytes"));
            assertEquals(RelayTransportPolicy.sha256(result.toString().getBytes(StandardCharsets.UTF_8)),page.getString("resultSha256"));
            assertFalse("Migrated receipts have no proven selected-file scope",upgraded.canReadResultForFile("legacy-accepted","any-file"));
        }
    }

    @Test public void smallResultsKeepOriginalShapeAndPagesRejectMissingOrOutOfRangeReads() throws Exception {
        JSONObject command=command(),result=new JSONObject().put("ok",true).put("revision",7);
        try(CommandOutbox outbox=new CommandOutbox(RuntimeEnvironment.getApplication())) {
            outbox.put(command,result,"completed");
            assertEquals(result.toString(),outbox.pending().getJSONObject(0).getJSONObject("result").toString());
            for(int invalid:new int[]{-1,1,Integer.MAX_VALUE}) try {
                outbox.readResultPage(command.getString("id"),invalid);fail("Invalid page accepted");
            } catch(IllegalArgumentException expected) {}
            try {outbox.readResultPage("missing-result",0);fail("Unknown result accepted");}
            catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("not found"));}
        }
    }

    @Test public void explicitLegacyRecoveryRequiresOriginalHashAndRetainsOriginalWireWithoutClaimingAck() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("mcp_result_outbox.db");
        JSONObject result=new JSONObject().put("ok",true).put("payload","🎨".repeat(6000));
        String wire=new JSONObject().put("status","completed").put("result",result).toString();
        android.database.sqlite.SQLiteDatabase legacy=context.openOrCreateDatabase("mcp_result_outbox.db",Context.MODE_PRIVATE,null);
        legacy.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, seq INTEGER NOT NULL, body TEXT NOT NULL, retry_after_ms INTEGER NOT NULL DEFAULT 0, attempt_count INTEGER NOT NULL DEFAULT 0)");
        legacy.execSQL("INSERT INTO results (id,seq,body) VALUES (?,?,?)",new Object[]{"legacy-recovery",4,wire});legacy.setVersion(2);legacy.close();
        try(CommandOutbox outbox=new CommandOutbox(context)) {
            try {outbox.compactLegacyReceipt("legacy-recovery","0".repeat(64));fail("Mismatched original result hash accepted");}
            catch(IllegalArgumentException expected) {}
            assertEquals(result.toString(),outbox.pending().getJSONObject(0).getJSONObject("result").toString());
            String sha=RelayTransportPolicy.sha256(result.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject receipt=outbox.compactLegacyReceipt("legacy-recovery",sha);
            assertTrue(receipt.getBoolean("localCompactionPrepared"));assertFalse(receipt.getBoolean("serverAcknowledged"));
            assertTrue(outbox.contains("legacy-recovery"));
            JSONObject pending=outbox.pending().getJSONObject(0);
            assertEquals("completed",pending.getString("status"));assertTrue(pending.getJSONObject("result").getBoolean("ok"));
            assertTrue(pending.getJSONObject("result").getBoolean("resultExternalized"));
            assertEquals(wire,SQLiteLargeText.read(outbox.getReadableDatabase(),"SELECT original_wire_body FROM receipt_recovery WHERE id=?","legacy-recovery"));
            assertEquals(sha,outbox.readResultPage("legacy-recovery",0).getString("resultSha256"));
            outbox.markNeedsReconciliation("legacy-recovery");
            JSONObject status=outbox.deliveryStatus(4);
            assertEquals("needs_reconciliation",status.getJSONArray("deliveries").getJSONObject(0).getString("recoveryState"));
            assertTrue(outbox.contains("legacy-recovery"));
        }
    }

    @Test public void httpFailureExposesOnlyAllowlistedCauseAndNeverServerBody() throws Exception {
        assertEquals("HTTP 400 SQLITE_TOOBIG",RelayTransportPolicy.safeHttpError(400,"{\"error\":\"string or blob too big: SQLITE_TOOBIG SECRET_OWNER\"}"));
        assertEquals("HTTP 400 TERMINAL_ACK_CONFLICT",RelayTransportPolicy.safeHttpError(400,"{\"error\":\"Conflicting terminal command acknowledgement was rejected; the original receipt is retained\"}"));
        assertEquals("HTTP 403",RelayTransportPolicy.safeHttpError(403,"{\"error\":\"Bearer SECRET_OWNER https://private.example/token\"}"));
    }

    @Test public void newReceiptsRefuseLegacyRecoveryAndIndexLimitsRemainExplicit() throws Exception {
        JSONObject command=command(),result=new JSONObject().put("ok",true).put("details","x".repeat(6000));
        try(CommandOutbox outbox=new CommandOutbox(RuntimeEnvironment.getApplication())) {
            outbox.put(command,result,"completed");
            try {outbox.compactLegacyReceipt(command.getString("id"),RelayTransportPolicy.sha256(result.toString().getBytes(StandardCharsets.UTF_8)));fail("New frozen receipt rewritten as legacy");}
            catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("legacy"));}
            JSONObject status=outbox.deliveryStatus(4);assertEquals(1,status.getInt("pendingResults"));assertEquals(0,status.getInt("legacyPending"));
            assertEquals(0,status.getJSONArray("deliveries").length());assertTrue(bytes(status)<=2048);
            for(int invalid:new int[]{0,5,Integer.MAX_VALUE}) try {outbox.deliveryStatus(invalid);fail("Unbounded delivery index accepted");}
            catch(IllegalArgumentException expected) {}
        }
    }

    @Test public void oneFilePagesRequireTheSameSelectionAsReceiptCapture() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);
        prefs.edit().putString("permission_mode","one_file").putString("allowed_asset_id","owned-file-a").commit();
        JSONObject command=command();AppProtocol protocol=new AppProtocol(context,null);
        try {
            protocol.captureResultScope(command,"one_file","owned-file-a");
            protocol.complete(command,new JSONObject().put("ok",true).put("analysis","x".repeat(6000)),"completed");
            assertEquals(command.getString("id"),protocol.readResultPage(command.getString("id"),0).getString("commandId"));
            prefs.edit().putString("allowed_asset_id","owned-file-b").commit();
            try {protocol.readResultPage(command.getString("id"),0);fail("Receipt for a different selected file was readable");}
            catch(SecurityException expected) {}
        } finally {closeProtocol(protocol);}
    }

    @Test public void broadModeArchiveDoesNotBecomeReadableBySelectingAnyOneFileLater() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);
        prefs.edit().putString("permission_mode","everything").putString("allowed_asset_id","old-selection").commit();
        JSONObject command=command();AppProtocol protocol=new AppProtocol(context,null);
        try {
            protocol.complete(command,new JSONObject().put("ok",true).put("state","x".repeat(6000)),"completed");
            prefs.edit().putString("permission_mode","one_file").putString("allowed_asset_id","old-selection").commit();
            try {protocol.readResultPage(command.getString("id"),0);fail("Unbound broad-mode receipt was readable in One File Lock");}
            catch(SecurityException expected) {}
        } finally {closeProtocol(protocol);}
    }

    @Test public void completionWithoutProvenExecutionScopeCannotAcquireCurrentFileBinding() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);
        prefs.edit().putString("permission_mode","everything").commit();
        AppProtocol protocol=new AppProtocol(context,null);JSONObject command=command();
        try {
            // Represents an older journal replay or restored background work;
            // its original permission scope is unavailable in this process.
            prefs.edit().putString("permission_mode","one_file").putString("allowed_asset_id","new-selection").commit();
            protocol.complete(command,new JSONObject().put("ok",true).put("broadState","x".repeat(6000)),"completed");
            try {protocol.readResultPage(command.getString("id"),0);fail("Unknown original execution scope acquired a selected-file binding");}
            catch(SecurityException expected) {}
        } finally {closeProtocol(protocol);}
    }

    @Test public void fileScopeCannotBeReboundAfterAdmissionOrDuplicateDelivery() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);
        AppProtocol protocol=new AppProtocol(context,null);
        try {
            JSONObject broad=command();prefs.edit().putString("permission_mode","everything").commit();protocol.captureResultScope(broad,"everything","");
            prefs.edit().putString("permission_mode","one_file").putString("allowed_asset_id","file-a").commit();
            protocol.captureResultScope(broad,"one_file","file-a");protocol.complete(broad,new JSONObject().put("ok",true).put("details","x".repeat(6000)),"completed");
            assertFalse(protocol.canReadResultForFile(broad.getString("id"),"file-a"));
            JSONObject changed=command();protocol.captureResultScope(changed,"one_file","file-a");
            prefs.edit().putString("allowed_asset_id","file-b").commit();
            protocol.complete(changed,new JSONObject().put("ok",true).put("details","x".repeat(6000)),"completed");
            assertFalse(protocol.canReadResultForFile(changed.getString("id"),"file-b"));
            assertFalse(protocol.canReadResultForFile(changed.getString("id"),"file-a"));
            JSONObject bound=command();protocol.captureResultScope(bound,"one_file","file-b");
            protocol.complete(bound,new JSONObject().put("ok",true),"completed");assertTrue(protocol.canReadResultForFile(bound.getString("id"),"file-b"));
            try(CommandOutbox reopened=new CommandOutbox(context)) {
                reopened.acknowledge(bound.getString("id"));
                reopened.put(bound,new JSONObject().put("ok",true),"completed","one_file","file-c");
                assertTrue(reopened.canReadResultForFile(bound.getString("id"),"file-b"));
                assertFalse(reopened.canReadResultForFile(bound.getString("id"),"file-c"));
                assertFalse(reopened.canReadResultForFile(bound.getString("id"),""));
            }
        } finally {closeProtocol(protocol);}
    }

    private static int bytes(JSONObject value) {return value.toString().getBytes(StandardCharsets.UTF_8).length;}
    private static void closeProtocol(AppProtocol protocol) throws Exception {
        protocol.stop();java.lang.reflect.Field field=AppProtocol.class.getDeclaredField("outbox");
        field.setAccessible(true);((CommandOutbox)field.get(protocol)).close();
    }
    private static JSONObject command() throws Exception {
        return new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",1).put("action","get_state");
    }
}
