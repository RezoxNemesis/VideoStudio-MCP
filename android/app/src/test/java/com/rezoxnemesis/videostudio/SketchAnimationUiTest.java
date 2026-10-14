package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAnimationUiTest {
    @Test public void preparedSketchOffersAnActualAnimationWorkspace()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id="anim_ui_"+UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create"));
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id))){
            SketchStudioActivity studio=controller.get();studio.getPreferences(0).edit().putBoolean("watchLiveDrawing",false).remove("lastBookId").commit();controller.create().start().resume().visible();settle(studio);
            Method menu=studio.getClass().getDeclaredMethod("moreMenu");menu.setAccessible(true);menu.invoke(studio);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();boolean present=false;
            for(int i=0;i<dialog.getListView().getCount();i++)if("2D animation".equals(dialog.getListView().getAdapter().getItem(i)))present=true;
            assertTrue("Prepared drawings need a human-accessible animation workspace",present);
        }
    }
    @Test public void poseEditsKeepEarlierKeysAndReplaceOnlyTheCurrentJoint()throws Exception {
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId","pose_source").put("frameCount",1));
        Class<?> model=Class.forName("com.rezoxnemesis.videostudio.SketchAnimationUiModel");
        JSONObject program=(JSONObject)model.getDeclaredMethod("createProgram",JSONObject.class).invoke(null,source);
        Method write=model.getDeclaredMethod("setRotation",JSONObject.class,String.class,int.class,String.class,double.class,String.class);
        write.invoke(null,program,"character_1",0,"arm_left",15d,"smooth");
        write.invoke(null,program,"character_1",24,"arm_left",60d,"linear");
        write.invoke(null,program,"character_1",24,"head",-10d,"linear");
        org.json.JSONArray poses=program.getJSONArray("rigs").getJSONObject(0).getJSONArray("poses");
        assertEquals(2,poses.length());assertEquals(15d,poses.getJSONObject(0).getJSONObject("rotations").getDouble("arm_left"),0);
        JSONObject keyed=poses.getJSONObject(1).getJSONObject("rotations");assertEquals(60d,keyed.getDouble("arm_left"),0);assertEquals(-10d,keyed.getDouble("head"),0);
        assertEquals(1,source.getInt("frameCount"));assertEquals(100,program.getInt("frameCount"));
    }

    @Test public void humanSceneBakeCreatesAnEditableAnimationAndLeavesPreparedArtworkUntouched()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id="prepared_ui_"+UUID.randomUUID().toString().replace("-","");
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId",id).put("width",320).put("height",480).put("frameCount",1));
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(new JSONObject().put("id","prepared_arm").put("kind","line").put("frame",0).put("exposure",1).put("points",new org.json.JSONArray("[[0.38,0.38,1],[0.30,0.50,1]]")).put("color","#FF29D6BA").put("width",.018).put("fill",false).put("transform",SketchDocument.transform()).put("keys",new org.json.JSONArray()));
        try(SketchProjectStore store=new SketchProjectStore(context)){store.importOwnedDocument(source,"seed");}
        try(ActivityController<SketchAnimationActivity> controller=Robolectric.buildActivity(SketchAnimationActivity.class,new Intent(context,SketchAnimationActivity.class).putExtra("sketchId",id))){
            controller.create().start().resume().visible();SketchAnimationActivity studio=controller.get();settle(studio);
            JSONObject program=(JSONObject)get(studio,"program"),rig=program.getJSONArray("rigs").getJSONObject(0);
            rig.getJSONArray("bindings").put(new JSONObject().put("objectId","prepared_arm").put("jointId","arm_left"));
            SketchAnimationUiModel.setRotation(program,"character_1",0,"arm_left",0,"smooth");SketchAnimationUiModel.setRotation(program,"character_1",99,"arm_left",60,"linear");
            invoke(studio,"changed");invoke(studio,"bake");settle(studio);
            assertEquals(1L,get(studio,"revision"));assertFalse((Boolean)get(studio,"saving"));
            AlertDialog result=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(result);assertEquals("Editable animation baked",org.robolectric.Shadows.shadowOf(result).getTitle());
            try(SketchProjectStore store=new SketchProjectStore(context)){
                assertEquals(SketchDocument.canonical(source),SketchDocument.canonical(store.get(id)));
                org.json.JSONArray list=store.list();JSONObject animated=null;for(int i=0;i<list.length();i++){String output=list.getJSONObject(i).getString("sketchId");if(output.startsWith("animated_"))animated=store.get(output);}
                assertNotNull(animated);assertEquals(100,animated.getInt("frameCount"));assertEquals(60,SketchDocument.transformAt(SketchDocument.object(animated,"prepared_arm"),99).getDouble("rotation"),.0001);assertEquals("#FF29D6BA",SketchDocument.object(animated,"prepared_arm").getString("color"));
            }
        }
    }
    @Test public void vertexWeightChangesCoverEveryPointAndDoNotShareMutableMaps()throws Exception {
        JSONObject binding=new JSONObject().put("objectId","cloth").put("jointId","chest");
        SketchAnimationUiModel.setVertexBlend(binding,3,1,"chest","hand_left",.25);
        org.json.JSONArray values=binding.getJSONArray("weights");assertEquals(3,values.length());assertEquals(1,values.getJSONObject(0).getInt("chest"));assertEquals(.75,values.getJSONObject(1).getDouble("hand_left"),0);assertEquals(1,values.getJSONObject(2).getInt("chest"));
        SketchAnimationUiModel.setVertexBlend(binding,3,0,"chest","hand_left",0);assertFalse(values.getJSONObject(0).has("chest"));assertEquals(.25,values.getJSONObject(1).getDouble("chest"),0);
        try{SketchAnimationUiModel.setVertexBlend(binding,3,1,"chest","hand_left",Double.NaN);fail("NaN weight admitted");}catch(IllegalArgumentException expected){}
    }
    @Test public void bundledExampleIsTheVerifiedFixtureAndCreatesIndependentRealNativeScenes()throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        for(String name:new String[]{"rooftop-relay-source","rooftop-relay-program"})try(java.io.InputStream bundled=context.getAssets().open("sketch-animation/"+name+".json");java.io.InputStream tested=getClass().getResourceAsStream("/sketch-animation/"+name+".json")){assertArrayEquals(tested.readAllBytes(),bundled.readAllBytes());}
        ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();try{
            JSONObject first=worker.submit(()->SketchAnimationExample.create(context)).get(30,TimeUnit.SECONDS),second=worker.submit(()->SketchAnimationExample.create(context)).get(30,TimeUnit.SECONDS);
            assertNotEquals(first.getString("animationId"),second.getString("animationId"));assertNotEquals(first.getString("sketchId"),second.getString("sketchId"));
            JSONObject loaded=SketchAnimationOps.load(context,first.getString("animationId"));assertEquals(100,loaded.getJSONObject("program").getInt("frameCount"));assertTrue(loaded.getJSONObject("report").getBoolean("independentArticulation"));
            try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(1,sketches.get(first.getString("sketchId")).getLong("revision"));assertEquals(1,sketches.get(second.getString("sketchId")).getLong("revision"));}
        }finally{worker.shutdown();}
    }
    @Test public void playbackDisplaysCompletedFramesEvenWhenTheClockMovesAheadOfAnInflightPreview()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id="playback_ui_"+UUID.randomUUID().toString().replace("-","");
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("frameCount",1));
        try(ActivityController<SketchAnimationActivity> controller=Robolectric.buildActivity(SketchAnimationActivity.class,new Intent(context,SketchAnimationActivity.class).putExtra("sketchId",id))){
            controller.create().start().resume().visible();SketchAnimationActivity studio=controller.get();settle(studio);invoke(studio,"stopPlayback");
            Field mapping=studio.getClass().getDeclaredField("mapping");mapping.setAccessible(true);mapping.set(studio,false);
            Field frame=studio.getClass().getDeclaredField("frame");frame.setAccessible(true);frame.set(studio,2);
            Field playing=studio.getClass().getDeclaredField("playing");playing.setAccessible(true);playing.set(studio,true);
            java.util.concurrent.CountDownLatch blocked=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);ExecutorService io=(ExecutorService)get(studio,"io");
            io.submit(()->{blocked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(blocked.await(10,TimeUnit.SECONDS));
            ((Runnable)get(studio,"previewTick")).run();assertTrue((Boolean)get(studio,"previewInFlight"));frame.set(studio,9);
            java.util.concurrent.CountDownLatch firstRendered=new java.util.concurrent.CountDownLatch(1),holdNext=new java.util.concurrent.CountDownLatch(1);
            io.submit(()->{firstRendered.countDown();try{holdNext.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});release.countDown();assertTrue(firstRendered.await(10,TimeUnit.SECONDS));
            try{ShadowLooper.idleMainLooper();assertEquals("Slow native rendering must still display progress instead of rejecting every playing frame",2,get(studio,"displayedFrame"));playing.set(studio,false);}finally{holdNext.countDown();}
        }
    }
    @Test public void humanRotationControlsKeepTheFullNativeMultipleTurnRangeEditable()throws Exception {
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId","full_rotation").put("frameCount",1)),program=SketchAnimationUiModel.createProgram(source);
        SketchAnimationUiModel.setRotation(program,"character_1",0,"head",-3600,"linear");
        SketchAnimationUiModel.setRotation(program,"character_1",99,"head",3600,"linear");
        assertEquals(-3600,SketchAnimationUiModel.previousPose(SketchAnimationUiModel.rig(program,"character_1"),0).getJSONObject("rotations").getDouble("head"),0);
        assertEquals(3600,SketchAnimationUiModel.previousPose(SketchAnimationUiModel.rig(program,"character_1"),99).getJSONObject("rotations").getDouble("head"),0);
        SketchAnimationCompiler.preview(source,program,50,source.getString("id"));
        assertEquals(3600,SketchAnimationUiModel.nearestRotation(0,3600),0);assertEquals(-3590,SketchAnimationUiModel.nearestRotation(10,-3600),0);
        try{SketchAnimationUiModel.setRotation(program,"character_1",99,"head",3600.01,"linear");fail("Out-of-range rotation admitted");}catch(IllegalArgumentException expected){}
    }
    private static Object get(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void invoke(Object target,String name)throws Exception {Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}
    private static void settle(Object studio)throws Exception {Field field=studio.getClass().getDeclaredField("io");field.setAccessible(true);for(int i=0;i<4;i++){((ExecutorService)field.get(studio)).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
}
