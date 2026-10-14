package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class Cinematic3DCameraProgramTest {
    private static JSONObject edit(Cinematic3DStoreTest.Fixture f,JSONObject request) throws Exception {
        try{return (JSONObject)Cinematic3DProgramStore.class.getMethod("edit",Context.class,ProjectStore.class,JSONObject.class).invoke(null,f.c,f.projects,request);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}
    }
    private static JSONObject request(Cinematic3DStoreTest.Fixture f,JSONObject program,double x) throws Exception {
        return new JSONObject().put("projectId",f.project.id).put("programId",program.getString("programId")).put("expectedProgramSha256",program.getString("sha256"))
                .put("sourceProjectUpdatedAt",f.project.updatedAt).put("operations",new JSONArray().put(Cinematic3DCameraEditOpsTest.key("clash",0,x)));
    }
    @Test public void editedProgramPublishesAnImmutableGenerationWithUnchangedSourcesAndSceneBindings() throws Exception {
        Cinematic3DStoreTest.Fixture f=new Cinematic3DStoreTest.Fixture("sources");JSONObject bundle=f.install(),first=Cinematic3DProgramStore.save(f.c,f.projects,f.saveRequest(bundle));String before=Cinematic3DTimeline.canonical(first);
        JSONObject next=edit(f,request(f,first,3));assertNotEquals(first.getString("sha256"),next.getString("sha256"));assertEquals(before,Cinematic3DTimeline.canonical(first));
        for(String key:new String[]{"sourceEntries","sourceRegistrationSha256","scenes","bundleSha256","bundleReceiptSha256"})assertEquals(key,Cinematic3DTimeline.canonical(first.get(key)),Cinematic3DTimeline.canonical(next.get(key)));
        JSONObject timeline=new JSONObject(next.getJSONObject("timeline").toString());timeline.getJSONArray("shots").getJSONObject(0).getJSONObject("camera").remove("keyframes");assertEquals(Cinematic3DTimeline.canonical(first.getJSONObject("timeline")),Cinematic3DTimeline.canonical(timeline));
        File prior=new File(f.c.getFilesDir(),"creative_workspace/projects/"+f.project.id+"/cinematic3d/programs/"+first.getString("programId")+"/"+first.getString("sha256")+".json");assertTrue(prior.isFile());
        assertFalse(next.getBoolean("nativeExecutionVerified"));assertFalse(next.getBoolean("originalImageReconstruction"));
        JSONObject exported=Cinematic3DProgramStore.exportRequest(f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",first.getString("programId")).put("expectedProgramSha256",next.getString("sha256")));assertEquals(Cinematic3DTimeline.canonical(next.getJSONObject("timeline")),Cinematic3DTimeline.canonical(exported.getJSONObject("exportRequest").getJSONObject("timeline")));
    }
    @Test public void staleMissingAndMalformedGuardsNeverReplaceCurrentProgram() throws Exception {
        Cinematic3DStoreTest.Fixture f=new Cinematic3DStoreTest.Fixture();JSONObject bundle=f.install(),first=Cinematic3DProgramStore.save(f.c,f.projects,f.saveRequest(bundle)),next=edit(f,request(f,first,3));
        JSONObject missing=request(f,next,4);missing.remove("expectedProgramSha256");JSONObject empty=request(f,next,4).put("expectedProgramSha256","");JSONObject foreign=request(f,next,4).put("bundleId",bundle.getString("bundleId"));JSONObject staleSource=request(f,next,4).put("sourceProjectUpdatedAt",f.project.updatedAt-1);
        for(JSONObject bad:new JSONObject[]{request(f,first,4),missing,empty,foreign,staleSource}){try{edit(f,bad);fail("A missing/stale guard admitted a camera edit");}catch(IllegalArgumentException|IllegalStateException expected){}}
        JSONObject current=Cinematic3DProgramStore.get(f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",next.getString("programId")));assertEquals(next.getString("sha256"),current.getString("sha256"));
    }
    @Test public void concurrentEditorsWithTheSameShaPublishExactlyOneCameraGeneration() throws Exception {
        Cinematic3DStoreTest.Fixture f=new Cinematic3DStoreTest.Fixture();JSONObject bundle=f.install(),first=Cinematic3DProgramStore.save(f.c,f.projects,f.saveRequest(bundle));ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{Future<Boolean> a=workers.submit(()->{start.await();try{edit(f,request(f,first,3));return true;}catch(IllegalStateException conflict){assertTrue(conflict.getMessage().contains("SOURCE_PROGRAM_CHANGED"));return false;}}),b=workers.submit(()->{start.await();try{edit(f,request(f,first,4));return true;}catch(IllegalStateException conflict){assertTrue(conflict.getMessage().contains("SOURCE_PROGRAM_CHANGED"));return false;}});start.countDown();assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}finally{workers.shutdownNow();}
    }
    @Test public void fullSaveAlsoRejectsAnInterpolatedInvalidCameraBeforePublishing() throws Exception {
        Cinematic3DStoreTest.Fixture f=new Cinematic3DStoreTest.Fixture();JSONObject bundle=f.install(),first=Cinematic3DProgramStore.save(f.c,f.projects,f.saveRequest(bundle)),timeline=new JSONObject(first.getJSONObject("timeline").toString());JSONObject shot=timeline.getJSONArray("shots").getJSONObject(0);shot.getJSONObject("camera").put("shakeIntensity",0).put("keyframes",new JSONArray().put(new JSONObject().put("phase",0).put("position",new JSONArray(new double[]{0,1,2})).put("target",new JSONArray(new double[]{0,1,0}))).put(new JSONObject().put("phase",1).put("position",new JSONArray(new double[]{0,1,-2})).put("target",new JSONArray(new double[]{0,1,0}))));
        try{Cinematic3DProgramStore.save(f.c,f.projects,f.saveRequest(bundle).put("programId",first.getString("programId")).put("expectedProgramSha256",first.getString("sha256")).put("timeline",timeline));fail("A full replacement bypassed output camera preflight");}catch(IllegalArgumentException expected){}
        assertEquals(first.getString("sha256"),Cinematic3DProgramStore.get(f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",first.getString("programId"))).getString("sha256"));
    }
}
