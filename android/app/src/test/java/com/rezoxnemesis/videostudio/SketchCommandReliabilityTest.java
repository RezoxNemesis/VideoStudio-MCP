package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchCommandReliabilityTest {
    @Test public void everySketchAliasUsesTheAuthoringWorkerInsteadOfBlockingTheUi() throws Exception {
        JSONArray actions=NativeWorkbenchBridge.capabilities().getJSONArray("canonicalActions");for(int i=0;i<actions.length();i++){String action=actions.getString(i);if(action.startsWith("sketch_"))assertTrue(action+" must leave the main thread",NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));}
    }
    @Test public void nativeMemoryFailureBecomesTerminalInsteadOfLeavingAnAgentWaiting() throws Exception {
        JobManager manager=new JobManager(RuntimeEnvironment.getApplication(),"sketch_failure_fixture");try{JobManager.Job job=manager.submit("Synthetic native allocation failure",JobManager.Kind.LIGHT,state->{throw new OutOfMemoryError("synthetic memory failure");});try{job.future.get(5,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException expected){}assertEquals(JobManager.STATE_FAILED,job.state);}finally{manager.shutdown();}
    }
    @Test public void nativeSuccessHasAnExplicitAckAndRepeatedStrokeBatchIsNotDuplicated() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();JSONObject create=SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));assertTrue(create.getBoolean("ok"));
        JSONObject args=new JSONObject().put("sketchId",id).put("operationId","stroke").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","line").put("kind","stroke").put("points",new JSONArray("[[0.2,0.2,1],[0.8,0.8,1]]")))));
        JSONObject first=SketchEditOps.execute(c,"sketch_edit",args),retry=SketchEditOps.execute(c,"sketch_edit",args);assertTrue(retry.getBoolean("ok"));assertEquals(first.getLong("revision"),retry.getLong("revision"));try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(1,SketchDocument.layer(store.get(id),"layer_1").getJSONArray("objects").length());assertEquals(1,store.liveEvents(id,0,8).getJSONArray("events").length());}
    }
}
