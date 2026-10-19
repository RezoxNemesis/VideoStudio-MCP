package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Exercises real manual controls, native pixels and the activity's protected save path. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHandDialogTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] CHANNELS={"indexCurl","middleCurl","ringCurl","littleCurl","thumbCurl","thumbOpposition","spread","wristFlex","wristTurn"};

    @Test public void defaultsMatchNativeAndInvalidTimingNeverOpens()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(a.get(),document(),(r,c)->{requests.add(r);c.accept(null);});
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();JSONObject op=operation(requests.get(0));
            assertEquals("add_hand_animation",op.getString("type"));assertTrue(op.getString("handId").length()<=20);assertFalse(op.has("poses"));
            assertEquals(SketchDocument.canonical(SketchDocument.copy(SketchHandAnimation.defaults())),SketchDocument.canonical(SketchDocument.copy(op.getJSONObject("settings"))));
            for(int[] timing:new int[][]{{24,30},{241,30},{120,24+1},{120,60}}){
                JSONObject invalid=document().put("frameCount",timing[0]).put("fps",timing[1]);String before=SketchDocument.canonical(invalid);
                InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->open(a.get(),invalid,(r,c)->fail("Invalid timing must not submit")));
                assertTrue(failure.getCause().getMessage().contains("4–8 seconds"));assertEquals(before,SketchDocument.canonical(invalid));
            }
        }
    }

    @Test public void allSettingsDispatchExactlyOnANewLayer()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=SketchDocument.canonical(doc);List<JSONObject> requests=new ArrayList<>();
            open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();
            assertEquals(6,spinner(form,"Hand view").getCount());assertEquals(2,spinner(form,"Handedness").getCount());assertEquals(2,spinner(form,"Hand detail").getCount());
            spinner(form,"Hand view").setSelection(2);spinner(form,"Handedness").setSelection(1);spinner(form,"Hand detail").setSelection(1);
            edit(form,"Skin colour (ARGB)").setText("#FFB78060");edit(form,"Nail colour (ARGB)").setText("#FFE8CBB8");
            String[] names={"Centre X (0.2..0.8)","Centre Y (0.2..0.8)","Size (0.4..1.2)","Motion (0..1)","Wrist motion (0..1)"};
            double[] values={.42,.51,.7,.8,.3};for(int i=0;i<names.length;i++)edit(form,names[i]).setText(String.valueOf(values[i]));
            ShadowLooper.idleMainLooper();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());
            JSONObject settings=operation(requests.get(0)).getJSONObject("settings");assertEquals(11,settings.length());assertEquals("smooth",settings.getString("renderStyle"));
            assertEquals("right",settings.getString("view"));assertEquals("left",settings.getString("handedness"));assertEquals("contour",settings.getString("detail"));
            assertEquals("#FFB78060",settings.getString("skinColor"));assertEquals("#FFE8CBB8",settings.getString("nailColor"));
            String[] keys={"centerX","centerY","size","motion","wristMotion"};for(int i=0;i<keys.length;i++)assertEquals(values[i],settings.getDouble(keys[i]),0);
            assertEquals(before,SketchDocument.canonical(doc));
        }
    }

    @Test public void allChannelsEasingAndOrderedKeyEditsSurviveSelectionAndReset()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->{requests.add(r);c.accept(null);});View form=root();
            custom(form);for(int i=0;i<CHANNELS.length;i++)edit(form,CHANNELS[i]).setText(String.valueOf(i<7?.1+i*.08:i==7?-12:17));
            spinner(form,"Pose easing").setSelection(7);ShadowLooper.idleMainLooper();
            double[] curve={.2,.1,.8,.9};for(int i=0;i<4;i++)edit(form,"Bezier "+(i+1)).setText(String.valueOf(curve[i]));
            click(form,"Apply pose edits");JSONArray poses=poses(owner);JSONObject first=poses.getJSONObject(0);
            for(int i=0;i<CHANNELS.length;i++)assertEquals(i<7?.1+i*.08:i==7?-12:17,first.getDouble(CHANNELS[i]),1e-12);
            assertEquals("bezier",first.getString("interpolation"));for(int i=0;i<4;i++)assertEquals(curve[i],first.getJSONArray("curve").getDouble(i),0);
            int unused=unusedFrame(poses);edit(form,"Key frame (1 based)").setText(String.valueOf(unused+1));click(form,"Add pose");
            int count=poses(owner).length();assertEquals(poses.length()+1,count);
            edit(form,"indexCurl").setText("0.93");click(form,"Apply pose edits");
            spinner(form,"Hand pose").setSelection(0);ShadowLooper.idleMainLooper();assertEquals("0.1",edit(form,"indexCurl").getText().toString());
            click(form,"Delete pose");assertEquals("Frame zero is mandatory",count,poses(owner).length());
            int moved=unusedFrame(poses(owner));edit(form,"Key frame (1 based)").setText(String.valueOf(moved+1));click(form,"Apply pose edits");
            assertEquals("Moving the starting pose must reject atomically",0,poses(owner).getJSONObject(0).getInt("frame"));
            edit(form,"Key frame (1 based)").setText("1");
            spinner(form,"Hand pose").setSelection(indexAt(poses(owner),unused));ShadowLooper.idleMainLooper();
            assertEquals("0.93",edit(form,"indexCurl").getText().toString());
            edit(form,"Key frame (1 based)").setText(String.valueOf(moved+1));click(form,"Apply pose edits");assertTrue(indexAt(poses(owner),moved)>0);assertEquals(-1,indexAt(poses(owner),unused));
            click(form,"Delete pose");assertEquals(count-1,poses(owner).length());
            edit(form,"Motion (0..1)").setText("0.4");click(form,"Reset preset");
            assertFalse(((CheckBox)control(form,"Custom poses")).isChecked());
            JSONObject settings=SketchHandAnimation.defaults().put("motion",.4);
            assertEquals(SketchDocument.canonical(SketchHandAnimation.preset(120,30,settings)),SketchDocument.canonical(poses(owner)));
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(operation(requests.get(0)).has("poses"));
        }
    }

    @Test public void omittedChannelsRemainNativeNeutralAndSelectionAppliesPendingEdits()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->{requests.add(r);c.accept(null);});View form=root();custom(form);
            for(String channel:CHANNELS)edit(form,channel).setText("");
            edit(form,"middleCurl").setText("0.6");spinner(form,"Hand pose").setSelection(1);ShadowLooper.idleMainLooper();
            JSONObject first=poses(owner).getJSONObject(0);assertEquals(.6,first.getDouble("middleCurl"),0);assertFalse(first.has("indexCurl"));
            int secondFrame=poses(owner).getJSONObject(1).getInt("frame");edit(form,"middleCurl").setText("");
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();JSONArray sent=operation(requests.get(0)).getJSONArray("poses");
            assertEquals(SketchDocument.canonical(new JSONArray(poses(owner).toString())),SketchDocument.canonical(sent));
            assertFalse(sent.getJSONObject(1).has("middleCurl"));
            double neutral=SketchHandAnimation.poseAt(new JSONArray().put(new JSONObject().put("frame",0)),0)[1];
            assertEquals("Omitted channel must use neutral rather than the prior .6",neutral,SketchHandAnimation.poseAt(sent,secondFrame)[1],0);
        }
    }

    @Test public void duplicateFramesAndThe32KeyLimitRejectWithoutLosingKeys()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Editing must not save"));View form=root();custom(form);
            assertEquals("The shared preset has nine keys",9,poses(owner).length());String before=SketchDocument.canonical(poses(owner));
            click(form,"Add pose");assertEquals(before,SketchDocument.canonical(poses(owner)));
            edit(form,"Key frame (1 based)").setText(String.valueOf(poses(owner).getJSONObject(1).getInt("frame")+1));click(form,"Apply pose edits");
            assertEquals(before,SketchDocument.canonical(poses(owner)));
            while(poses(owner).length()<32){edit(form,"Key frame (1 based)").setText(String.valueOf(unusedFrame(poses(owner))+1));click(form,"Add pose");}
            before=SketchDocument.canonical(poses(owner));edit(form,"Key frame (1 based)").setText(String.valueOf(unusedFrame(poses(owner))+1));click(form,"Add pose");
            assertEquals(32,poses(owner).length());assertEquals(before,SketchDocument.canonical(poses(owner)));dialog().cancel();
        }
    }

    @Test public void everyNativeEasingModeAndBezierControlsReachTheSavedOperation()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(a.get(),document(),(r,c)->{requests.add(r);c.accept(new IllegalStateException("Retain controls"));});View form=root();custom(form);
            String[] modes={"linear","hold","smooth","ease_in","ease_out","ease_in_out","bezier"};
            for(int mode=0;mode<modes.length;mode++){
                spinner(form,"Pose easing").setSelection(mode+1);ShadowLooper.idleMainLooper();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                JSONObject pose=operation(requests.get(mode)).getJSONArray("poses").getJSONObject(0);assertEquals(modes[mode],pose.getString("interpolation"));assertEquals(mode==6,pose.has("curve"));
                if(mode==6)assertEquals(4,pose.getJSONArray("curve").length());
            }
            spinner(form,"Pose easing").setSelection(0);ShadowLooper.idleMainLooper();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            JSONObject nativeDefault=operation(requests.get(7)).getJSONArray("poses").getJSONObject(0);assertFalse(nativeDefault.has("interpolation"));assertFalse(nativeDefault.has("curve"));dialog().cancel();
        }
    }

    @Test public void invalidSettingsKeysChannelsAndPreviewFramesKeepInputsAndNeverSubmit()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->requests.add(r));View form=root();
            for(String[] pair:new String[][]{{"Centre X (0.2..0.8)",".1"},{"Centre Y (0.2..0.8)",".9"},{"Size (0.4..1.2)","1.3"},{"Motion (0..1)","NaN"},{"Wrist motion (0..1)","bad"},{"Skin colour (ARGB)","bad"},{"Nail colour (ARGB)","bad"}}){
                EditText field=edit(form,pair[0]);String old=field.getText().toString();field.setText(pair[1]);dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(requests.isEmpty());assertEquals(pair[1],field.getText().toString());field.setText(old);
            }
            custom(form);String before=SketchDocument.canonical(poses(owner));
            for(String channel:CHANNELS){EditText field=edit(form,channel);String old=field.getText().toString();field.setText(channel.startsWith("wrist")?"26":"1.1");
                dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());assertEquals(before,SketchDocument.canonical(poses(owner)));field.setText(old);}
            for(String invalid:new String[]{"0","121","1.5","bad"}){edit(form,"Key frame (1 based)").setText(invalid);click(form,"Add pose");assertEquals(before,SketchDocument.canonical(poses(owner)));}
            edit(form,"Key frame (1 based)").setText("1");
            for(String invalid:new String[]{"0","121","1.5"}){edit(form,"Preview frame (1 based)").setText(invalid);click(form,"Preview hand");assertNull(SketchSolidUiTest.field(owner,"previewTask"));}
            spinner(form,"Pose easing").setSelection(7);ShadowLooper.idleMainLooper();edit(form,"Bezier 1").setText("-0.1");
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());assertTrue(dialog().isShowing());dialog().cancel();
        }
    }

    @Test public void actualNativePreviewMatchesSavedRequestInEveryView()throws Exception {
        for(int v=0;v<VIEWS.length;v++)try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=SketchDocument.canonical(doc);List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();
            spinner(form,"Hand view").setSelection(v);edit(form,"Preview frame (1 based)").setText("61");ShadowLooper.idleMainLooper();
            click(form,"Preview hand");drain(owner);Bitmap actual=previewCopy(form);
            try{
                assertTrue(requests.isEmpty());assertEquals(before,SketchDocument.canonical(doc));dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                JSONObject candidate=apply(doc,requests.get(0));assertEquals(VIEWS[v],operation(requests.get(0)).getJSONObject("settings").getString("view"));
                assertEquals(120,candidate.getInt("frameCount"));assertEquals(30,candidate.getInt("fps"));assertEquals(512,candidate.getInt("width"));assertEquals(512,candidate.getInt("height"));
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),candidate)){Bitmap expected=renderer.render(60);try{assertTrue("Native preview/save parity: "+VIEWS[v],actual.sameAs(expected));}finally{expected.recycle();}}
                assertEquals(before,SketchDocument.canonical(doc));
            }finally{actual.recycle();}
        }
    }

    @Test public void editingAllCustomChannelsChangesActualPixelsAndMatchesNativeSave()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();custom(form);
            edit(form,"Preview frame (1 based)").setText("1");click(form,"Preview hand");drain(owner);Bitmap before=previewCopy(form);
            try{
                for(int i=0;i<CHANNELS.length;i++)edit(form,CHANNELS[i]).setText(String.valueOf(i<7?.75:i==7?-9:11));
                assertNull(((ImageView)control(form,"Hand preview")).getDrawable());click(form,"Preview hand");drain(owner);Bitmap actual=previewCopy(form);
                try{
                    assertFalse("Custom joint edits must change native pixels",before.sameAs(actual));dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();JSONObject op=operation(requests.get(0));
                    JSONObject first=op.getJSONArray("poses").getJSONObject(0);for(int i=0;i<CHANNELS.length;i++)assertEquals(i<7?.75:i==7?-9:11,first.getDouble(CHANNELS[i]),0);
                    try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),apply(doc,requests.get(0)))){Bitmap expected=renderer.render(0);try{assertTrue(actual.sameAs(expected));}finally{expected.recycle();}}
                }finally{actual.recycle();}
            }finally{before.recycle();}
        }
    }

    @Test public void cancelInterruptsQueuedPreviewAndDoesNotSave()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=SketchDocument.canonical(doc);List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),doc,(r,c)->requests.add(r));
            ExecutorService worker=worker(owner);CountDownLatch entered=new CountDownLatch(1);
            Future<?> blocker=worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try{
                assertTrue(entered.await(10,TimeUnit.SECONDS));View form=root();click(form,"Preview hand");Future<?> pending=(Future<?>)SketchSolidUiTest.field(owner,"previewTask");
                assertNotNull(pending);assertFalse(pending.isDone());dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();
                assertTrue(pending.isCancelled());assertTrue(worker.isShutdown());assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));
                assertTrue((Boolean)SketchSolidUiTest.field(owner,"closed"));assertNull(((ImageView)control(form,"Hand preview")).getDrawable());assertTrue(requests.isEmpty());assertEquals(before,SketchDocument.canonical(doc));
            }finally{blocker.cancel(true);worker.shutdownNow();}
        }
    }

    @Test public void editRejectsAlreadyPostedNativePreviewCallback()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),document(),(r,c)->fail("Preview must not save"));View form=root();click(form,"Preview hand");
            worker(owner).submit(()->{}).get(60,TimeUnit.SECONDS); // Render finished; delivery is queued on the UI looper.
            edit(form,"Motion (0..1)").setText("0.4");ShadowLooper.idleMainLooper();
            assertNull("A stale generation must not publish its bitmap",((ImageView)control(form,"Hand preview")).getDrawable());
            click(form,"Preview hand");drain(owner);assertTrue(((ImageView)control(form,"Hand preview")).getDrawable() instanceof BitmapDrawable);dialog().cancel();
        }
    }

    @Test public void failedAsyncSaveAllowsRetryAndIgnoresOldAndDuplicateCompletions()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();List<Consumer<Exception>> callbacks=new ArrayList<>();open(a.get(),document(),(r,c)->{requests.add(r);callbacks.add(c);});View form=root();AlertDialog editor=dialog();
            edit(form,"Motion (0..1)").setText("0.42");Button save=editor.getButton(AlertDialog.BUTTON_POSITIVE);save.performClick();save.performClick();editor.onBackPressed();
            assertEquals(1,requests.size());assertFalse(save.isEnabled());assertFalse(editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());assertTrue(editor.isShowing());
            Thread callback=new Thread(()->callbacks.get(0).accept(new IllegalStateException("Save conflict")));callback.start();callback.join();ShadowLooper.idleMainLooper();
            assertTrue(save.isEnabled());assertEquals("0.42",edit(form,"Motion (0..1)").getText().toString());assertTrue(status(form).contains("Save conflict"));save.performClick();assertEquals(2,requests.size());
            assertEquals(SketchDocument.canonical(operation(requests.get(0)).getJSONObject("settings")),SketchDocument.canonical(operation(requests.get(1)).getJSONObject("settings")));
            callbacks.get(0).accept(null);ShadowLooper.idleMainLooper();assertTrue(editor.isShowing());callbacks.get(1).accept(null);ShadowLooper.idleMainLooper();assertFalse(editor.isShowing());
        }
    }

    @Test public void synchronousSubmitFailureAndCallbackAfterDismissAreSafe()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<Consumer<Exception>> callbacks=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->{callbacks.add(c);if(callbacks.size()==1)throw new IllegalStateException("Store unavailable");});View form=root();AlertDialog editor=dialog();
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(editor.isShowing());assertTrue(status(form).contains("Store unavailable"));
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();editor.dismiss();ShadowLooper.idleMainLooper();callbacks.get(1).accept(new IllegalStateException("Late failure"));ShadowLooper.idleMainLooper();
            assertTrue((Boolean)SketchSolidUiTest.field(owner,"closed"));assertTrue(worker(owner).isShutdown());assertFalse(editor.isShowing());assertFalse(status(form).contains("Late failure"));
        }
    }

    @Test public void kind8MenuRejectsAdoptedTimingAndCanvasChanges()throws Exception {
        for(JSONObject change:new JSONObject[]{new JSONObject().put("type","set_timing").put("frameCount",180).put("fps",30),new JSONObject().put("type","set_canvas").put("width",960),new JSONObject().put("type","set_canvas").put("height",960)}){
            Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);
            try(ActivityController<SketchStudioActivity> a=studio(context,id)){
                SketchStudioActivity activity=a.get();openMenu(activity);AlertDialog editor=dialog();View form=root();edit(form,"Motion (0..1)").setText("0.42");SketchEditorSession session=(SketchEditorSession)SketchSolidUiTest.field(activity,"session");
                SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",session.document().getLong("revision")).put("operationId","hand_geometry_change").put("operations",new JSONArray().put(change)));
                JSONObject adopted;try(SketchProjectStore store=new SketchProjectStore(context)){adopted=store.get(id);}assertTrue(session.adopt(adopted));String unchanged=SketchDocument.canonical(adopted);
                Method present=SketchStudioActivity.class.getDeclaredMethod("present",JSONObject.class);present.setAccessible(true);present.invoke(activity,session.document());
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);
                try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(unchanged,SketchDocument.canonical(store.get(id)));}
                assertTrue(editor.isShowing());assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertEquals("0.42",edit(form,"Motion (0..1)").getText().toString());assertTrue(status(form).toLowerCase(Locale.ROOT).contains("reopen"));editor.cancel();ShadowLooper.idleMainLooper();
            }
        }
    }

    @Test public void kind8ConflictRetryPreservesConcurrentArtworkAndInputs()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);
        try(ActivityController<SketchStudioActivity> a=studio(context,id)){
            SketchStudioActivity activity=a.get();openMenu(activity);AlertDialog editor=dialog();View form=root();edit(form,"Motion (0..1)").setText("0.42");
            SketchEditorSession session=(SketchEditorSession)SketchSolidUiTest.field(activity,"session");long revision=session.document().getLong("revision");
            JSONObject artwork=SketchEditOps.completeObject(new JSONObject().put("id","concurrent_hand_ink").put("kind","line").put("points",new JSONArray("[[0.1,0.1,1],[0.2,0.2,1]]")));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId","other_hand_artist").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",artwork))));
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);assertTrue(editor.isShowing());assertEquals("0.42",edit(form,"Motion (0..1)").getText().toString());
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);assertFalse(editor.isShowing());
            try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject saved=store.get(id);assertEquals(revision+2,saved.getLong("revision"));assertEquals(2,saved.getJSONArray("layers").length());assertEquals(SketchDocument.canonical(artwork),SketchDocument.canonical(SketchDocument.object(saved,"concurrent_hand_ink")));}
        }
    }

    @Test public void realActivityPreviewPixelsMatchDurablySavedNativeLayer()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);
        try(ActivityController<SketchStudioActivity> a=studio(context,id)){
            SketchStudioActivity activity=a.get();openMenu(activity);AlertDialog editor=dialog();View form=root();
            SketchEditorSession session=(SketchEditorSession)SketchSolidUiTest.field(activity,"session");JSONObject original=SketchDocument.copy(session.document());String before=SketchDocument.canonical(original);
            spinner(form,"Hand view").setSelection(2);spinner(form,"Handedness").setSelection(1);custom(form);
            edit(form,"indexCurl").setText("0.75");edit(form,"thumbOpposition").setText("0.6");edit(form,"wristFlex").setText("-4");edit(form,"Preview frame (1 based)").setText("1");
            ShadowLooper.idleMainLooper();click(form,"Preview hand");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(((ImageView)control(form,"Hand preview")).getDrawable()==null&&System.nanoTime()<deadline&&!status(form).startsWith("Preview unavailable")){
                Thread.sleep(20);ShadowLooper.idleMainLooper();
            }
            Bitmap actual=previewCopy(form);
            try{
                try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals("Preview must not persist",before,SketchDocument.canonical(store.get(id)));}
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);assertFalse("Protected native save must succeed",editor.isShowing());
                JSONObject saved;try(SketchProjectStore store=new SketchProjectStore(context)){saved=store.get(id);}
                assertEquals(original.getLong("revision")+1,saved.getLong("revision"));assertEquals(original.getJSONArray("layers").length()+1,saved.getJSONArray("layers").length());
                for(String key:new String[]{"width","height","frameCount","fps"})assertEquals(original.getInt(key),saved.getInt(key));
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,saved)){Bitmap expected=renderer.render(0);try{assertTrue("Real manual preview must equal the reloaded durable native output",actual.sameAs(expected));}finally{expected.recycle();}}
            }finally{actual.recycle();editor.cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void kind8RejectsStalePageBeforeSubmitting()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);
        try(ActivityController<SketchStudioActivity> a=studio(context,id)){
            SketchStudioActivity activity=a.get();openMenu(activity);AlertDialog editor=dialog();View form=root();SketchEditorSession session=(SketchEditorSession)SketchSolidUiTest.field(activity,"session");String before=SketchDocument.canonical(session.document());
            Field page=SketchStudioActivity.class.getDeclaredField("pageGeneration");page.setAccessible(true);page.setInt(activity,page.getInt(activity)+1);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(activity);assertTrue(editor.isShowing());assertTrue(status(form).contains("Hand animation"));
            try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(before,SketchDocument.canonical(store.get(id)));}editor.cancel();ShadowLooper.idleMainLooper();
        }
    }

    private static ActivityController<SketchStudioActivity> studio(Context context,String id)throws Exception {
        ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).setup();SketchSolidUiTest.flush(a.get());
        Field watching=SketchStudioActivity.class.getDeclaredField("watching");watching.setAccessible(true);watching.setBoolean(a.get(),false);Method stop=SketchStudioActivity.class.getDeclaredMethod("stopLivePlayer");stop.setAccessible(true);stop.invoke(a.get());return a;
    }
    private static void openMenu(SketchStudioActivity activity){click(activity.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Hand animation");ShadowLooper.idleMainLooper();}
    private static Object open(Activity activity,JSONObject doc,SketchConstructionSubmit submit)throws Exception {
        Constructor<SketchHandDialog> constructor=SketchHandDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);constructor.setAccessible(true);
        Object owner=constructor.newInstance(activity,doc,submit);Method show=SketchHandDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();return owner;
    }
    private static JSONObject document()throws Exception {return SketchDocument.create(new JSONObject().put("sketchId","hand_manual").put("width",512).put("height",512).put("frameCount",120).put("fps",30));}
    private static JSONObject operation(JSONObject request)throws Exception {JSONArray ops=request.getJSONArray("operations");assertEquals(2,ops.length());assertEquals("add_layer",ops.getJSONObject(0).getString("type"));assertEquals(ops.getJSONObject(0).getJSONObject("layer").getString("id"),ops.getJSONObject(1).getString("layerId"));return ops.getJSONObject(1);}
    private static JSONObject apply(JSONObject doc,JSONObject request)throws Exception {JSONObject candidate=SketchDocument.copy(doc);JSONArray ops=request.getJSONArray("operations");for(int i=0;i<ops.length();i++)SketchEditOps.apply(candidate,ops.getJSONObject(i));SketchDocument.validate(candidate);return candidate;}
    private static JSONArray poses(Object owner)throws Exception {return (JSONArray)SketchSolidUiTest.field(owner,"poses");}
    private static int unusedFrame(JSONArray poses)throws Exception {for(int frame=1;frame<120;frame++)if(indexAt(poses,frame)<0)return frame;throw new AssertionError("No free frame");}
    private static int indexAt(JSONArray poses,int frame)throws Exception {for(int i=0;i<poses.length();i++)if(poses.getJSONObject(i).getInt("frame")==frame)return i;return -1;}
    private static ExecutorService worker(Object owner)throws Exception {return (ExecutorService)SketchSolidUiTest.field(owner,"worker");}
    private static void drain(Object owner)throws Exception {worker(owner).submit(()->{}).get(60,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static Bitmap previewCopy(View form){ImageView preview=(ImageView)control(form,"Hand preview");assertTrue("Native preview must finish: "+status(form),preview.getDrawable() instanceof BitmapDrawable);return ((BitmapDrawable)preview.getDrawable()).getBitmap().copy(Bitmap.Config.ARGB_8888,false);}
    private static void custom(View form){((CheckBox)control(form,"Custom poses")).setChecked(true);ShadowLooper.idleMainLooper();}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View root(){return dialog().getWindow().getDecorView();}
    private static View control(View root,String label){View view=SketchSolidUiTest.description(root,label);assertNotNull("Missing manual control: "+label,view);return view;}
    private static EditText edit(View root,String label){return (EditText)control(root,label);}
    private static Spinner spinner(View root,String label){return (Spinner)control(root,label);}
    private static String status(View root){return ((TextView)control(root,"Hand animation status")).getText().toString();}
    private static void click(View root,String label){Button button=SketchSolidUiTest.button(root,label);assertNotNull("Missing manual action: "+label,button);button.performClick();ShadowLooper.idleMainLooper();}
}
