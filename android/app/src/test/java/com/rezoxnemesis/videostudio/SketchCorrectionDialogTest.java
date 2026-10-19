package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchCorrectionDialogTest {
    private Activity activity;
    private SketchCorrectionDialog owner;
    private QueueWorker worker;
    private JSONObject document;
    @After public void cleanup(){if(owner!=null)owner.close();if(activity!=null)activity.finish();}
    @Test public void manualCorrectionEntryPointExists()throws Exception {
        Class<?> type;
        try{type=Class.forName("com.rezoxnemesis.videostudio.SketchCorrectionDialog");}
        catch(ClassNotFoundException missing){throw new AssertionError("Missing app-owned manual review/correction dialog",missing);}
        assertNotNull(type.getDeclaredMethod("show",Activity.class,JSONObject.class,int.class,Consumer.class));
    }
    private void setup(SketchCorrectionDialog.Backend backend,Consumer<JSONObject> applied)throws Exception {setup(backend,applied,false);}
    private void setup(SketchCorrectionDialog.Backend backend,Consumer<JSONObject> applied,boolean human)throws Exception {
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        SketchEditOps.execute(activity,"sketch_create",new JSONObject().put("sketchId","manual").put("operationId","create").put("width",1280).put("height",720).put("frameCount",24).put("fps",24));
        JSONObject objectOperation=human?new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","actor").put("settings",new JSONObject().put("size",1.4)).put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("nearWrist",25).put("farWrist",-25)).put(new JSONObject().put("frame",23).put("nearWrist",25).put("farWrist",-25))):new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","shape").put("kind","path").put("frame",0).put("exposure",24).put("fill",true).put("color","#FF344657").put("commands",commands(.25)));
        SketchEditOps.execute(activity,"sketch_edit",new JSONObject().put("sketchId","manual").put("operationId","draw").put("expectedRevision",1).put("operations",new JSONArray().put(objectOperation)));
        try(SketchProjectStore store=new SketchProjectStore(activity)){document=store.get("manual");}
        worker=new QueueWorker();owner=new SketchCorrectionDialog(activity,document,0,applied,backend,worker);owner.open();idle();
    }
    private JSONArray commands(double x)throws Exception {return new JSONArray().put(new JSONArray().put("M").put(x).put(.2)).put(new JSONArray().put("L").put(x+.16).put(.2)).put(new JSONArray().put("L").put(x+.16).put(.7)).put(new JSONArray().put("L").put(x).put(.7)).put(new JSONArray().put("Z"));}
    private JSONArray correction()throws Exception{return new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","shape").put("patch",new JSONObject().put("commands",commands(.47))));}
    private Object field(String name)throws Exception {Field f=SketchCorrectionDialog.class.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private View root()throws Exception{return (View)field("form");}
    private View find(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    private View view(String label)throws Exception {View v=find(root(),label);assertNotNull(label,v);return v;}
    private void advancedFor(String label)throws Exception {View v=view(label);for(android.view.ViewParent p=v.getParent();p instanceof View;p=p.getParent())if(p==field("advanced")&&((View)p).getVisibility()!=View.VISIBLE){view("Advanced controls").performClick();idle();break;}}
    private void set(String label,String value)throws Exception {advancedFor(label);((EditText)view(label)).setText(value);idle();}
    private void choose(String label,int n)throws Exception {advancedFor(label);((Spinner)view(label)).setSelection(n);idle();}
    private void click(String label)throws Exception {advancedFor(label);assertTrue("Enabled: "+label,view(label).isEnabled());view(label).performClick();idle();}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void finishWork()throws Exception {worker.runNext();idle();}
    private String status()throws Exception{return ((TextView)view("Correction status")).getText().toString();}
    private void start()throws Exception{set("Review target","Correct original placement");click("Start review");finishWork();}
    private void record()throws Exception{set("Review note","Placement needs correction");choose("Visual outcome",1);click("Record note");finishWork();}
    private void prepare()throws Exception{choose("Correction route",1);set("Correction summary","Move the original shape right");set("Correction operations (JSON array)",correction().toString());click("Prepare candidate");finishWork();}
    @Test public void actualNativeManualFlowPreviewsBeforeApplyAndUsesOrdinaryUndo()throws Exception {
        AtomicInteger callbacks=new AtomicInteger();setup((c,a,r)->{assertNotSame(Looper.getMainLooper(),Looper.myLooper());return SketchCorrectionOps.execute(c,a,r);},r->callbacks.incrementAndGet());
        String initial=SketchDocument.canonical(document);start();record();prepare();assertEquals(initial,SketchDocument.canonical(current()));
        click("Preview before");finishWork();Bitmap before=(Bitmap)field("beforeBitmap");assertNotNull(status(),before);
        click("Preview after");finishWork();Bitmap after=(Bitmap)field("afterBitmap");assertNotNull(status(),after);assertFalse(before.sameAs(after));assertEquals(initial,SketchDocument.canonical(current()));
        assertTrue(((TextView)view("Correction scope")).getText().toString().contains("1 native edit(s)"));
        evidence("native-before.png",before);evidence("native-candidate.png",after);screenshot("manual-preview-window.png",true);screenshot("manual-review-window.png",false);
        click("Apply selected candidate");finishWork();assertEquals(1,callbacks.get());assertEquals(3,current().getInt("revision"));assertTrue(worker.isShutdown());
        SketchEditOps.execute(activity,"sketch_undo",new JSONObject().put("sketchId","manual").put("operationId","undo").put("expectedRevision",3));assertEquals(SketchDocument.canonical(document.getJSONArray("layers")),SketchDocument.canonical(current().getJSONArray("layers")));
    }
    private JSONObject current()throws Exception{try(SketchProjectStore store=new SketchProjectStore(activity)){return store.get("manual");}}
    @Test public void normalHumanRecipeFlowNeedsNoIdsOrJsonAndShowsRealNativePreviews()throws Exception {
        JSONArray calls=new JSONArray();setup((c,a,r)->{JSONObject reply=SketchCorrectionOps.execute(c,a,r);calls.put(new JSONObject().put("action",a).put("request",r).put("response",reply));return reply;},r->fail("Preview must not apply"),true);
        assertEquals(View.GONE,((View)field("advanced")).getVisibility());assertFalse(view("Review ID (start or resume)").isShown());assertFalse(view("Correction operations (JSON array)").isShown());assertEquals(0,((Spinner)view("Visual outcome")).getSelectedItemPosition());
        String original=SketchDocument.canonical(current());set("Review target","Relax the arms and inspect contacts");click("Start review");finishWork();set("Review note","Elbow and wrist stance needs a calmer alternative; inspect hands after changing it.");choose("Visual outcome",1);click("Record note");finishWork();click("Relax arms");finishWork();assertTrue(status(),view("Prepare candidate").isEnabled());click("Prepare candidate");finishWork();assertTrue(status(),view("Preview after").isEnabled());
        assertEquals(View.GONE,((View)field("advanced")).getVisibility());assertEquals(original,SketchDocument.canonical(current()));assertTrue(((TextView)view("Correction scope")).getText().toString().contains("Timing and canvas preserved"));assertFalse(((TextView)view("Correction scope")).getText().toString().contains("nativeChecks"));
        click("Preview before");finishWork();Bitmap before=(Bitmap)field("beforeBitmap");click("Preview after");finishWork();Bitmap after=(Bitmap)field("afterBitmap");assertNotNull(status(),before);assertNotNull(status(),after);assertFalse(before.sameAs(after));assertEquals(original,SketchDocument.canonical(current()));
        jsonEvidence("human-native-requests.json",calls.toString(2));evidence("human-native-before.png",before);evidence("human-native-candidate.png",after);screenshot("human-recipe-preview-window.png",true);screenshot("human-review-window.png",false);
        click("Saved reviews");finishWork();AlertDialog picker=(AlertDialog)field("savedDialog");assertTrue(picker.getListView().getAdapter().getItem(0).toString().contains("Relax the arms"));windowEvidence("human-saved-reviews-window.png",picker.getWindow().getDecorView());picker.getListView().performItemClick(null,0,0);idle();finishWork();assertEquals(View.GONE,((View)field("advanced")).getVisibility());assertTrue(status(),view("Preview after").isEnabled());
        click("Advanced controls");assertTrue(view("Review ID (start or resume)").isShown());String id=((EditText)view("Review ID (start or resume)")).getText().toString();click("Advanced controls");assertEquals(View.GONE,((View)field("advanced")).getVisibility());assertEquals(id,((EditText)view("Review ID (start or resume)")).getText().toString());
    }
    @Test public void savedReviewsPageAndResumeWithoutTypingAnId()throws Exception {
        Fake engine=new Fake();setup(engine,r->fail("No edit"));click("Saved reviews");finishWork();assertEquals(16,engine.requests.get(0).getInt("limit"));
        AlertDialog picker=(AlertDialog)field("savedDialog");assertTrue(picker.getListView().getAdapter().getItem(0).toString().contains("Saved target"));picker.getListView().performItemClick(null,1,1);idle();finishWork();assertEquals(1,engine.requests.get(1).getInt("offset"));
        picker=(AlertDialog)field("savedDialog");picker.getListView().performItemClick(null,0,0);idle();finishWork();assertEquals("saved_review",((EditText)view("Review ID (start or resume)")).getText().toString());assertTrue(((TextView)view("Correction history")).getText().toString().contains("Review open"));
    }
    @Test public void disabledRecipesExplainWhyAndRecipeAndJsonRoutesAreExclusive()throws Exception {
        Fake engine=new Fake();setup(engine,r->fail("No apply"));start();click("Load recipe choices");finishWork();Spinner choices=(Spinner)view("Native correction recipe");assertFalse(((android.widget.ListAdapter)choices.getAdapter()).isEnabled(0));assertTrue(choices.getAdapter().getItem(0).toString().contains("Already matches"));
        choose("Native correction recipe",1);assertTrue(((TextView)view("Recipe expected changes")).getText().toString().contains("Existing channels"));set("Correction summary","Neutral arms");set("Notes supporting correction (comma-separated IDs)","note_a");click("Prepare candidate");finishWork();JSONObject recipe=engine.last("sketch_correction_propose");assertEquals("neutral_arms",recipe.getString("recipeId"));assertEquals("shape",recipe.getString("objectId"));assertFalse(recipe.has("operations"));
        choose("Correction route",1);set("Correction operations (JSON array)",correction().toString());click("Prepare candidate");finishWork();JSONObject advanced=engine.last("sketch_correction_propose");assertTrue(advanced.has("operations"));assertFalse(advanced.has("recipeId"));assertFalse(advanced.has("objectId"));
    }
    @Test public void errorsAndDismissalRetainDraftAndCancellationRejectsLatePreview()throws Exception {
        Fake engine=new Fake();setup(engine,r->fail("No apply"));start();set("Review note","Keep my inspected evidence");engine.fail=true;click("Record note");finishWork();assertTrue(status(),status().contains("test rejection"));assertEquals("Keep my inspected evidence",((EditText)view("Review note")).getText().toString());
        engine.fail=false;click("Preview before");click("Cancel work");finishWork();assertNull(field("beforeBitmap"));assertTrue(status().contains("cancelled"));
        owner.close();assertFalse(((AlertDialog)field("dialog")).isShowing());worker=new QueueWorker();owner=new SketchCorrectionDialog(activity,document,0,r->fail("No apply"),engine,worker);owner.open();idle();assertEquals("Keep my inspected evidence",((EditText)view("Review note")).getText().toString());
    }
    @Test public void successfulApplyRefreshFailureStillReportsCommitExactlyOnce()throws Exception {
        AtomicInteger applied=new AtomicInteger();Fake engine=new Fake();setup(engine,r->applied.incrementAndGet());start();set("Notes supporting correction (comma-separated IDs)","note_a");prepare();engine.failRefreshAfterApply=true;click("Apply selected candidate");finishWork();assertEquals(1,applied.get());assertEquals(2,engine.last("sketch_correction_apply").getLong("expectedRevision"));assertEquals(2,engine.last("sketch_correction_apply").getLong("expectedReviewRevision"));assertEquals("No candidate selected",((Spinner)view("Prepared candidate")).getAdapter().getItem(0));
    }
    @Test public void cancelAfterCommittedApplyStillReloadsKnownResultOnceAndCloses()throws Exception {
        AtomicInteger applied=new AtomicInteger();Fake engine=new Fake();setup(engine,r->applied.incrementAndGet());start();set("Notes supporting correction (comma-separated IDs)","note_a");prepare();click("Apply selected candidate");worker.runNext();
        // The native operation returned, while its UI delivery is still queued.
        view("Cancel work").performClick();idle();assertEquals(1,applied.get());assertTrue((boolean)field("closed"));assertFalse(((AlertDialog)field("dialog")).isShowing());idle();assertEquals(1,applied.get());
    }
    @Test public void sourceConflictDisablesPreparationAndApplyButKeepsHistoryAndPreview()throws Exception {
        Fake engine=new Fake();setup(engine,r->fail("No edit"));start();set("Notes supporting correction (comma-separated IDs)","note_a");prepare();engine.matches=false;click("Resume / refresh review");finishWork();assertFalse(view("Apply selected candidate").isEnabled());assertFalse(view("Prepare candidate").isEnabled());assertTrue(view("Preview before").isEnabled());assertTrue(view("Record note").isEnabled());
    }
    @Test public void advancedMultiNoteReferencesSurviveRefreshAndRemainExact()throws Exception {
        Fake engine=new Fake();setup(engine,r->fail("No apply"));start();for(String id:new String[]{"first","second"})engine.state.getJSONArray("notes").put(new JSONObject().put("noteId",id).put("note","Evidence "+id).put("outcome","NEEDS_REWORK").put("frameStart",0).put("frameEnd",23));
        set("Notes supporting correction (comma-separated IDs)","first,second");click("Resume / refresh review");finishWork();assertEquals("first,second",((EditText)view("Notes supporting correction (comma-separated IDs)")).getText().toString());prepare();assertEquals("[\"first\",\"second\"]",engine.last("sketch_correction_propose").getJSONArray("noteIds").toString());click("Advanced controls");assertFalse(view("Notes supporting correction (comma-separated IDs)").isShown());
    }
    @Test public void editorMoreOpensCoordinatorAndApplyReloadsSameOriginForNormalUndo()throws Exception {
        setup(SketchCorrectionOps::execute,r->{});owner.close();activity.finish();
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(activity,SketchStudioActivity.class).putExtra("sketchId","manual"))){
            SketchStudioActivity studio=controller.get();studio.getPreferences(0).edit().putBoolean("watchLiveDrawing",false).remove("lastBookId").commit();controller.create().start().resume().visible();studioIdle(studio);
            java.lang.reflect.Method more=SketchStudioActivity.class.getDeclaredMethod("moreMenu");more.setAccessible(true);more.invoke(studio);AlertDialog tools=ShadowAlertDialog.getLatestAlertDialog();ListView list=tools.getListView();int entry=-1;for(int i=0;i<list.getCount();i++)if("Review & correct".equals(list.getAdapter().getItem(i)))entry=i;assertTrue("Manual More entry",entry>=0);list.performItemClick(list.getAdapter().getView(entry,null,list),entry,entry);studioIdle(studio);
            Field f=SketchStudioActivity.class.getDeclaredField("correctionDialog");f.setAccessible(true);owner=(SketchCorrectionDialog)f.get(studio);assertNotNull(owner);activity=studio;
            set("Review target","Correct placement through More");click("Start review");finishActual();set("Review note","Move right");choose("Visual outcome",1);click("Record note");finishActual();choose("Correction route",1);set("Correction summary","Move right");set("Correction operations (JSON array)",correction().toString());click("Prepare candidate");finishActual();click("Apply selected candidate");finishActual();studioIdle(studio);
            Field sessionField=SketchStudioActivity.class.getDeclaredField("session");sessionField.setAccessible(true);SketchEditorSession session=(SketchEditorSession)sessionField.get(studio);assertEquals("manual",session.document().getString("id"));assertEquals(3,session.document().getInt("revision"));
            java.lang.reflect.Method undo=SketchStudioActivity.class.getDeclaredMethod("history",boolean.class);undo.setAccessible(true);undo.invoke(studio,false);studioIdle(studio);session=(SketchEditorSession)sessionField.get(studio);assertEquals(4,session.document().getInt("revision"));assertEquals(SketchDocument.canonical(document.getJSONArray("layers")),SketchDocument.canonical(session.document().getJSONArray("layers")));
        }
    }
    private void finishActual()throws Exception{ExecutorService executor=(ExecutorService)field("worker");if(!executor.isShutdown())executor.submit(()->{}).get(90,TimeUnit.SECONDS);idle();}
    private static void studioIdle(SketchStudioActivity studio)throws Exception{for(int i=0;i<5;i++){for(String n:new String[]{"io","transfers"}){Field f=SketchStudioActivity.class.getDeclaredField(n);f.setAccessible(true);((ExecutorService)f.get(studio)).submit(()->{}).get(30,TimeUnit.SECONDS);}idle();}}
    @Test public void stoppedActivityCancelsWorkAndRetainsDraftWithoutStaleUiUpdates()throws Exception {
        Fake engine=new Fake();setup(engine,r->fail("No edit"));start();set("Review note","Evidence survives lifecycle stop");click("Preview before");android.app.Application.ActivityLifecycleCallbacks lifecycle=(android.app.Application.ActivityLifecycleCallbacks)field("lifecycle");lifecycle.onActivityStopped(activity);finishWork();assertNull(field("beforeBitmap"));assertEquals(1,engine.actions.stream().filter(a->a.equals("sketch_correction_start")).count());lifecycle.onActivityResumed(activity);assertEquals("Evidence survives lifecycle stop",((EditText)view("Review note")).getText().toString());assertTrue(view("Resume / refresh review").isEnabled());
    }
    private void evidence(String name,Bitmap bitmap)throws Exception {String dir=System.getProperty("correction.evidence","");if(dir.isEmpty())return;File folder=new File(dir);assertTrue(folder.isDirectory()||folder.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(folder,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
    private void jsonEvidence(String name,String value)throws Exception{String dir=System.getProperty("correction.evidence","");if(dir.isEmpty())return;File folder=new File(dir);assertTrue(folder.isDirectory()||folder.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(folder,name))){out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
    private void windowEvidence(String name,View decor)throws Exception{if(System.getProperty("correction.evidence","").isEmpty())return;decor.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));decor.layout(0,0,1080,1920);Bitmap image=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image));evidence(name,image);image.recycle();}
    private void screenshot(String name,boolean previews)throws Exception {
        if(System.getProperty("correction.evidence","").isEmpty())return;AlertDialog dialog=(AlertDialog)field("dialog");View decor=dialog.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));decor.layout(0,0,1080,1920);
        ScrollView scroll=(ScrollView)root().getParent();scroll.scrollTo(0,previews?view("Preview before").getTop():0);idle();Bitmap image=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image));evidence(name,image);image.recycle();
    }
    private static class QueueWorker extends AbstractExecutorService {
        final Queue<Runnable> tasks=new ArrayDeque<>();boolean stopped;
        public void execute(Runnable r){if(stopped)throw new RejectedExecutionException();tasks.add(r);}public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;return new ArrayList<>(tasks);}public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped;}public boolean awaitTermination(long t,TimeUnit u){return stopped;}
        void runNext()throws Exception{Runnable r=tasks.poll();assertNotNull("Queued background work",r);Thread t=new Thread(r,"manual-correction-test-worker");t.start();t.join(90000);assertFalse("Worker completed",t.isAlive());}
    }
    private static class Fake implements SketchCorrectionDialog.Backend {
        final List<String> actions=new ArrayList<>();final List<JSONObject> requests=new ArrayList<>();JSONObject state;boolean fail,matches=true,failRefreshAfterApply,applied;
        public JSONObject execute(android.content.Context c,String a,JSONObject r)throws Exception{
            assertNotSame(Looper.getMainLooper(),Looper.myLooper());actions.add(a);requests.add(new JSONObject(r.toString()));if(fail)throw new IllegalStateException("test rejection");if(a.equals("sketch_correction_get")&&applied&&failRefreshAfterApply)throw new IllegalStateException("lost refresh");
            if(state==null)state=new JSONObject().put("reviewId",r.optString("reviewId","saved_review")).put("target","Saved target").put("status","OPEN").put("revision",1).put("sourceRevision",2).put("notes",new JSONArray()).put("proposals",new JSONArray());
            if(a.equals("sketch_correction_list"))return new JSONObject().put("offset",r.getInt("offset")).put("more",r.getInt("offset")==0).put("reviews",new JSONArray().put(new JSONObject().put("reviewId","saved_review").put("target","Saved target").put("status","OPEN").put("noteCount",1).put("proposalCount",1)));
            if(a.equals("sketch_correction_options"))return new JSONObject().put("options",new JSONArray().put(new JSONObject().put("id","same").put("label","Existing style").put("available",false).put("reason","Already matches").put("expectedChange","None").put("checks",new JSONArray())).put(new JSONObject().put("id","neutral_arms").put("label","Relax arms").put("available",true).put("reason","Available").put("expectedChange","Existing channels relax arms").put("checks",new JSONArray().put("Grip"))));
            if(a.equals("sketch_correction_propose")){state.getJSONArray("proposals").put(new JSONObject().put("proposalId",r.getString("proposalId")).put("summary",r.getString("summary")).put("operationCount",1));state.put("revision",state.getInt("revision")+1);}
            if(a.equals("sketch_correction_apply")){applied=true;state.put("status","APPLIED");}
            return new JSONObject().put("review",new JSONObject(state.toString())).put("sourceMatches",matches);
        }
        JSONObject last(String action){for(int i=actions.size()-1;i>=0;i--)if(actions.get(i).equals(action))return requests.get(i);throw new AssertionError(action);}
    }
}
