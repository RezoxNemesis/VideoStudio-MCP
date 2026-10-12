package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class Cinematic3DServiceAdmissionTest {
 @Test public void recoveryAdmissionCrashRemainsEligibleAndCanBeCancelled()throws Exception{
  RecoveryPlanStore plans=new RecoveryPlanStore(RuntimeEnvironment.getApplication());String id=plans.begin("cinematic_3d_render",new JSONObject().put("projectId","output_retry"),"output_retry");plans.markResuming(id);
  boolean found=false;org.json.JSONArray pending=plans.pendingForAutoResume();for(int i=0;i<pending.length();i++)if(id.equals(pending.getJSONObject(i).getString("id")))found=true;assertTrue("Process death before worker attachment must not strand the saved plan",found);
  plans.cancelActive();assertEquals("cancelled",plans.get(id).getString("state"));
 }
 @Test public void recoveryRetainsAcceptedEmptyOutputRevisionAndUpdatesOnlyItsGeneration()throws Exception{
  RecoveryPlanStore plans=new RecoveryPlanStore(RuntimeEnvironment.getApplication());String output="output_accepted",job="cinematic_job";
  JSONObject params=new JSONObject().put("projectId",output).put("outputProjectId",output).put("sourceProjectId","source_accepted").put("_cinematicOutputUpdatedAt",123L).put("_untrustedProof","discard");
  String id=plans.begin("cinematic_3d_render",params,output);assertEquals(123L,plans.get(id).getJSONObject("parameters").getLong("_cinematicOutputUpdatedAt"));assertFalse(plans.get(id).getJSONObject("parameters").has("_untrustedProof"));
  plans.attachJob(id,job);plans.checkpointCinematicOutputForJob(job,output,124L);assertEquals(124L,plans.get(id).getJSONObject("parameters").getLong("_cinematicOutputUpdatedAt"));
  try{plans.checkpointCinematicOutputForJob(job,"foreign_output",999L);fail("Foreign recovery target admitted");}catch(IllegalStateException expected){}
  assertEquals(124L,plans.get(id).getJSONObject("parameters").getLong("_cinematicOutputUpdatedAt"));
 }
 @Test public void unknownProgramCannotCreateOutputOrEnterTheGpuLane()throws Exception{
  ControlService service=Robolectric.buildService(ControlService.class).get();ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project source=store.create("Preserve source");int count=store.list().size();
  Field field=ControlService.class.getDeclaredField("store");field.setAccessible(true);field.set(service,store);
  Method queue=ControlService.class.getDeclaredMethod("queueCinematic3D",JSONObject.class);queue.setAccessible(true);
  for(JSONObject request:new JSONObject[]{new JSONObject().put("projectId",source.id).put("programId","missing_program"),new JSONObject().put("projectId",source.id).put("programId","missing_program").put("outputProjectId",source.id),new JSONObject().put("projectId",source.id).put("programId","missing_program").put("width",1280).put("height",1280)}){
   try{queue.invoke(service,request);fail("Unadmitted program reached effects");}catch(InvocationTargetException e){assertTrue(e.getCause() instanceof IllegalArgumentException||e.getCause() instanceof IllegalStateException);}
   assertEquals(count,store.list().size());assertEquals(0,store.get(source.id).clips.size());
  }
 }
 @Test public void completedRenderNeedsValidatedMediaAndCancelledOtherJobsCannotCancelIt()throws Exception{
  assertTrue(ExecutionTruthPolicy.requiresValidatedMediaOutput("cinematic_3d_render"));
  ControlService service=Robolectric.buildService(ControlService.class).get();Field owner=ControlService.class.getDeclaredField("activeRenderJobId");owner.setAccessible(true);owner.set(service,"render_owner");
  Method cancel=ControlService.class.getDeclaredMethod("cancelOwnedRender",String.class);cancel.setAccessible(true);assertEquals(false,cancel.invoke(service,"different_job"));assertEquals("render_owner",owner.get(service));
 }
}
