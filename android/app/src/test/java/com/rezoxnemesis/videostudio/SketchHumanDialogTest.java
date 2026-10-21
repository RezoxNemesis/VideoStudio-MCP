package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Real manual controls, retained native rigs/pixels, imported timing and revision-bound storage. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanDialogTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};

    @Test public void defaultControlsDispatchTheSharedNativeContractWithoutChangingTheSource()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=json(doc);List<JSONObject> requests=new ArrayList<>();
            open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();
            assertEquals(6,spinner(form,"Human view").getCount());assertEquals(2,spinner(form,"Adult build").getCount());assertEquals(2,spinner(form,"Hair style").getCount());assertEquals(24,spinner(form,"Starting action").getCount());
            assertFalse(check(form,"Edit custom poses").isChecked());assertFalse(check(form,"Timed speech").isChecked());
            for(String channel:SketchHumanFigure.POSE_CHANNELS)assertNotNull(edit(form,channel));
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());JSONObject op=operation(requests.get(0));
            assertEquals("add_human_animation",op.getString("type"));assertTrue(op.getString("actorId").length()<=20);assertEquals("listen",op.getString("preset"));
            assertEquals(json(SketchHumanFigure.defaults()),json(op.getJSONObject("settings")));assertFalse(op.has("poses"));assertFalse(op.has("speech"));assertEquals(before,json(doc));
        }
    }

    @Test public void allSettingsAndEveryStartingActionReachTheNewLayerRequest()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(a.get(),document(),(r,c)->{requests.add(r);c.accept(new IllegalStateException("Keep controls for review"));});View form=root();
            spinner(form,"Human view").setSelection(2);spinner(form,"Adult build").setSelection(1);spinner(form,"Hair style").setSelection(1);
            JSONObject values=new JSONObject().put("x",.43).put("floorY",.82).put("size",.8).put("skinColor","#FF98674E").put("hairColor","#FF382A22").put("coatColor","#FF62553A").put("trouserColor","#FF414B52");
            setFields(form,values);ShadowLooper.idleMainLooper();
            for(int i=0;i<SketchHumanPresets.NAMES.length;i++){
                spinner(form,"Starting action").setSelection(i);ShadowLooper.idleMainLooper();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                JSONObject op=operation(requests.get(i)),settings=op.getJSONObject("settings");assertEquals(SketchHumanPresets.NAMES[i],op.getString("preset"));assertFalse(op.has("poses"));
                assertEquals("right",settings.getString("view"));assertEquals("broad",settings.getString("build"));assertEquals("long",settings.getString("hairStyle"));
                Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();assertEquals(values.get(key).toString(),settings.get(key).toString());}
            }
            dialog().cancel();
        }
    }

    @Test public void allExposedPoseControlsAndBezierEditsAreSentAsOrderedCustomKeys()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->{requests.add(r);c.accept(null);});View form=root();custom(form);
            JSONObject values=authoredPose();assertEquals(SketchHumanFigure.POSE_CHANNELS.length,values.length());setFields(form,values);spinner(form,"Pose timing").setSelection(6);edit(form,"Bezier controls (JSON array)").setText("[0.2,0.1,0.8,0.9]");ShadowLooper.idleMainLooper();
            click(form,"Apply pose");assertPose(values,poses(owner).getJSONObject(0));
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();JSONObject op=operation(requests.get(0));JSONArray keys=op.getJSONArray("poses");SketchHumanAnimation.validatePoses(keys,120);
            assertPose(values,keys.getJSONObject(0));assertEquals("bezier",keys.getJSONObject(0).getString("interpolation"));assertEquals("[0.2,0.1,0.8,0.9]",keys.getJSONObject(0).getJSONArray("curve").toString());assertEquals(json(poses(owner)),json(keys));
        }
    }

    @Test public void addEditDeleteAndResetKeepCustomKeysOrderedAndProtectTheStartingKey()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Editing keys must not submit"));View form=root();custom(form);int count=poses(owner).length();
            click(form,"Delete pose");assertEquals(count,poses(owner).length());
            int firstFree=unusedFrame(poses(owner),120);edit(form,"Pose frame (1 based)").setText(String.valueOf(firstFree+1));edit(form,"headTilt").setText("-7");click(form,"Add pose");assertEquals(count+1,poses(owner).length());
            int moved=unusedFrame(poses(owner),120);edit(form,"Pose frame (1 based)").setText(String.valueOf(moved+1));edit(form,"headTilt").setText("-9");click(form,"Apply pose");assertEquals(-1,indexAt(poses(owner),firstFree));assertEquals(-9,poses(owner).getJSONObject(indexAt(poses(owner),moved)).getDouble("headTilt"),0);
            spinner(form,"Selected pose").setSelection(0);ShadowLooper.idleMainLooper();spinner(form,"Selected pose").setSelection(indexAt(poses(owner),moved));ShadowLooper.idleMainLooper();assertEquals(-9,Double.parseDouble(edit(form,"headTilt").getText().toString()),0);
            click(form,"Use selected pose frame");assertEquals(String.valueOf(moved+1),edit(form,"Preview frame (1 based)").getText().toString());click(form,"Delete pose");assertEquals(count,poses(owner).length());
            spinner(form,"Starting action").setSelection(Arrays.asList(SketchHumanPresets.NAMES).indexOf("bow"));ShadowLooper.idleMainLooper();click(form,"Reset poses from action");assertEquals(json(SketchHumanPresets.create("bow",120,30)),json(poses(owner)));dialog().cancel();
        }
    }

    @Test public void invalidPoseFieldsCurvesAndFramesRejectWithoutSubmittingOrReplacingKeys()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->requests.add(r));View form=root();custom(form);String before=json(poses(owner));
            for(String[] invalid:new String[][]{{"bodyLean","26"},{"headTilt","-21"},{"gazeX","1.1"},{"gazeY","-1.1"},{"blink","1.1"},{"mouth","-0.1"},{"mouthWide","-1.1"},{"mouthRound","1.1"},{"mouthTeeth","1.1"},{"mouthTongue","-0.1"},{"smile","1.1"},{"brow","-1.1"},{"rootX","4097"},{"rootY","NaN"},{"rootZ","bad"},{"hipZ","Infinity"},{"nearHandZ","4097"},{"farHandZ","-4097"},{"footLeftZ","4097"},{"footRightZ","-4097"}}){
                EditText field=edit(form,invalid[0]);String old=field.getText().toString();field.setText(invalid[1]);dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue("Invalid field "+invalid[0],requests.isEmpty());assertEquals(invalid[1],field.getText().toString());assertEquals(before,json(poses(owner)));field.setText(old);
            }
            for(String value:new String[]{"0","121","1.5","bad"}){edit(form,"Pose frame (1 based)").setText(value);click(form,"Add pose");assertEquals(before,json(poses(owner)));}
            edit(form,"Pose frame (1 based)").setText("1");click(form,"Add pose");assertEquals("Duplicate frame must reject atomically",before,json(poses(owner)));
            spinner(form,"Pose timing").setSelection(6);ShadowLooper.idleMainLooper();for(String value:new String[]{"bad","[0,1]","[-0.1,0,1,1]"}){edit(form,"Bezier controls (JSON array)").setText(value);dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());assertEquals(before,json(poses(owner)));}dialog().cancel();
        }
    }

    @Test public void invalidSettingsSpeechAndPreviewFramesKeepControlsAndNeverSubmit()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->requests.add(r));View form=root();
            for(String[] invalid:new String[][]{{"x","1.1"},{"floorY","-0.1"},{"size","0"},{"skinColor","#123456"},{"hairColor","bad"},{"coatColor","bad"},{"trouserColor","bad"}}){EditText field=edit(form,invalid[0]);String old=field.getText().toString();field.setText(invalid[1]);dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());assertEquals(invalid[1],field.getText().toString());field.setText(old);}
            check(form,"Timed speech").setChecked(true);for(String invalid:new String[]{"bad","{\"cues\":[]}","{\"cues\":[{\"startMs\":0,\"endMs\":4001,\"shape\":\"open\"}]}"}){edit(form,"Speech timing (editable JSON)").setText(invalid);dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());assertEquals(invalid,edit(form,"Speech timing (editable JSON)").getText().toString());}
            check(form,"Timed speech").setChecked(false);for(String invalid:new String[]{"0","121","1.5","bad"}){edit(form,"Preview frame (1 based)").setText(invalid);click(form,"Preview human");assertNull(SketchSolidUiTest.field(owner,"task"));}assertTrue(dialog().isShowing());dialog().cancel();
        }
    }

    @Test public void everyViewManualPreviewMatchesTheActualNativeRequestAndKeepsTheSourceUntouched()throws Exception {
        for(int v=0;v<VIEWS.length;v++)try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=json(doc);List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();
            spinner(form,"Human view").setSelection(v);spinner(form,"Starting action").setSelection(Arrays.asList(SketchHumanPresets.NAMES).indexOf("explain"));edit(form,"Preview frame (1 based)").setText("61");ShadowLooper.idleMainLooper();click(form,"Preview human");drain(owner);Bitmap actual=previewCopy(form);
            try{assertTrue(requests.isEmpty());assertEquals(before,json(doc));dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());JSONObject saved=apply(doc,requests.get(0));assertEquals(VIEWS[v],operation(requests.get(0)).getJSONObject("settings").getString("view"));
                assertEquals("human",rig(saved).getString("kind"));assertEquals(120,saved.getInt("frameCount"));assertEquals(30,saved.getInt("fps"));
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),saved)){Bitmap expected=renderer.render(60);try{assertTrue("Actual manual/native preview parity: "+VIEWS[v],actual.sameAs(expected));}finally{expected.recycle();}}assertEquals(before,json(doc));
            }finally{actual.recycle();}
        }
    }

    @Test public void customJointFaceAndBlinkEditsChangeNativePixelsAndMatchSavedPoses()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();custom(form);edit(form,"Preview frame (1 based)").setText("1");click(form,"Preview human");drain(owner);Bitmap before=previewCopy(form);
            try{JSONObject authored=authoredPose();setFields(form,authored);assertNull(((ImageView)control(form,"Human animation preview")).getDrawable());click(form,"Preview human");drain(owner);Bitmap actual=previewCopy(form);
                try{assertFalse("Real pose edits must change native pixels",before.sameAs(actual));dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertPose(authored,operation(requests.get(0)).getJSONArray("poses").getJSONObject(0));try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),apply(doc,requests.get(0)))){Bitmap expected=renderer.render(0);try{assertTrue(actual.sameAs(expected));}finally{expected.recycle();}}}finally{actual.recycle();}
            }finally{before.recycle();}
        }
    }

    @Test public void sceneryAndAllHeldPropsShareTheActualManualNativePreviewRequest()throws Exception {
        String[] locations={"forest_path","bridge","shelter"},lights={"dusk","night","dawn"},weather={"clear","rain","wind"},props={"lantern","parcel","staff"};
        for(int variant=0;variant<props.length;variant++)try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=json(doc);List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();
            assertEquals(5,spinner(form,"Optional scenery").getCount());assertEquals(3,spinner(form,"Scene light").getCount());assertEquals(3,spinner(form,"Scene weather").getCount());assertEquals(5,spinner(form,"Optional held prop").getCount());
            select(form,"Optional scenery",locations[variant]);select(form,"Scene light",lights[variant]);select(form,"Scene weather",weather[variant]);select(form,"Optional held prop",props[variant]);custom(form);setFields(form,authoredPose());edit(form,"Preview frame (1 based)").setText("1");ShadowLooper.idleMainLooper();click(form,"Preview human");drain(owner);Bitmap actual=previewCopy(form);
            try{assertTrue(requests.isEmpty());assertEquals(before,json(doc));dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());JSONObject request=requests.get(0),human=findOperation(request,"add_human_animation"),stage=findOperation(request,"add_story_stage"),prop=findOperation(request,"add_actor_prop");
                assertEquals(locations[variant],stage.getJSONObject("settings").getString("location"));assertEquals(lights[variant],stage.getJSONObject("settings").getString("time"));assertEquals(weather[variant],stage.getJSONObject("settings").getString("weather"));assertEquals(.6,stage.getJSONObject("settings").getDouble("intensity"),0);
                assertEquals(props[variant],prop.getJSONObject("settings").getString("kind"));assertEquals("near",prop.getJSONObject("settings").getString("attachment"));assertEquals(human.getString("actorId"),prop.getString("actorId"));assertEquals(human.getString("layerId"),prop.getString("layerId"));assertTrue(operationIndex(request,"add_human_animation")<operationIndex(request,"add_actor_prop"));
                JSONObject candidate=apply(doc,request);JSONArray layers=candidate.getJSONArray("layers");assertEquals(4,layers.length());assertEquals(stage.getString("layerId"),layers.getJSONObject(0).getString("id"));assertEquals("layer_1",layers.getJSONObject(1).getString("id"));assertEquals(human.getString("layerId"),layers.getJSONObject(2).getString("id"));assertEquals(stage.getString("foregroundLayerId"),layers.getJSONObject(3).getString("id"));assertTrue(layers.getJSONObject(0).getJSONArray("objects").length()>10);assertTrue(layers.getJSONObject(3).getJSONArray("objects").length()>0);
                String handle=prop.getString("propId")+"_handle_0";assertEquals(human.getString("layerId"),SketchDocument.owner(candidate,handle).getString("id"));assertEquals(120,SketchDocument.object(candidate,handle).getJSONArray("keys").length());
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),candidate)){Bitmap expected=renderer.render(0);try{assertTrue("Combined scenery/human/"+props[variant]+" must have exact native preview/save parity",actual.sameAs(expected));capture("native-human-scene-"+props[variant]+"-preview.png",actual);}finally{expected.recycle();}}
            }finally{actual.recycle();}
        }
    }

    @Test public void ropeSelectionLinksActualNearAndFarGripsAndMatchesNativePreviewAndSave()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=json(doc);List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();
            select(form,"Optional scenery","village");select(form,"Scene light","dawn");select(form,"Optional held prop","rope");custom(form);setFields(form,authoredPose());edit(form,"Preview frame (1 based)").setText("61");ShadowLooper.idleMainLooper();click(form,"Preview human");drain(owner);Bitmap actual=previewCopy(form);
            try{
                assertTrue("Rope preview must not submit artwork",requests.isEmpty());assertEquals(before,json(doc));dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());JSONObject request=requests.get(0),human=findOperation(request,"add_human_animation"),link=findOperation(request,"add_grip_link"),stage=findOperation(request,"add_story_stage");
                assertEquals(human.getString("actorId"),link.getJSONObject("from").getString("actorId"));assertEquals(human.getString("actorId"),link.getJSONObject("to").getString("actorId"));assertEquals("near",link.getJSONObject("from").getString("hand"));assertEquals("far",link.getJSONObject("to").getString("hand"));assertTrue(operationIndex(request,"add_human_animation")<operationIndex(request,"add_grip_link"));
                JSONArray ops=request.getJSONArray("operations");for(int i=0;i<ops.length();i++)assertNotEquals("Rope must use its two-grip link operation", "add_actor_prop",ops.getJSONObject(i).getString("type"));
                JSONObject candidate=apply(doc,request);JSONArray layers=candidate.getJSONArray("layers");assertEquals(5,layers.length());assertEquals(stage.getString("layerId"),layers.getJSONObject(0).getString("id"));assertEquals(link.getString("layerId"),layers.getJSONObject(2).getString("id"));assertEquals(human.getString("layerId"),layers.getJSONObject(3).getString("id"));assertEquals(stage.getString("foregroundLayerId"),layers.getJSONObject(4).getString("id"));
                JSONObject rope=SketchDocument.object(candidate,link.getString("linkId")+"_rope"),shadow=SketchDocument.object(candidate,link.getString("linkId")+"_shadow");assertEquals(link.getString("layerId"),SketchDocument.owner(candidate,rope.getString("id")).getString("id"));assertEquals(120,rope.getJSONArray("shapeKeys").length());assertEquals(120,shadow.getJSONArray("shapeKeys").length());
                JSONObject actor=rig(candidate),settings=actor.getJSONObject("human").getJSONObject("settings"),pose=SketchHumanAnimation.pose(actor,60);JSONArray commands=rope.getJSONArray("shapeKeys").getJSONObject(60).getJSONArray("commands");assertEquals(60,rope.getJSONArray("shapeKeys").getJSONObject(60).getInt("frame"));assertEquals("M",commands.getJSONArray(0).getString(0));assertEquals("Q",commands.getJSONArray(1).getString(0));
                double scale=Math.min(candidate.getInt("width")/1280d,candidate.getInt("height")/720d)*settings.getDouble("size");
                for(boolean near:new boolean[]{true,false}){double[] grip=SketchHumanFigure.grip(settings,pose,near);JSONArray command=commands.getJSONArray(near?0:1);int endpoint=near?1:3;assertEquals("Rope endpoint X must follow the actual native grip",candidate.getInt("width")*settings.getDouble("x")+grip[0]*scale,command.getDouble(endpoint)*candidate.getInt("width"),1e-3);assertEquals("Rope endpoint Y must follow the actual native grip",candidate.getInt("height")*settings.getDouble("floorY")+grip[1]*scale,command.getDouble(endpoint+1)*candidate.getInt("height"),1e-3);}
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),candidate)){Bitmap expected=renderer.render(60);try{assertTrue("Manual rope preview must equal the saved native request at the chosen frame",actual.sameAs(expected));capture("native-human-scene-rope-preview.png",actual);}finally{expected.recycle();}}
                JSONObject withoutRope=SketchDocument.copy(candidate);SketchDocument.layer(withoutRope,link.getString("layerId")).put("objects",new JSONArray());try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),withoutRope)){Bitmap unlinked=renderer.render(60);try{assertFalse("Rope must contribute visible native scene pixels",actual.sameAs(unlinked));}finally{unlinked.recycle();}}
                assertEquals("Manual preview and submit must preserve the source",before,json(doc));
            }finally{actual.recycle();}
        }
    }

    @Test public void changingSceneryAndPropControlsInvalidatesPendingCombinedPreview()throws Exception {
        for(String control:new String[]{"Optional scenery","Scene light","Scene weather","Optional held prop"})try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Changing scene controls must not submit"));View form=root();select(form,"Optional scenery","forest_path");select(form,"Optional held prop","lantern");ShadowLooper.idleMainLooper();ExecutorService worker=worker(owner);CountDownLatch entered=new CountDownLatch(1);Future<?> blocker=gate(worker,entered);
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));click(form,"Preview human");Future<?> task=(Future<?>)SketchSolidUiTest.field(owner,"task");assertNotNull(task);spinner(form,control).setSelection(control.equals("Optional held prop")?2:control.equals("Optional scenery")?2:1);ShadowLooper.idleMainLooper();assertTrue("Changed scene control must invalidate its queued image: "+control,task.isCancelled());}finally{dialog().cancel();ShadowLooper.idleMainLooper();blocker.cancel(true);worker.shutdownNow();}
        }
    }

    @Test public void cancellationInterruptsQueuedPreviewAndPreservesTheOriginalDrawing()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=json(doc);List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->requests.add(r));ExecutorService worker=worker(owner);CountDownLatch entered=new CountDownLatch(1);Future<?> blocker=gate(worker,entered);View form=root();
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));click(form,"Preview human");Future<?> pending=(Future<?>)SketchSolidUiTest.field(owner,"task");assertNotNull(pending);assertFalse(pending.isDone());dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();assertTrue(pending.isCancelled());assertTrue(worker.isShutdown());assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));assertTrue((Boolean)SketchSolidUiTest.field(owner,"closed"));assertNull(((ImageView)control(form,"Human animation preview")).getDrawable());assertTrue(requests.isEmpty());assertEquals(before,json(doc));}finally{blocker.cancel(true);worker.shutdownNow();}
        }
    }

    @Test public void changedSettingsRejectAnAlreadyPostedPreviewCallbackAndAllowFreshRendering()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Preview must not submit"));View form=root();click(form,"Preview human");worker(owner).submit(()->{}).get(60,TimeUnit.SECONDS);edit(form,"coatColor").setText("#FF62553A");ShadowLooper.idleMainLooper();assertNull(((ImageView)control(form,"Human animation preview")).getDrawable());click(form,"Preview human");drain(owner);Bitmap actual=previewCopy(form);actual.recycle();dialog().cancel();
        }
    }

    /** These edits change the request just as settings edits do, and must invalidate its preview. */
    @Test public void speechTogglePoseFrameAndCurveEditsCancelQueuedPreview()throws Exception {
        for(String edited:new String[]{"Timed speech","Pose frame (1 based)","Bezier controls (JSON array)"})try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Editing must not save"));View form=root();custom(form);if(edited.startsWith("Bezier")){spinner(form,"Pose timing").setSelection(6);ShadowLooper.idleMainLooper();}
            ExecutorService worker=worker(owner);CountDownLatch entered=new CountDownLatch(1);Future<?> blocker=gate(worker,entered);
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));click(form,"Preview human");Future<?> pending=(Future<?>)SketchSolidUiTest.field(owner,"task");assertNotNull(pending);
                if(edited.equals("Timed speech"))check(form,edited).setChecked(true);else edit(form,edited).setText(edited.startsWith("Bezier")?"[0.4,0.2,0.7,1]":"2");ShadowLooper.idleMainLooper();assertTrue("Edit must cancel the stale preview: "+edited,pending.isCancelled());
            }finally{dialog().cancel();ShadowLooper.idleMainLooper();blocker.cancel(true);worker.shutdownNow();}
        }
    }

    @Test public void asyncFailureAllowsRetryAndOldDuplicateCallbacksCannotDismissIt()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();List<Consumer<Exception>> callbacks=new ArrayList<>();open(a.get(),document(),(r,c)->{requests.add(r);callbacks.add(c);});View form=root();AlertDialog editor=dialog();edit(form,"coatColor").setText("#FF62553A");Button save=editor.getButton(AlertDialog.BUTTON_POSITIVE);save.performClick();save.performClick();editor.onBackPressed();assertEquals(1,requests.size());assertFalse(save.isEnabled());assertFalse(editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());assertTrue(editor.isShowing());
            Thread callback=new Thread(()->callbacks.get(0).accept(new IllegalStateException("Save conflict")));callback.start();callback.join();ShadowLooper.idleMainLooper();assertTrue(save.isEnabled());assertEquals("#FF62553A",edit(form,"coatColor").getText().toString());assertTrue(status(form).contains("Save conflict"));save.performClick();assertEquals(2,requests.size());assertEquals(json(operation(requests.get(0)).getJSONObject("settings")),json(operation(requests.get(1)).getJSONObject("settings")));
            callbacks.get(0).accept(null);ShadowLooper.idleMainLooper();assertTrue("An old completed attempt must not dismiss an active retry",editor.isShowing());assertFalse(save.isEnabled());callbacks.get(1).accept(null);ShadowLooper.idleMainLooper();assertFalse(editor.isShowing());
        }
    }

    @Test public void synchronousSubmitExceptionRestoresRetryControlsAndLateCallbackAfterCloseIsIgnored()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<Consumer<Exception>> callbacks=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->{callbacks.add(c);if(callbacks.size()==1)throw new IllegalStateException("Store unavailable");});View form=root();AlertDialog editor=dialog();Button save=editor.getButton(AlertDialog.BUTTON_POSITIVE);save.performClick();assertTrue(status(form).contains("Store unavailable"));assertTrue("Synchronous failure must restore retry",save.isEnabled());assertTrue(editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());save.performClick();assertEquals(2,callbacks.size());editor.dismiss();ShadowLooper.idleMainLooper();callbacks.get(1).accept(new IllegalStateException("Late failure"));ShadowLooper.idleMainLooper();assertFalse(editor.isShowing());assertFalse(status(form).contains("Late failure"));assertTrue(worker(owner).isShutdown());
        }
    }

    @Test public void changingPoseSelectionMustNotSilentlyDiscardPendingChannelEdits()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Selection must not save artwork"));View form=root();custom(form);edit(form,"headTilt").setText("-7");spinner(form,"Selected pose").setSelection(1);ShadowLooper.idleMainLooper();assertEquals("Pending pose edits must survive selection",-7,poses(owner).getJSONObject(0).getDouble("headTilt"),0);spinner(form,"Selected pose").setSelection(0);ShadowLooper.idleMainLooper();assertEquals(-7,Double.parseDouble(edit(form,"headTilt").getText().toString()),0);dialog().cancel();
        }
    }

    @Test public void importedGeneratedDialogueRebasesLoadsEditsAndMatchesNativePreviewAndSave()throws Exception {
        SketchSpeechAssetsTest.Fixture source=new SketchSpeechAssetsTest.Fixture("english");
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=json(doc);List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});AlertDialog editor=dialog();View form=root();edit(form,"Shot start in master (ms)").setText("500");click(form,"Load generated dialogue");drain(owner);AlertDialog picker=dialog();JSONArray choices=SketchSpeechAssets.choices(a.get());int choice=-1;for(int i=0;i<choices.length();i++)if(source.id.equals(choices.getJSONObject(i).getString("requestId"))&&choices.getJSONObject(i).getInt("lineIndex")==0)choice=i;assertTrue(choice>=0);Shadows.shadowOf(picker).clickOnItem(choice);ShadowLooper.idleMainLooper();drain(owner);
            JSONObject loaded=SketchSpeechAssets.load(a.get(),source.id,0,4000,500),expected=loaded.getJSONObject("speech"),actualSpeech=new JSONObject(edit(form,"Speech timing (editable JSON)").getText().toString());assertEquals(json(expected),json(actualSpeech));assertTrue(check(form,"Timed speech").isChecked());assertTrue(status(form).contains(loaded.getString("text")));assertTrue(status(form).contains("500"));assertEquals(OpenPipelineArtifacts.hash(SketchSpeechStudyTest.resource("english.wav")),loaded.getString("audioSha256"));assertFalse(loaded.getBoolean("forcedAlignmentVerified"));assertFalse(loaded.getBoolean("audioMixedIntoSketch"));
            int frame=speakingFrame(actualSpeech,120,30);assertTrue("Imported line needs a sampled speaking frame",frame>=0);for(int i=0;i<actualSpeech.getJSONArray("cues").length();i++)actualSpeech.getJSONArray("cues").getJSONObject(i).put("strength",.65);edit(form,"Speech timing (editable JSON)").setText(actualSpeech.toString());edit(form,"Preview frame (1 based)").setText(String.valueOf(frame+1));click(form,"Preview human");drain(owner);Bitmap preview=previewCopy(form);
            try{assertTrue(requests.isEmpty());assertEquals(before,json(doc));editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();JSONObject request=requests.get(0);assertEquals(json(actualSpeech),json(operation(request).getJSONObject("speech")));assertFalse(operation(request).getJSONObject("speech").has("generated"));
                JSONObject saved=apply(doc,request);try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),saved)){Bitmap image=renderer.render(frame);try{assertTrue("Loaded, edited model timing must reach native manual preview/save",preview.sameAs(image));}finally{image.recycle();}}
                JSONObject silent=SketchDocument.copy(request);silent.getJSONArray("operations").getJSONObject(1).remove("speech");try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),apply(doc,silent))){Bitmap image=renderer.render(frame);try{assertFalse("Real visemes must change the rendered face",preview.sameAs(image));}finally{image.recycle();}}
            }finally{preview.recycle();}
        }
    }

    @Test public void postedSpeechLoadCannotOverwriteNewerManualTimingOrShotOffset()throws Exception {
        SketchSpeechAssetsTest.Fixture source=new SketchSpeechAssetsTest.Fixture("english");
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Loading must not save"));AlertDialog editor=dialog();View form=root();click(form,"Load generated dialogue");drain(owner);AlertDialog picker=dialog();JSONArray choices=SketchSpeechAssets.choices(a.get());int chosen=-1;for(int i=0;i<choices.length();i++)if(source.id.equals(choices.getJSONObject(i).getString("requestId"))&&choices.getJSONObject(i).getInt("lineIndex")==0)chosen=i;assertTrue(chosen>=0);Shadows.shadowOf(picker).clickOnItem(chosen);worker(owner).submit(()->{}).get(60,TimeUnit.SECONDS);
            String newer=speech("closed").toString();edit(form,"Speech timing (editable JSON)").setText(newer);edit(form,"Shot start in master (ms)").setText("750");check(form,"Timed speech").setChecked(false);ShadowLooper.idleMainLooper();assertEquals(newer,edit(form,"Speech timing (editable JSON)").getText().toString());assertEquals("750",edit(form,"Shot start in master (ms)").getText().toString());assertFalse(check(form,"Timed speech").isChecked());editor.cancel();
        }
    }
    @Test public void invalidMasterOffsetsDoNotOpenGeneratedDialoguePicker()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Import validation must not submit"));AlertDialog editor=dialog();View form=root();String before=edit(form,"Speech timing (editable JSON)").getText().toString();
            for(String offset:new String[]{"-1","600001","1.5","bad"}){edit(form,"Shot start in master (ms)").setText(offset);click(form,"Load generated dialogue");drain(owner);assertSame(editor,dialog());assertEquals(before,edit(form,"Speech timing (editable JSON)").getText().toString());}editor.cancel();
        }
    }

    @Test public void activityPreviewMatchesReloadedDurableNativeArtworkWithCustomPosesAndSpeech()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);
        try(ActivityController<SketchStudioActivity> a=studio(context,id)){
            SketchStudioActivity activity=a.get();openMenu(activity);AlertDialog editor=dialog();View form=root();SketchEditorSession session=(SketchEditorSession)SketchSolidUiTest.field(activity,"session");JSONObject original=SketchDocument.copy(session.document());String before=json(original);
            spinner(form,"Human view").setSelection(2);spinner(form,"Adult build").setSelection(1);spinner(form,"Hair style").setSelection(1);edit(form,"size").setText("0.82");edit(form,"floorY").setText("0.82");select(form,"Optional scenery","forest_path");select(form,"Scene light","night");select(form,"Optional held prop","lantern");custom(form);setFields(form,authoredPose());check(form,"Timed speech").setChecked(true);JSONObject speech=speech("round");edit(form,"Speech timing (editable JSON)").setText(speech.toString());edit(form,"Preview frame (1 based)").setText("1");ShadowLooper.idleMainLooper();click(form,"Preview human");awaitPreview(form);Bitmap actual=previewCopy(form);
            try{try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals("Preview must not persist",before,json(store.get(id)));}capture("native-human-preview.png",actual);captureUi(form);editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);assertFalse("Protected native save must succeed: "+status(form),editor.isShowing());JSONObject saved;try(SketchProjectStore store=new SketchProjectStore(context)){saved=store.get(id);}assertEquals(original.getLong("revision")+1,saved.getLong("revision"));assertEquals(original.getJSONArray("layers").length()+3,saved.getJSONArray("layers").length());for(String key:new String[]{"width","height","frameCount","fps"})assertEquals(original.getInt(key),saved.getInt(key));
                JSONObject rig=rig(saved);assertEquals("human",rig.getString("kind"));assertPose(authoredPose(),rig.getJSONObject("human").getJSONArray("poses").getJSONObject(0));assertEquals(json(SketchSpeechTiming.resolve(speech,4000)),json(rig.getJSONObject("human").getJSONObject("speech")));
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,saved)){Bitmap expected=renderer.render(0);try{assertTrue("Real Activity preview must equal artwork reloaded from durable storage",actual.sameAs(expected));capture("native-human-saved.png",expected);}finally{expected.recycle();}}
            }finally{actual.recycle();editor.cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void kind9RejectsAdoptedCanvasOrTimingChangesAndRetainsInputs()throws Exception {
        for(JSONObject change:new JSONObject[]{new JSONObject().put("type","set_timing").put("frameCount",180).put("fps",30),new JSONObject().put("type","set_canvas").put("width",960),new JSONObject().put("type","set_canvas").put("height",960)}){
            Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);try(ActivityController<SketchStudioActivity> a=studio(context,id)){
                SketchStudioActivity activity=a.get();openMenu(activity);AlertDialog editor=dialog();View form=root();edit(form,"coatColor").setText("#FF62553A");SketchEditorSession session=(SketchEditorSession)SketchSolidUiTest.field(activity,"session");SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",session.document().getLong("revision")).put("operationId","human_geometry_change").put("operations",new JSONArray().put(change)));JSONObject adopted;try(SketchProjectStore store=new SketchProjectStore(context)){adopted=store.get(id);}assertTrue(session.adopt(adopted));String before=json(adopted);Method present=SketchStudioActivity.class.getDeclaredMethod("present",JSONObject.class);present.setAccessible(true);present.invoke(activity,session.document());editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(before,json(store.get(id)));}assertTrue(editor.isShowing());assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertEquals("#FF62553A",edit(form,"coatColor").getText().toString());assertTrue(status(form).toLowerCase(Locale.ROOT).contains("reopen"));editor.cancel();ShadowLooper.idleMainLooper();
            }
        }
    }

    @Test public void kind9ConflictRetryPreservesConcurrentNativeArtwork()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);try(ActivityController<SketchStudioActivity> a=studio(context,id)){
            SketchStudioActivity activity=a.get();openMenu(activity);AlertDialog editor=dialog();View form=root();edit(form,"coatColor").setText("#FF62553A");SketchEditorSession session=(SketchEditorSession)SketchSolidUiTest.field(activity,"session");long revision=session.document().getLong("revision");JSONObject artwork=SketchEditOps.completeObject(new JSONObject().put("id","concurrent_human_ink").put("kind","line").put("points",new JSONArray("[[0.1,0.1,1],[0.2,0.2,1]]")));SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId","other_human_artist").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",artwork))));editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);assertTrue(editor.isShowing());assertEquals("#FF62553A",edit(form,"coatColor").getText().toString());editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);assertFalse("Retry should use adopted authoritative revision",editor.isShowing());try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject saved=store.get(id);assertEquals(revision+2,saved.getLong("revision"));assertEquals(json(artwork),json(SketchDocument.object(saved,"concurrent_human_ink")));assertEquals(2,saved.getJSONArray("layers").length());}
        }
    }

    @Test public void kind9RejectsStalePageBeforeWriting()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);try(ActivityController<SketchStudioActivity> a=studio(context,id)){
            SketchStudioActivity activity=a.get();openMenu(activity);AlertDialog editor=dialog();View form=root();String before=json(((SketchEditorSession)SketchSolidUiTest.field(activity,"session")).document());Field page=SketchStudioActivity.class.getDeclaredField("pageGeneration");page.setAccessible(true);page.setInt(activity,page.getInt(activity)+1);editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);assertTrue(editor.isShowing());assertTrue(status(form).contains("Human acting"));try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(before,json(store.get(id)));}editor.cancel();ShadowLooper.idleMainLooper();
        }
    }

    private static JSONObject authoredPose()throws Exception {return SketchHumanFigure.poseDefaults().put("rootX",3).put("rootY",2).put("rootZ",4).put("hipX",2).put("hipY",-175).put("hipZ",1).put("bodyLean",6).put("headTilt",-7).put("gazeX",.4).put("gazeY",-.3).put("blink",.5).put("mouth",.6).put("mouthWide",.2).put("mouthRound",.3).put("mouthTeeth",.7).put("mouthTongue",.4).put("smile",.2).put("brow",.4).put("nearHandX",65).put("nearHandY",-190).put("nearHandZ",22).put("farHandX",-62).put("farHandY",-185).put("farHandZ",-20).put("footLeftX",-26).put("footLeftY",-2).put("footLeftZ",-7).put("footRightX",30).put("footRightY",-1).put("footRightZ",11).put("nearWrist",35).put("farWrist",-15).put("handOpen",0).put("nearElbowSwivel",90).put("farElbowSwivel",-50);}
    private static JSONObject speech(String shape)throws Exception {return new JSONObject().put("transitionMs",0).put("cues",new JSONArray().put(new JSONObject().put("startMs",0).put("endMs",4000).put("shape",shape).put("strength",.8)));}
    private static int speakingFrame(JSONObject speech,int frames,int fps)throws Exception {for(int f=0;f<frames;f++)if(SketchSpeechTiming.sample(speech,f*1000d/fps)[0]>.35)return f;return -1;}
    private static void assertPose(JSONObject expected,JSONObject actual)throws Exception {for(String key:SketchHumanFigure.POSE_CHANNELS)assertEquals("Manual pose channel "+key,expected.getDouble(key),actual.getDouble(key),1e-12);}
    private static void setFields(View form,JSONObject values)throws Exception {Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();edit(form,key).setText(String.valueOf(values.get(key)));}}
    private static ActivityController<SketchStudioActivity> studio(Context context,String id)throws Exception {ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).setup();SketchSolidUiTest.flush(a.get());Field watching=SketchStudioActivity.class.getDeclaredField("watching");watching.setAccessible(true);watching.setBoolean(a.get(),false);Method stop=SketchStudioActivity.class.getDeclaredMethod("stopLivePlayer");stop.setAccessible(true);stop.invoke(a.get());return a;}
    private static void openMenu(SketchStudioActivity activity){click(activity.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Human acting");ShadowLooper.idleMainLooper();}
    private static Object open(Activity activity,JSONObject doc,SketchConstructionSubmit submit)throws Exception {Constructor<SketchHumanDialog> constructor=SketchHumanDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);constructor.setAccessible(true);Object owner=constructor.newInstance(activity,doc,submit);Method show=SketchHumanDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();return owner;}
    private static JSONObject document()throws Exception {return SketchDocument.create(new JSONObject().put("sketchId","human_manual").put("width",512).put("height",512).put("frameCount",120).put("fps",30));}
    private static JSONObject operation(JSONObject request)throws Exception {JSONArray ops=request.getJSONArray("operations");assertEquals(2,ops.length());assertEquals("add_layer",ops.getJSONObject(0).getString("type"));assertEquals(ops.getJSONObject(0).getJSONObject("layer").getString("id"),ops.getJSONObject(1).getString("layerId"));return ops.getJSONObject(1);}
    private static int operationIndex(JSONObject request,String type)throws Exception {JSONArray ops=request.getJSONArray("operations");int found=-1;for(int i=0;i<ops.length();i++)if(type.equals(ops.getJSONObject(i).getString("type"))){assertEquals("Only one "+type+" expected",-1,found);found=i;}assertTrue("Missing "+type+" in combined manual request",found>=0);return found;}
    private static JSONObject findOperation(JSONObject request,String type)throws Exception {return request.getJSONArray("operations").getJSONObject(operationIndex(request,type));}
    private static JSONObject apply(JSONObject doc,JSONObject request)throws Exception {JSONObject out=SketchDocument.copy(doc);JSONArray ops=request.getJSONArray("operations");for(int i=0;i<ops.length();i++)SketchEditOps.apply(out,ops.getJSONObject(i));SketchDocument.validate(out);return out;}
    private static JSONObject rig(JSONObject doc)throws Exception {JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray a=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<a.length();i++)if("human".equals(a.getJSONObject(i).getString("kind")))return a.getJSONObject(i);}throw new AssertionError("No retained native human rig");}
    private static String json(Object value)throws Exception {return SketchDocument.canonical(value instanceof JSONArray?new JSONArray(value.toString()):new JSONObject(value.toString()));}
    private static JSONArray poses(Object owner)throws Exception {return (JSONArray)SketchSolidUiTest.field(owner,"poses");}
    private static int indexAt(JSONArray poses,int frame)throws Exception {for(int i=0;i<poses.length();i++)if(poses.getJSONObject(i).getInt("frame")==frame)return i;return -1;}
    private static int unusedFrame(JSONArray poses,int frames)throws Exception {for(int f=1;f<frames;f++)if(indexAt(poses,f)<0)return f;throw new AssertionError("No free key frame");}
    private static ExecutorService worker(Object owner)throws Exception {return (ExecutorService)SketchSolidUiTest.field(owner,"worker");}
    private static Future<?> gate(ExecutorService worker,CountDownLatch entered){return worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});}
    private static void drain(Object owner)throws Exception {worker(owner).submit(()->{}).get(60,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static void awaitPreview(View form)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(((ImageView)control(form,"Human animation preview")).getDrawable()==null&&System.nanoTime()<deadline&&!status(form).startsWith("Controls retained")){Thread.sleep(20);ShadowLooper.idleMainLooper();}}
    private static Bitmap previewCopy(View form){ImageView preview=(ImageView)control(form,"Human animation preview");assertTrue("Native preview must finish: "+status(form),preview.getDrawable() instanceof BitmapDrawable);return ((BitmapDrawable)preview.getDrawable()).getBitmap().copy(Bitmap.Config.ARGB_8888,false);}
    private static void custom(View form){check(form,"Edit custom poses").setChecked(true);ShadowLooper.idleMainLooper();}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View root(){return dialog().getWindow().getDecorView();}
    private static View control(View root,String label){View view=SketchSolidUiTest.description(root,label);assertNotNull("Missing manual control: "+label,view);return view;}
    private static EditText edit(View root,String label){return (EditText)control(root,label);}
    private static Spinner spinner(View root,String label){return (Spinner)control(root,label);}
    private static void select(View form,String label,String value){Spinner spinner=spinner(form,label);for(int i=0;i<spinner.getCount();i++)if(value.equals(spinner.getItemAtPosition(i).toString())){spinner.setSelection(i);return;}throw new AssertionError("Missing manual option "+label+" = "+value);}
    private static CheckBox findCheck(View root,String label){if(root instanceof CheckBox&&(label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())||label.contentEquals(((CheckBox)root).getText())))return (CheckBox)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){CheckBox found=findCheck(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private static CheckBox check(View root,String label){CheckBox box=findCheck(root,label);assertNotNull("Missing manual toggle: "+label,box);return box;}
    private static String status(View root){return ((TextView)control(root,"Human animation status")).getText().toString();}
    private static void click(View root,String label){Button button=SketchSolidUiTest.button(root,label);assertNotNull("Missing manual action: "+label,button);button.performClick();ShadowLooper.idleMainLooper();}
    /** Optional genuine Robolectric UI/native evidence; the parent chooses an outside-Git directory. */
    private static void capture(String name,Bitmap image)throws Exception {String path=System.getenv("VS_HUMAN_UI_EVIDENCE_DIR");if(path==null||path.isEmpty())return;File dir=new File(path);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create human UI evidence directory");try(FileOutputStream output=new FileOutputStream(new File(dir,name))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}}
    private static void captureUi(View root)throws Exception {String path=System.getenv("VS_HUMAN_UI_EVIDENCE_DIR");if(path==null||path.isEmpty())return;assertTrue("Actual dialog must be laid out for a UI capture",root.getWidth()>0&&root.getHeight()>0);Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try{root.draw(new Canvas(image));capture("robolectric-human-dialog.png",image);}finally{image.recycle();}}
}
