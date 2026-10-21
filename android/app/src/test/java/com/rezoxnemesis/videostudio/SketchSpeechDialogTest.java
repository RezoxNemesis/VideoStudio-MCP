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
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Exercises real manual controls and native output; reflection permits a useful pre-UI red run. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSpeechDialogTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};

    @Test public void realConcurrentNativeArtworkSurvivesAConflictAndRetryWithoutEditingCachedRevisions()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);
        try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).setup()){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);
            Field watching=SketchStudioActivity.class.getDeclaredField("watching");watching.setAccessible(true);watching.setBoolean(studio,false);
            Method stop=SketchStudioActivity.class.getDeclaredMethod("stopLivePlayer");stop.setAccessible(true);stop.invoke(studio);
            click(studio.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Speech animation");ShadowLooper.idleMainLooper();
            AlertDialog editor=dialog();View form=root();edit(form,"Cue strength (0..1)").setText("0.63");
            SketchEditorSession origin=(SketchEditorSession)SketchSolidUiTest.field(studio,"session");long revision=origin.document().getLong("revision");
            JSONObject artwork=SketchEditOps.completeObject(new JSONObject().put("id","concurrent_artist_ink").put("kind","line").put("points",new JSONArray("[[0.1,0.1,1],[0.2,0.2,1]]")));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId","other_artist")
                    .put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",artwork))));
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
            assertTrue(editor.isShowing());assertEquals("0.63",edit(form,"Cue strength (0..1)").getText().toString());
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
            assertFalse("Retry must refresh the authoritative revision without test-side mutation",editor.isShowing());
            try(SketchProjectStore store=new SketchProjectStore(context)){
                JSONObject saved=store.get(id);assertEquals(revision+2,saved.getLong("revision"));assertEquals(2,saved.getJSONArray("layers").length());
                assertEquals(SketchDocument.canonical(artwork),SketchDocument.canonical(SketchDocument.object(saved,"concurrent_artist_ink")));
            }
        }
    }

    @Test public void alreadyImportedGeneratedSpeechLoadsIntoTheSameEditableManualCueControls()throws Exception {
        SketchSpeechAssetsTest.Fixture source=new SketchSpeechAssetsTest.Fixture("english");
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(180,30),(r,c)->{requests.add(r);c.accept(null);});
            AlertDialog editor=dialog();click(root(),"Load generated dialogue");((ExecutorService)SketchSolidUiTest.field(owner,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            AlertDialog picker=dialog();assertNotNull(picker.getListView());Shadows.shadowOf(picker).clickOnItem(0);ShadowLooper.idleMainLooper();
            ((ExecutorService)SketchSolidUiTest.field(owner,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            View form=editor.getWindow().getDecorView();assertEquals("1000",edit(form,"Cue start (ms)").getText().toString());
            assertEquals("1325",edit(form,"Cue end (ms)").getText().toString());assertEquals(0,spinner(form,"Cue shape").getSelectedItemPosition());
            assertTrue(((TextView)control(form,"Speech animation status")).getText().toString().contains("Maybe we should go home."));
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());
            JSONObject speech=operation(requests.get(0)).getJSONObject("speech");
            assertEquals(SketchDocument.canonical(SketchSpeechAssets.load(a.get(),source.id,0,6000).getJSONObject("speech")),SketchDocument.canonical(speech));
        }
    }

    @Test public void speechDialogExistsAndDemonstrationFitsEvenTheShortestSketch()throws Exception {
        for(int fps:new int[]{24,30,60})for(int frames:new int[]{24,240})
            try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
                JSONObject doc=document(frames,fps);List<JSONObject> requests=new ArrayList<>();
                open(a.get(),doc,(request,done)->{requests.add(request);done.accept(null);});
                AlertDialog dialog=dialog();View root=root();
                String description=((TextView)control(root,"Speech animation description")).getText().toString().toLowerCase(Locale.ROOT);
                assertTrue(description.contains("authored demonstration"));assertTrue(description.contains("no speech recognition"));
                assertTrue(description.contains("not synthesized or mixed"));assertTrue(description.contains("same timeline offset"));
                assertEquals(2,spinner(root,"Speech target").getCount());assertEquals(6,spinner(root,"Speech view").getCount());
                assertEquals(3,spinner(root,"Mouth style").getCount());assertEquals(3,spinner(root,"Head shape").getCount());
                assertEquals(3,spinner(root,"Eye style").getCount());assertEquals(7,spinner(root,"Cue shape").getCount());
                assertEquals("60",edit(root,"Transition (ms)").getText().toString());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());
                JSONObject op=operation(requests.get(0));assertFalse(op.has("poses"));assertTrue(op.has("speech"));
                JSONArray cues=op.getJSONObject("speech").getJSONArray("cues");assertTrue(cues.length()>1);
                long previous=0;for(int i=0;i<cues.length();i++){JSONObject cue=cues.getJSONObject(i);assertTrue(cue.getLong("startMs")>=previous);previous=cue.getLong("endMs");assertTrue(previous>cue.getLong("startMs"));}
                assertTrue(previous<=frames*1000d/fps);
            }
    }

    @Test public void cueEditingSortsSelectsDeletesAndRejectsInvalidChangesWithoutLosingInputs()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(a.get(),document(120,30),(r,c)->{requests.add(r);c.accept(null);});
            View root=root();Spinner picker=spinner(root,"Speech cue");int count=picker.getCount();
            edit(root,"Cue start (ms)").setText("500");edit(root,"Cue end (ms)").setText("650");
            spinner(root,"Cue shape").setSelection(6);edit(root,"Cue strength (0..1)").setText("0.7");click(root,"Apply cue edits");
            spinner(root,"Speech cue").setSelection(1);ShadowLooper.idleMainLooper();spinner(root,"Speech cue").setSelection(0);ShadowLooper.idleMainLooper();
            assertEquals("0.7",edit(root,"Cue strength (0..1)").getText().toString());assertEquals(6,spinner(root,"Cue shape").getSelectedItemPosition());
            edit(root,"Cue end (ms)").setText("499");click(root,"Apply cue edits");assertEquals("499",edit(root,"Cue end (ms)").getText().toString());
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());assertTrue(dialog().isShowing());
            edit(root,"Cue end (ms)").setText("650");click(root,"Apply cue edits");
            edit(root,"Cue start (ms)").setText("100");edit(root,"Cue end (ms)").setText("200");click(root,"Add cue");
            assertEquals(count+1,picker.getCount());assertEquals(0,picker.getSelectedItemPosition());
            click(root,"Add cue");assertEquals("An existing start selects its cue",count+1,picker.getCount());
            click(root,"Delete cue");assertEquals(count,picker.getCount());
            edit(root,"Cue strength (0..1)").setText("1.1");click(root,"Apply cue edits");
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());
            edit(root,"Cue strength (0..1)").setText("0.7");edit(root,"Transition (ms)").setText("-1");
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());
            edit(root,"Transition (ms)").setText("201");dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());
            edit(root,"Transition (ms)").setText("60");dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());
            JSONObject cue=operation(requests.get(0)).getJSONObject("speech").getJSONArray("cues").getJSONObject(0);
            assertEquals(500,cue.getInt("startMs"));assertEquals("tongue",cue.getString("shape"));assertEquals(.7,cue.getDouble("strength"),0);
        }
    }

    @Test public void overlappingOutOfRangeAndFractionalTimesCannotSubmit()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(a.get(),document(120,30),(r,c)->requests.add(r));View root=root();
            for(String[] invalid:new String[][]{{"500","1000"},{"-1","650"},{"500.5","650"},{"500","4001"},{"650","650"}}){
                edit(root,"Cue start (ms)").setText(invalid[0]);edit(root,"Cue end (ms)").setText(invalid[1]);
                dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());assertTrue(dialog().isShowing());
                assertEquals(invalid[0],edit(root,"Cue start (ms)").getText().toString());
            }
            dialog().cancel();
        }
    }

    @Test public void cueListIsBoundedAndDeletingFreesAnEditableSlot()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(120,30),(r,c)->{requests.add(r);c.accept(null);});
            JSONArray full=new JSONArray();for(int i=0;i<240;i++)full.put(new JSONObject().put("startMs",i*10).put("endMs",i*10+2).put("shape","closed").put("strength",1));
            Field cues=owner.getClass().getDeclaredField("cues");cues.setAccessible(true);cues.set(owner,full);
            Method refresh=owner.getClass().getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(owner);ShadowLooper.idleMainLooper();
            View root=root();edit(root,"Cue start (ms)").setText("3000");edit(root,"Cue end (ms)").setText("3100");click(root,"Add cue");
            assertEquals(240,spinner(root,"Speech cue").getCount());assertTrue(((TextView)control(root,"Speech animation status")).getText().toString().contains("240"));
            click(root,"Delete cue");assertEquals(239,spinner(root,"Speech cue").getCount());
            edit(root,"Cue start (ms)").setText("3000");edit(root,"Cue end (ms)").setText("3100");edit(root,"Cue strength (0..1)").setText("0");click(root,"Add cue");
            assertEquals(240,spinner(root,"Speech cue").getCount());edit(root,"Transition (ms)").setText("200");dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(1,requests.size());JSONObject speech=operation(requests.get(0)).getJSONObject("speech");assertEquals(240,speech.getJSONArray("cues").length());assertEquals(0,speech.getJSONArray("cues").getJSONObject(239).getDouble("strength"),0);
        }
    }

    @Test public void asynchronousFailureRetainsCueEditsAndOnlyOneRetryCanBeInFlight()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();List<Consumer<Exception>> completions=new ArrayList<>();
            open(a.get(),document(120,30),(r,c)->{requests.add(r);completions.add(c);});View root=root();AlertDialog dialog=dialog();
            edit(root,"Cue strength (0..1)").setText("0.42");spinner(root,"Speech target").setSelection(1);spinner(root,"Speech view").setSelection(2);ShadowLooper.idleMainLooper();
            Button create=dialog.getButton(AlertDialog.BUTTON_POSITIVE);create.performClick();create.performClick();dialog.onBackPressed();
            assertEquals(1,requests.size());assertTrue(dialog.isShowing());assertFalse(create.isEnabled());assertFalse(edit(root,"Cue start (ms)").isEnabled());
            assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
            Thread callback=new Thread(()->completions.get(0).accept(new IllegalStateException("Save failed")));callback.start();callback.join();ShadowLooper.idleMainLooper();
            assertTrue(create.isEnabled());assertTrue(dialog.isShowing());assertEquals("0.42",edit(root,"Cue strength (0..1)").getText().toString());
            assertTrue(((TextView)control(root,"Speech animation status")).getText().toString().contains("Save failed"));
            create.performClick();assertEquals(2,requests.size());
            assertEquals(SketchDocument.canonical(operation(requests.get(0)).getJSONObject("speech")),SketchDocument.canonical(operation(requests.get(1)).getJSONObject("speech")));
            assertEquals("add_face_animation",operation(requests.get(1)).getString("type"));
            completions.get(0).accept(null);ShadowLooper.idleMainLooper();assertTrue(dialog.isShowing());
            completions.get(1).accept(null);ShadowLooper.idleMainLooper();assertFalse(dialog.isShowing());
        }
    }

    @Test public void selectedCuePreviewUsesDetachedNativeOperationsInAllSixViewsForBothTargets()throws Exception {
        for(int target=0;target<2;target++)for(int view=0;view<VIEWS.length;view++)
            try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
                JSONObject doc=document(24,30);String before=SketchDocument.canonical(doc);List<JSONObject> requests=new ArrayList<>();
                Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View root=root();
                spinner(root,"Speech target").setSelection(target);spinner(root,"Speech view").setSelection(view);
                spinner(root,"Mouth style").setSelection(view%3);spinner(root,"Head shape").setSelection(view%3);spinner(root,"Eye style").setSelection(view%3);ShadowLooper.idleMainLooper();
                spinner(root,"Speech cue").setSelection(5);ShadowLooper.idleMainLooper();
                click(root,"Preview selected cue");
                ((ExecutorService)SketchSolidUiTest.field(owner,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
                ImageView image=(ImageView)control(root,"Speech cue preview");assertTrue("Native preview must finish",image.getDrawable() instanceof BitmapDrawable);
                Bitmap actual=((BitmapDrawable)image.getDrawable()).getBitmap();
                assertEquals("Preview must not change source artwork",before,SketchDocument.canonical(doc));assertTrue(requests.isEmpty());
                String status=((TextView)control(root,"Speech animation status")).getText().toString();assertTrue(status.contains(VIEWS[view]));
                JSONObject cue=((JSONArray)SketchSolidUiTest.field(owner,"cues")).getJSONObject(5);
                int frame=Math.min(23,(int)Math.floor((cue.getDouble("startMs")+cue.getDouble("endMs"))*30/2000d));
                Bitmap previewCopy=actual.copy(Bitmap.Config.ARGB_8888,false);
                try{
                    String evidence=System.getenv("VIDEOSTUDIO_SPEECH_EVIDENCE");if(evidence!=null)SketchActionFilmTest.png(new File(evidence,"manual/"+(target==0?"mouth":"face")+"-"+VIEWS[view]+".png"),previewCopy);
                    dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());
                    JSONObject op=operation(requests.get(0));assertEquals(VIEWS[view],op.getJSONObject("settings").getString("view"));assertFalse(op.has("poses"));
                    assertEquals(new String[]{"soft","cartoon","fang"}[view%3],op.getJSONObject("settings").getString(target==0?"style":"mouthStyle"));
                    if(target==1){assertEquals(new String[]{"oval","round","angular"}[view%3],op.getJSONObject("settings").getString("shape"));assertEquals(new String[]{"round","almond","slit"}[view%3],op.getJSONObject("settings").getString("eyeStyle"));}
                    JSONObject expected=SketchDocument.copy(doc);JSONArray ops=requests.get(0).getJSONArray("operations");for(int i=0;i<ops.length();i++)SketchEditOps.apply(expected,ops.getJSONObject(i));SketchDocument.validate(expected);
                    assertEquals(24,expected.getInt("frameCount"));assertEquals(30,expected.getInt("fps"));assertEquals(2,expected.getJSONArray("layers").length());
                    try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),expected)){Bitmap rendered=renderer.render(frame);try{assertTrue("Manual preview and saved native operation must render identically",previewCopy.sameAs(rendered));}finally{rendered.recycle();}}
                    assertEquals(before,SketchDocument.canonical(doc));
                }finally{previewCopy.recycle();}
            }
    }

    @Test public void cancellationStopsPreviewWorkerAndDoesNotSubmit()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(120,30),(r,c)->requests.add(r));
            ExecutorService worker=(ExecutorService)SketchSolidUiTest.field(owner,"worker");
            CountDownLatch entered=new CountDownLatch(1);Future<?> blocker=worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try{assertTrue("Worker gate must be running",entered.await(10,TimeUnit.SECONDS));click(root(),"Preview selected cue");
                Future<?> queuedPreview=(Future<?>)SketchSolidUiTest.field(owner,"previewTask");assertNotNull(queuedPreview);
                assertFalse("Preview must still be queued behind the gate",queuedPreview.isDone());dialog().cancel();
                // Dialog delivers OnDismissListener through the main looper; cleanup runs there.
                ShadowLooper.idleMainLooper();
                assertTrue("Dismissal must close the dialog owner",(Boolean)SketchSolidUiTest.field(owner,"closed"));
                assertTrue("Dismissal must cancel the queued native preview",queuedPreview.isCancelled());
                assertTrue("Dismissal callback must shut down the worker",worker.isShutdown());
                assertTrue("Shutdown must interrupt the blocked worker",worker.awaitTermination(10,TimeUnit.SECONDS));assertTrue(requests.isEmpty());
                assertNull(((ImageView)SketchSolidUiTest.field(owner,"preview")).getDrawable());
            }finally{blocker.cancel(true);worker.shutdownNow();}
        }
    }

    @Test public void activityMenuUsesSharedConstructionLifecycleAndRetainsRealFailedSaveForRetry()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,24,30);
        try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id))){
            SketchStudioActivity studio=a.get();studio.getPreferences(0).edit().putBoolean("watchLiveDrawing",false).remove("lastBookId").commit();a.create().start().resume().visible();SketchSolidUiTest.flush(studio);
            click(studio.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Speech animation");ShadowLooper.idleMainLooper();
            AlertDialog dialog=dialog();View root=root();edit(root,"Cue strength (0..1)").setText("0.6");
            SketchEditorSession origin=(SketchEditorSession)SketchSolidUiTest.field(studio,"session");long revision=origin.document().getLong("revision");
            ExecutorService io=(ExecutorService)SketchSolidUiTest.field(studio,"io");CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
            Future<?> gate=io.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try{
                assertTrue(entered.await(10,TimeUnit.SECONDS));origin.document().put("revision",revision+1);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();a.pause().resume();ShadowLooper.idleMainLooper();
                assertSame(origin,SketchSolidUiTest.field(studio,"session"));assertFalse(((Map<?,?>)SketchSolidUiTest.field(studio,"activeConstructions")).isEmpty());
                release.countDown();gate.get(10,TimeUnit.SECONDS);SketchSolidUiTest.flush(studio);
                assertTrue(dialog.isShowing());assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertEquals("0.6",edit(root,"Cue strength (0..1)").getText().toString());
                assertTrue(((TextView)control(root,"Speech animation status")).getText().toString().contains("REVISION"));assertFalse((Boolean)SketchSolidUiTest.field(studio,"loadingDrawing"));
                origin.document().put("revision",revision);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);assertFalse(dialog.isShowing());
                assertTrue(((Map<?,?>)SketchSolidUiTest.field(studio,"activeConstructions")).isEmpty());
                try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject saved=store.get(id);assertEquals(revision+1,saved.getLong("revision"));assertEquals(2,saved.getJSONArray("layers").length());assertTrue(saved.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").length()>0);}
            }finally{release.countDown();origin.document().put("revision",revision);gate.get(10,TimeUnit.SECONDS);}
        }
    }

    private static Object open(Activity activity,JSONObject doc,SketchConstructionSubmit submit)throws Exception {
        Class<?> type=Class.forName("com.rezoxnemesis.videostudio.SketchSpeechDialog");
        Constructor<?> constructor=type.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);constructor.setAccessible(true);
        Object owner=constructor.newInstance(activity,doc,submit);Method show=type.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();return owner;
    }
    private static JSONObject document(int frames,int fps)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","speech_manual").put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps));}
    private static JSONObject operation(JSONObject request)throws Exception{JSONArray ops=request.getJSONArray("operations");assertEquals(2,ops.length());assertEquals("add_layer",ops.getJSONObject(0).getString("type"));assertEquals(ops.getJSONObject(0).getJSONObject("layer").getString("id"),ops.getJSONObject(1).getString("layerId"));return ops.getJSONObject(1);}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View root(){return dialog().getWindow().getDecorView();}
    private static View control(View root,String label){View view=SketchSolidUiTest.description(root,label);assertNotNull("Missing manual control: "+label,view);return view;}
    private static EditText edit(View root,String label){return (EditText)control(root,label);}
    private static Spinner spinner(View root,String label){return (Spinner)control(root,label);}
    private static void click(View root,String label){Button button=SketchSolidUiTest.button(root,label);assertNotNull("Missing manual action: "+label,button);button.performClick();ShadowLooper.idleMainLooper();}
}
