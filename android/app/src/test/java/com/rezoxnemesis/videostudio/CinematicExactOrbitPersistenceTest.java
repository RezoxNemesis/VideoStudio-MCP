package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class CinematicExactOrbitPersistenceTest {
 static JSONObject operation(String shot)throws Exception{return new JSONObject().put("type","set_camera_orbit").put("shotId",shot).put("orbit",CinematicExactOrbitTest.orbit(25,385,0,0));}
 @Test public void editReopenAndReadOnlySampleKeepSourceAndBakedSceneProof()throws Exception{
  Cinematic3DStoreTest.Fixture f=new Cinematic3DStoreTest.Fixture("sources");JSONObject first=Cinematic3DProgramStore.save(f.c,f.projects,f.saveRequest(f.install()));String original=first.toString();
  JSONObject request=new JSONObject().put("projectId",f.project.id).put("programId",first.getString("programId")).put("expectedProgramSha256",first.getString("sha256")).put("sourceProjectUpdatedAt",f.project.updatedAt).put("operations",new JSONArray().put(operation("clash")));
  JSONObject next=Cinematic3DProgramStore.edit(f.c,f.projects,request);assertNotEquals(first.getString("sha256"),next.getString("sha256"));assertEquals(original,first.toString());
  for(String field:new String[]{"sourceEntries","sourceRegistrationSha256","scenes","bundleSha256","bundleReceiptSha256"})assertEquals(field,Cinematic3DTimeline.canonical(first.get(field)),Cinematic3DTimeline.canonical(next.get(field)));
  JSONObject get=new JSONObject().put("projectId",f.project.id).put("programId",first.getString("programId")).put("expectedProgramSha256",next.getString("sha256")).put("sampleFrame",0);
  JSONObject reopened=Cinematic3DProgramStore.get(f.c,f.projects,get),sample=reopened.getJSONObject("cameraSample");assertEquals(25,sample.getJSONObject("camera").getDouble("azimuthDegrees"),0);assertTrue(sample.getBoolean("readOnly"));assertFalse(sample.getBoolean("rendered"));assertEquals(next.getString("sha256"),sample.getString("programSha256"));
  get.remove("sampleFrame");assertEquals(next.getString("sha256"),Cinematic3DProgramStore.get(f.c,f.projects,get).getString("sha256"));
  JSONObject old=new JSONObject(get.toString()).put("expectedProgramSha256",first.getString("sha256")).put("sampleFrame",0);assertThrows(Exception.class,()->Cinematic3DProgramStore.get(f.c,f.projects,old));
  JSONObject unbound=new JSONObject(get.toString()).put("sampleFrame",0);unbound.remove("expectedProgramSha256");assertThrows(Exception.class,()->Cinematic3DProgramStore.get(f.c,f.projects,unbound));
  JSONObject outside=new JSONObject(get.toString()).put("sampleFrame",11999);assertThrows(Exception.class,()->Cinematic3DProgramStore.get(f.c,f.projects,outside));
 }
 @Test public void clearingAnOrbitIsRequiredBeforeCartesianKeysAndNeverChangesActorData()throws Exception{
  JSONObject before=Cinematic3DStudioActivityTest.timeline();String original=before.toString();JSONObject edited=Cinematic3DCameraEditOps.apply(before,new JSONArray().put(operation("impact")));assertEquals(original,before.toString());
  JSONObject a=new JSONObject(before.toString()),b=new JSONObject(edited.toString());a.getJSONArray("shots").getJSONObject(1).remove("camera");b.getJSONArray("shots").getJSONObject(1).remove("camera");assertEquals(Cinematic3DTimeline.canonical(a),Cinematic3DTimeline.canonical(b));
  assertThrows(Exception.class,()->Cinematic3DCameraEditOps.apply(edited,new JSONArray().put(Cinematic3DCameraEditOpsTest.key("impact",0,1))));
  JSONObject cleared=Cinematic3DCameraEditOps.apply(edited,new JSONArray().put(new JSONObject().put("type","clear_camera_orbit").put("shotId","impact")).put(Cinematic3DCameraEditOpsTest.key("impact",0,1)));assertFalse(cleared.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").has("orbit"));
 }
 @Test public void authoredCameraLocksRejectBothOrbitEditsAndMisleadingSampleReads()throws Exception{
  Cinematic3DStoreTest.Fixture f=new Cinematic3DStoreTest.Fixture("authoredCamera");JSONObject first=Cinematic3DProgramStore.save(f.c,f.projects,f.saveRequest(f.install()));
  JSONObject identity=new JSONObject().put("projectId",f.project.id).put("programId",first.getString("programId")).put("expectedProgramSha256",first.getString("sha256"));
  Exception sample=assertThrows(IllegalArgumentException.class,()->Cinematic3DProgramStore.get(f.c,f.projects,new JSONObject(identity.toString()).put("sampleFrame",0)));
  assertTrue(sample.getMessage().contains("AUTHORED_CAMERA_SAMPLE_UNAVAILABLE"));
  Exception edit=assertThrows(IllegalArgumentException.class,()->Cinematic3DProgramStore.edit(f.c,f.projects,new JSONObject(identity.toString()).put("sourceProjectUpdatedAt",f.project.updatedAt).put("operations",new JSONArray().put(operation("clash")))));
  assertTrue(edit.getMessage().contains("BAKED_CAMERA_REBUILD_REQUIRED"));
  assertEquals(first.getString("sha256"),Cinematic3DProgramStore.get(f.c,f.projects,identity).getString("sha256"));
 }
 @Test public void exactCameraSceneSamplesPreserveTheActorClipAndImpactHold()throws Exception{
  JSONObject original=Cinematic3DSceneSamplingTest.shot(361,4),orbited=new JSONObject(original.toString());orbited.getJSONObject("camera").put("shakeIntensity",0).put("orbit",CinematicExactOrbitTest.orbit(0,360,0,0));
  for(int frame=0;frame<361;frame++){
   JSONObject before=Cinematic3DSceneSampling.frame(original,frame,60,Cinematic3DSceneSamplingTest.scene()),after=Cinematic3DSceneSampling.frame(orbited,frame,60,Cinematic3DSceneSamplingTest.scene());
   for(String field:new String[]{"phase","motionSeconds","hitStop","impactFrame","seconds","clipSeconds","cameraSeconds","animationName","cameraName","path","vfx","trailClipSeconds"})assertEquals(field,Cinematic3DTimeline.canonical(before.get(field)),Cinematic3DTimeline.canonical(after.get(field)));
   // Interior angles have normal IEEE754 rounding; actor/source comparisons above stay exact.
   assertEquals(frame,after.getJSONObject("camera").getDouble("azimuthDegrees"),1e-12);
  }
 }
 @Test public void canceledOrbitPreflightRetainsTheWholeOriginalDraft()throws Exception{
  JSONObject original=Cinematic3DStudioActivityTest.timeline();String before=original.toString();java.util.concurrent.atomic.AtomicInteger n=new java.util.concurrent.atomic.AtomicInteger();assertThrows(InterruptedException.class,()->Cinematic3DCameraEditOps.apply(original,new JSONArray().put(operation("impact")),()->n.incrementAndGet()>8));assertEquals(before,original.toString());
 }
}
