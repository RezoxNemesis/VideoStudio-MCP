package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class OwnerAccessPolicyTest {
    private Context context;private ProjectStore store;private SharedPreferences prefs;private ProjectStore.Project project,privateProject;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_v3.db");prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);prefs.edit().clear().commit();store=new ProjectStore(context);project=store.create("Allowed");privateProject=store.create("Private");
        for(String id:new String[]{"allowed","private"}){ProjectStore.Asset a=new ProjectStore.Asset();a.id=id;a.mime="image/png";a.name=id;a.uri="file:///"+id+".png";project.assets.add(a);ProjectStore.Clip c=new ProjectStore.Clip();c.id=id;c.assetId=id;c.outMs=1000;c.startMs="allowed".equals(id)?0:1000;project.clips.add(c);}store.save(project);
    }
    private void selected(){prefs.edit().putString("permission_mode","selected_assets").putString("allowed_project_id",project.id).putString("allowed_asset_ids",new JSONArray().put("allowed").toString()).commit();}
    @Test public void storageDiscoveryIsBoundToThePermittedProjectInEveryRestrictedMode()throws Exception{
        for(String mode:new String[]{"project","selected_assets","one_file"}){
            selected();prefs.edit().putString("permission_mode",mode).putString("allowed_asset_id","allowed").commit();OwnerAccessPolicy policy=new OwnerAccessPolicy(context,store);
            assertTrue(mode,policy.allows("storage_profiles",new JSONObject().put("projectId",project.id)));
            assertFalse(policy.allows("storage_profiles",new JSONObject()));assertFalse(policy.allows("storage_profiles",new JSONObject().put("projectId",privateProject.id)));
        }
    }
    @Test public void storageReplicationUsesTheCurrentSelectedAssetScope()throws Exception{
        selected();OwnerAccessPolicy policy=new OwnerAccessPolicy(context,store);
        JSONObject request=new JSONObject().put("projectId",project.id).put("assetId","allowed");assertTrue(policy.allows("vault_replicate",request));assertTrue(policy.allows("vault_restore",request));
        request.put("assetId","private");assertFalse(policy.allows("vault_replicate",request));assertFalse(policy.allows("vault_restore",request));
        request.put("projectId",privateProject.id).put("assetId","allowed");assertFalse(policy.allows("vault_replicate",request));assertFalse(policy.allows("vault_restore",request));
    }
    @Test public void selectedMediaDoesNotExposeOtherAssetsJobResults()throws Exception{
        selected();OwnerAccessPolicy policy=new OwnerAccessPolicy(context,store);
        JSONObject job=new JSONObject().put("projectId",project.id).put("origin","owner").put("result",new JSONObject().put("uri","file:///private-vault-manifest"));
        assertFalse("Legacy unbound jobs fail closed",policy.allowsJob(job));
        job.put("inputAssetIds",new JSONArray().put("private"));assertFalse(policy.allowsJob(job));
        job.put("inputAssetIds",new JSONArray().put("allowed").put("private"));assertFalse(policy.allowsJob(job));
        job.put("inputAssetIds",new JSONArray().put("allowed"));assertTrue(policy.allowsJob(job));
        prefs.edit().putString("allowed_asset_ids","[]").commit();assertFalse("Current scope wins over saved scope",policy.allowsJob(job));
    }
    @Test public void projectScopeDoesNotChangeWhenOwnerOpensAnotherProject()throws Exception{
        prefs.edit().putString("permission_mode","project").putString("allowed_project_id",project.id).commit();store.setActive(privateProject.id);
        OwnerAccessPolicy p=new OwnerAccessPolicy(context,store);assertTrue(p.projectAllowed(project.id));assertFalse(p.projectAllowed(privateProject.id));assertEquals(1,p.summaries().getJSONArray("projects").length());
    }
    @Test public void filteredGraphDoesNotExposeUnselectedMediaOrExport()throws Exception{
        selected();project.latestExportUri="file:///all-private.mp4";project.sourcePrompt="Private context";
        JSONObject graph=new OwnerAccessPolicy(context,store).redactProject(project);assertEquals(1,graph.getJSONArray("assets").length());assertEquals("allowed",graph.getJSONArray("assets").getJSONObject(0).getString("id"));assertEquals(1,graph.getJSONArray("clips").length());assertEquals("",graph.getString("latestExportUri"));assertEquals("",graph.getString("sourcePrompt"));
    }
    @Test public void selectedAssetsCannotUseHistoryOrRippleToChangeOtherMedia()throws Exception{
        selected();OwnerAccessPolicy p=new OwnerAccessPolicy(context,store);JSONObject request=new JSONObject().put("projectId",project.id).put("operation","remove_clip").put("args",new JSONObject().put("clipId","allowed").put("ripple",true));assertFalse(p.allows("editor_operation",request));assertFalse(p.allows("editor_history",request));
        request.put("operation","set_property").put("args",new JSONObject().put("clipId","private").put("property","volume").put("value",.5));assertFalse(p.allows("editor_operation",request));
        request.getJSONObject("args").put("clipId","allowed");assertTrue(p.allows("editor_operation",request));
    }
    @Test public void nativeFacadeAppliesScopeEvenWhenRelayIsBypassed()throws Exception{
        selected();EditorProtocol nativeEditor=new EditorProtocol(context,store);JSONObject query=nativeEditor.execute("project_query",new JSONObject().put("projectId",project.id));assertEquals(1,query.getInt("clipCount"));assertEquals(1,query.getJSONObject("project").getJSONArray("assets").length());
        JSONObject edit=new JSONObject().put("projectId",project.id).put("expectedRevision",project.revision).put("commandId","scope-bypass-001").put("operation","rename_project").put("args",new JSONObject().put("name","Should fail"));
        try{nativeEditor.execute("editor_operation",edit);fail("Scope bypassed");}catch(SecurityException expected){}assertEquals("Allowed",store.get(project.id).name);
    }
    @Test public void terminalResultsFromAnEarlierScopeCannotBeReplayed()throws Exception{
        selected();prefs.edit().putLong("permission_scope_updated_at",2000).commit();OwnerAccessPolicy p=new OwnerAccessPolicy(context,store);assertTrue(p.resultPredatesScope(new JSONObject().put("createdAt","1970-01-01T00:00:01Z")));assertFalse(p.resultPredatesScope(new JSONObject().put("createdAt","1970-01-01T00:00:03Z")));
    }
}
