package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class NativeWorkbenchBridgeTest {
    @Test public void onlyTheClosedKnownAliasesResolveToExistingActions()throws Exception {
        JSONObject advertised=NativeWorkbenchBridge.capabilities();JSONArray actions=advertised.getJSONArray("canonicalActions");assertEquals(85,actions.length());assertEquals("sketch_layered_drawing_preview",NativeWorkbenchBridge.canonicalAction("studio_sketch_layered_drawing_preview"));assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_sketch_layered_drawing_preview"));assertTrue(NativeAgentMailboxActions.supports("sketch_layered_drawing_preview"));assertEquals("4.9.2",advertised.getString("layeredDrawingMinimumNativeVersion"));assertEquals("sketch_review",NativeWorkbenchBridge.canonicalAction("studio_sketch_review"));assertEquals("sketch_quality_audit",NativeWorkbenchBridge.canonicalAction("studio_sketch_quality_audit"));assertEquals("app_execute",advertised.getString("tool"));assertEquals("3.9.0",advertised.getString("minimumNativeVersion"));assertEquals("4.1.0",advertised.getString("paintMinimumNativeVersion"));
        assertEquals("4.4.0",advertised.getString("characterAnimationMinimumNativeVersion"));
        for(String action:new String[]{"sketch_book_timeline","sketch_import_animation","sketch_component_save","sketch_component_list","sketch_component_apply"}){assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));}
        assertEquals("4.7.0",advertised.getString("featureLengthSketchMinimumNativeVersion"));
        assertEquals("cinematic_3d_edit_program",NativeWorkbenchBridge.canonicalAction("studio_cinematic_3d_edit_program"));
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_cinematic_3d_edit_program"));
        assertEquals("4.3.0",advertised.getString("cameraEditMinimumNativeVersion"));
        assertEquals("import_mailbox_media",NativeWorkbenchBridge.canonicalAction("studio_import_mailbox_media"));
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_import_mailbox_media"));
        assertEquals("4.2.0",advertised.getString("encryptedMediaImportMinimumNativeVersion"));
        assertEquals(256L*1024*1024,advertised.getLong("encryptedMediaImportMaximumBytes"));
        // New actions are explicitly enumerated; unknown prefixes remain closed below.
        for(String action:new String[]{"sketch_animation_place_in_book","sketch_story_create","sketch_story_preview","sketch_story_audio_create","sketch_story_example","project_asset_info","project_import_asset"}){
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));assertTrue(NativeAgentMailboxActions.supports(action));
        }
        for(String suffix:new String[]{"start","get","list","note","options","propose","preview","apply","close"}){
            String action="sketch_correction_"+suffix;assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));assertTrue(NativeAgentMailboxActions.supports(action));assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
        }
        for(int i=0;i<actions.length();i++){String action=actions.getString(i);assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));assertEquals(action,NativeWorkbenchBridge.canonicalAction(action));}
        for(String unknown:new String[]{"studio_gallery_list","studio_delete_project","studio_run_shell","studio_unknown_action","studio_studio_sketch_edit","studio_cinematic_3d_save_program/execute","Studio_sketch_edit","studio_"})assertEquals(unknown,NativeWorkbenchBridge.canonicalAction(unknown));assertNull(NativeWorkbenchBridge.canonicalAction(null));
    }
    @Test public void referenceLayersAliasUsesSharedMailboxAndBackgroundRoute()throws Exception {
        String action="sketch_import_reference_layers";
        assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
        assertTrue(NativeAgentMailboxActions.supports(action));
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
        assertEquals("4.8.5",NativeWorkbenchBridge.capabilities().getString("referenceLayersMinimumNativeVersion"));
        assertEquals("4.8.5",NativeWorkbenchBridge.capabilities().getString("sourceMaskMinimumNativeVersion"));
    }
    @Test public void referencePreviewAliasIsExplicitAndRunsOnTheBackgroundLane()throws Exception {
        String action="sketch_reference_layers_preview";
        assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
        assertTrue(NativeAgentMailboxActions.supports(action));
        assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
        assertEquals("4.8.5",NativeWorkbenchBridge.capabilities().getString("referenceLayersPreviewMinimumNativeVersion"));
    }
    @Test public void flatParametersCredentialsAndPrivateBookkeepingStayExactAndIndependent()throws Exception {
        JSONObject parameters=new JSONObject().put("projectId","owned_source").put("sourceProjectUpdatedAt",9223372036854775800L).put("requestId","job_reference").put("bearer","private-token-in-memory-only").put("_mcpCommandId","command_1").put("_recoveryPlanId","recovery_1").put("assetIds",new JSONArray().put("source_b").put("source_a")).put("settings",new JSONObject().put("strength",.8));
        JSONObject original=new JSONObject().put("id","command_1").put("seq",17).put("action","studio_open_pipeline_configure").put("parameters",parameters).put("createdAt","2026-10-12T00:00:00.000Z").put("_private_internal",new JSONObject().put("generation",5));String before=original.toString();JSONObject canonical=NativeWorkbenchBridge.canonicalCommand(original);
        assertEquals("open_pipeline_configure",canonical.getString("action"));assertEquals(before,original.toString());assertEquals(parameters.toString(),canonical.getJSONObject("parameters").toString());assertEquals(9223372036854775800L,canonical.getJSONObject("parameters").getLong("sourceProjectUpdatedAt"));assertEquals("private-token-in-memory-only",canonical.getJSONObject("parameters").getString("bearer"));assertEquals("command_1",canonical.getString("id"));assertEquals(17,canonical.getInt("seq"));assertEquals(5,canonical.getJSONObject("_private_internal").getInt("generation"));assertFalse(canonical.getJSONObject("parameters").has("nativeParameters"));
        canonical.getJSONObject("parameters").getJSONArray("assetIds").put(0,"changed");canonical.getJSONObject("parameters").getJSONObject("settings").put("strength",.5);canonical.getJSONObject("_private_internal").put("generation",6);assertEquals(before,original.toString());
    }
    @Test public void unknownPrefixCommandsRemainUnknownWithoutLosingSourceOrRevision()throws Exception {JSONObject original=new JSONObject().put("id","future").put("action","studio_foreign_action").put("parameters",new JSONObject().put("projectId","source").put("expectedUpdatedAt",123));JSONObject copy=NativeWorkbenchBridge.canonicalCommand(original);assertEquals(original.toString(),copy.toString());assertNotSame(original,copy);assertNotSame(original.getJSONObject("parameters"),copy.getJSONObject("parameters"));}
    @Test public void aliasesUseTheirCanonicalBackgroundLaneWithoutBroadPrefixAdmission()throws Exception {JSONArray actions=NativeWorkbenchBridge.capabilities().getJSONArray("canonicalActions");for(int i=0;i<actions.length();i++){String action=actions.getString(i);assertEquals(action,NativeCommandExecutionPolicy.requiresBackgroundThread(action),NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));}assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_open_pipeline_submit"));assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_sketch_render"));assertFalse(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_run_shell"));assertFalse(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_cancel_job"));}
    @Test public void cyclesForeignObjectsAndOverBudgetMetadataRefuseBeforeSerialization()throws Exception {JSONObject cyclic=new JSONObject().put("action","studio_sketch_edit");cyclic.put("parameters",cyclic);assertThrows(IllegalArgumentException.class,()->NativeWorkbenchBridge.canonicalCommand(cyclic));JSONObject oversized=new JSONObject().put("action","studio_sketch_edit").put("parameters",new JSONObject().put("text","x".repeat(2*1024*1024)));assertThrows(IllegalArgumentException.class,()->NativeWorkbenchBridge.canonicalCommand(oversized));JSONObject invalid=new JSONObject().put("action","studio_sketch_edit").put("parameters",new JSONObject().put("object",new Object()));assertThrows(IllegalArgumentException.class,()->NativeWorkbenchBridge.canonicalCommand(invalid));}
}
