package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAnimationUiReviewTest {
    private JSONObject source()throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","ui_review").put("width",320).put("height",480).put("frameCount",1));}
    @Test public void insertingHeadKeyPreservesInterpolatedArmAndRootPoseInsteadOfSnappingToPreviousKey()throws Exception {
        JSONObject program=SketchAnimationUiModel.createProgram(source()),rig=SketchAnimationUiModel.rig(program,"character_1");
        SketchAnimationUiModel.setRotation(program,"character_1",0,"arm_left",0,"smooth");
        SketchAnimationUiModel.setRotation(program,"character_1",20,"arm_left",80,"linear");
        rig.getJSONArray("poses").getJSONObject(1).put("rootX",.2);
        SketchAnimationUiModel.setRotation(program,"character_1",5,"head",-12,"smooth");
        JSONObject inserted=rig.getJSONArray("poses").getJSONObject(1);
        assertEquals(5,inserted.getInt("frame"));assertEquals(12.5,inserted.getJSONObject("rotations").getDouble("arm_left"),1e-9);
        assertEquals(.03125,inserted.getDouble("rootX"),1e-9);assertEquals(-12,inserted.getJSONObject("rotations").getDouble("head"),0);
        assertEquals(80,rig.getJSONArray("poses").getJSONObject(2).getJSONObject("rotations").getDouble("arm_left"),0);
    }
    @Test public void inverseCameraMatchesSolverDisplayCoordinatesOnPortraitCanvasAndSparseKeys()throws Exception {
        JSONObject source=source(),program=SketchAnimationUiModel.createProgram(source());
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","torso").put("kind","line").put("points",new JSONArray().put(new JSONArray().put(.5).put(.56).put(1)).put(new JSONArray().put(.5).put(.38).put(1)))));
        program.getJSONArray("rigs").getJSONObject(0).getJSONArray("bindings").put(new JSONObject().put("objectId","torso").put("jointId","root"));
        program.put("camera",new JSONArray().put(new JSONObject().put("frame",0).put("x",.06).put("y",-.03).put("zoom",1.2).put("rotation",25).put("interpolation","smooth"))
                .put(new JSONObject().put("frame",20).put("x",-.04).put("zoom",1.6).put("rotation",-40)));
        for(int frame:new int[]{0,5,10,19,20}){
            JSONObject joint=SketchAnimationCompiler.sample(source,program,frame).getJSONArray("joints").getJSONObject(0);
            double[] world=SketchAnimationUiModel.canvasToWorld(source,program,frame,joint.getDouble("displayX"),joint.getDouble("displayY"));
            assertEquals(joint.getDouble("x"),world[0],1e-9);assertEquals(joint.getDouble("y"),world[1],1e-9);
        }
    }
    @Test public void insertingEarlierCameraKeyProducesCompilerAcceptedOrder()throws Exception {
        JSONObject source=source(),program=SketchAnimationUiModel.createProgram(source());
        JSONArray unsorted=new JSONArray().put(new JSONObject().put("frame",40).put("zoom",1.1)).put(new JSONObject().put("frame",0).put("zoom",1)).put(new JSONObject().put("frame",20).put("zoom",1.3));
        program.put("camera",SketchAnimationUiModel.sortedFrames(unsorted));
        assertEquals(0,program.getJSONArray("camera").getJSONObject(0).getInt("frame"));
        assertEquals(20,program.getJSONArray("camera").getJSONObject(1).getInt("frame"));
        SketchAnimationCompiler.preview(source,program,21,source.getString("id"));
    }
    @Test public void unsavedSceneSurvivesActivityRecreationWithoutClaimingItWasSaved()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id="review_"+UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("frameCount",1));
        Intent intent=new Intent(context,SketchAnimationActivity.class).putExtra("sketchId",id);Bundle state=new Bundle();String animation;
        try(ActivityController<SketchAnimationActivity> original=Robolectric.buildActivity(SketchAnimationActivity.class,intent).create().start().resume().visible()){
            SketchAnimationActivity activity=original.get();settle(activity);JSONObject program=(JSONObject)get(activity,"program");assertNotNull(program);
            SketchAnimationUiModel.setRotation(program,"character_1",12,"head",-17,"smooth");animation=(String)get(activity,"animationId");
            invoke(activity,"persistDraft");settle(activity);original.saveInstanceState(state).pause().stop();
        }
        try(ActivityController<SketchAnimationActivity> recreated=Robolectric.buildActivity(SketchAnimationActivity.class,intent).create(state).start().resume().visible()){
            SketchAnimationActivity activity=recreated.get();settle(activity);JSONObject program=(JSONObject)get(activity,"program");assertNotNull("An unsaved animation must restore its local draft",program);
            assertEquals(animation,get(activity,"animationId"));assertEquals(0L,get(activity,"revision"));
            assertEquals(-17,SketchAnimationUiModel.previousPose(SketchAnimationUiModel.rig(program,"character_1"),12).getJSONObject("rotations").getDouble("head"),0);
        }
    }
    private static Object get(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void invoke(Object target,String name)throws Exception {Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}
    private static void settle(Object target)throws Exception {ExecutorService io=(ExecutorService)get(target,"io");for(int n=0;n<4;n++){io.submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
}
