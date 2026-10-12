package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StoryboardRecoveryProofTest {
    @Test public void recreationKeepsNativeAcceptedProofAndCommandIdentityButDropsOtherInternalFields()throws Exception{
        RecoveryPlanStore plans=new RecoveryPlanStore(RuntimeEnvironment.getApplication());
        JSONObject accepted=new JSONObject().put("manifestSha256","fixture-source-digest");
        JSONObject bindings=new JSONObject().put("bindingSha256","fixture-rig-digest");
        String id=plans.begin("animate_storyboard_series",new JSONObject().put("assetIds",new org.json.JSONArray().put("a").put("b"))
                .put("_mcpCommandId","trusted-native-command").put("_storyboardSourceManifest",accepted)
                .put("_storyboardAdmission",bindings).put("_untrustedOverride","must disappear"),"source-project");
        JSONObject recovered=new RecoveryPlanStore(RuntimeEnvironment.getApplication()).get(id).getJSONObject("parameters");
        assertEquals("trusted-native-command",recovered.getString("_mcpCommandId"));
        assertEquals(accepted.toString(),recovered.getJSONObject("_storyboardSourceManifest").toString());
        assertEquals(bindings.toString(),recovered.getJSONObject("_storyboardAdmission").toString());
        assertFalse(recovered.has("_untrustedOverride"));
    }
    @Test public void outputCheckpointIsBoundToTheAcceptedTargetAndSurvivesRecreation()throws Exception{
        RecoveryPlanStore plans=new RecoveryPlanStore(RuntimeEnvironment.getApplication());
        String id=plans.begin("animate_storyboard_series",new JSONObject().put("outputProjectId","accepted-target"),"accepted-target");
        plans.attachJob(id,"render-job");
        try{plans.checkpointStoryboardOutputForJob("render-job","other-project",2);fail("Foreign output cannot acquire a recovery proof");}
        catch(IllegalStateException expected){}
        plans.checkpointStoryboardOutputForJob("render-job","accepted-target",42);
        assertEquals(42,new RecoveryPlanStore(RuntimeEnvironment.getApplication()).get(id).getJSONObject("parameters").getLong("_storyboardOutputUpdatedAt"));
    }
}
