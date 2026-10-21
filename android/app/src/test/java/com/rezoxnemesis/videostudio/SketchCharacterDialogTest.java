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
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Real manual controls, native rendering and the activity's revision-bound submit path. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchCharacterDialogTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};

    @Test public void manualDefaultsMatchDesignAndUnsupportedTimingDoesNotOpenControls()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(a.get(),document(),(r,c)->{requests.add(r);c.accept(null);});
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();JSONObject settings=operation(requests.get(0)).getJSONObject("settings");
            assertEquals("front",settings.getString("view"));assertEquals("slender",settings.getString("build"));assertEquals("wool",settings.getString("material"));
            assertEquals("tailored",settings.getString("detail"));assertEquals("pendant",settings.getString("gesture"));assertEquals(.8,settings.getDouble("armLift"),0);assertEquals(0,settings.getDouble("headTilt"),0);
            assertEquals("#FFD6A078",settings.getString("skinColor"));assertEquals("#FF252D3D",settings.getString("hairColor"));assertEquals("#FF263847",settings.getString("coatColor"));
            JSONObject shortDoc=document().put("frameCount",24);String before=SketchDocument.canonical(shortDoc);
            InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->open(a.get(),shortDoc,(r,c)->fail("Invalid timing must not submit")));
            assertTrue(failure.getCause().getMessage().contains("4–8 seconds"));assertEquals(before,SketchDocument.canonical(shortDoc));
        }
    }

    @Test public void controlsDispatchExactSettingsAndOptionalSpeechOnANewLayer()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();JSONObject doc=document();String before=SketchDocument.canonical(doc);
            open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();
            assertEquals(6,spinner(form,"Character view").getCount());
            for(String label:new String[]{"Character build","Coat material","Character detail","Character gesture"})assertEquals(2,spinner(form,label).getCount());
            assertFalse(((CheckBox)control(form,"Timed speech")).isChecked());
            spinner(form,"Character view").setSelection(2);spinner(form,"Character build").setSelection(1);
            spinner(form,"Coat material").setSelection(1);spinner(form,"Character detail").setSelection(0);spinner(form,"Character gesture").setSelection(1);
            edit(form,"Arm lift (0..1)").setText("0.45");edit(form,"Head tilt (-12..12 degrees)").setText("-7");
            edit(form,"Skin colour (ARGB)").setText("#FFB78060");edit(form,"Hair colour (ARGB)").setText("#FF181820");edit(form,"Coat colour (ARGB)").setText("#FF385840");
            ShadowLooper.idleMainLooper();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());
            JSONObject op=operation(requests.get(0)),settings=op.getJSONObject("settings");assertEquals("add_character_animation",op.getString("type"));
            assertTrue(op.getString("characterId").length()<=20);assertFalse(op.has("speech"));assertEquals(10,settings.length());
            assertEquals("right",settings.getString("view"));assertEquals("broad",settings.getString("build"));assertEquals("leather",settings.getString("material"));
            assertEquals("contour",settings.getString("detail"));assertEquals("explain",settings.getString("gesture"));
            assertEquals(.45,settings.getDouble("armLift"),0);assertEquals(-7,settings.getDouble("headTilt"),0);
            assertEquals("#FFB78060",settings.getString("skinColor"));assertEquals("#FF181820",settings.getString("hairColor"));assertEquals("#FF385840",settings.getString("coatColor"));
            assertEquals(before,SketchDocument.canonical(doc));
        }
    }

    @Test public void invalidSettingsAndSpeechKeepInputsWithoutSubmitting()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(a.get(),document(),(r,c)->requests.add(r));View form=root();
            for(String invalid:new String[]{"-0.1","1.1","NaN","bad"}){
                edit(form,"Arm lift (0..1)").setText(invalid);dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(requests.isEmpty());assertEquals(invalid,edit(form,"Arm lift (0..1)").getText().toString());
            }
            edit(form,"Arm lift (0..1)").setText("0.8");edit(form,"Head tilt (-12..12 degrees)").setText("13");
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());
            edit(form,"Head tilt (-12..12 degrees)").setText("0");edit(form,"Coat colour (ARGB)").setText("garbage");
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());
            edit(form,"Coat colour (ARGB)").setText("#FF263847");((CheckBox)control(form,"Timed speech")).setChecked(true);
            edit(form,"Cue end (ms)").setText("0");dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(requests.isEmpty());assertTrue(dialog().isShowing());
            dialog().cancel();
        }
    }

    @Test public void nativePreviewMatchesSavedOperationAcrossAllViewsAndKeepsSourceUntouched()throws Exception {
        for(int v=0;v<VIEWS.length;v++)try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=SketchDocument.canonical(doc);List<JSONObject> requests=new ArrayList<>();
            Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();spinner(form,"Character view").setSelection(v);
            edit(form,"Preview frame (1 based)").setText("91");ShadowLooper.idleMainLooper();click(form,"Preview character");drain(owner);
            ImageView preview=(ImageView)control(form,"Character preview");assertTrue("Native preview must finish: "+text(form),preview.getDrawable() instanceof BitmapDrawable);
            Bitmap actual=((BitmapDrawable)preview.getDrawable()).getBitmap().copy(Bitmap.Config.ARGB_8888,false);
            try{
                assertTrue(requests.isEmpty());assertEquals(before,SketchDocument.canonical(doc));
                edit(form,"Arm lift (0..1)").setText("0.8");assertNull("Editing invalidates preview",preview.getDrawable());
                dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());
                JSONObject candidate=SketchDocument.copy(doc);JSONArray ops=requests.get(0).getJSONArray("operations");
                for(int i=0;i<ops.length();i++)SketchEditOps.apply(candidate,ops.getJSONObject(i));SketchDocument.validate(candidate);
                assertEquals(VIEWS[v],operation(requests.get(0)).getJSONObject("settings").getString("view"));
                assertEquals(180,candidate.getInt("frameCount"));assertEquals(30,candidate.getInt("fps"));
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),candidate)){
                    Bitmap expected=renderer.render(90);try{assertTrue("Preview/save parity for "+VIEWS[v],actual.sameAs(expected));}finally{expected.recycle();}
                }
                assertEquals(before,SketchDocument.canonical(doc));
            }finally{actual.recycle();}
        }
    }

    @Test public void importedGeneratedLineLoadsThroughProductionResolverAndRemainsEditable()throws Exception {
        SketchSpeechAssetsTest.Fixture source=new SketchSpeechAssetsTest.Fixture("english");
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();Object owner=open(a.get(),document(),(r,c)->{requests.add(r);c.accept(null);});AlertDialog editor=dialog();View form=root();
            ((CheckBox)control(form,"Timed speech")).setChecked(true);click(form,"Load generated dialogue");drain(owner);
            AlertDialog picker=dialog();assertNotNull(picker.getListView());Shadows.shadowOf(picker).clickOnItem(0);ShadowLooper.idleMainLooper();drain(owner);
            assertEquals("1000",edit(form,"Cue start (ms)").getText().toString());assertTrue(text(form).contains("Maybe we should go home."));
            JSONObject expected=SketchSpeechAssets.load(a.get(),source.id,0,6000).getJSONObject("speech");
            edit(form,"Cue strength (0..1)").setText("0.63");editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());
            JSONObject speech=operation(requests.get(0)).getJSONObject("speech");expected.getJSONArray("cues").getJSONObject(0).put("strength",.63);
            assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(speech));assertFalse(speech.has("generated"));
        }
    }

    /** Replays the imported actual-PCM fixture envelope; does not run inference or audio playback. */
    @Test public void generatedDialoguePickerPreviewAndSavedCuesMatchNativeGeneratedReference()throws Exception {
        SketchSpeechAssetsTest.Fixture source=new SketchSpeechAssetsTest.Fixture("english");
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();assertEquals(180,doc.getInt("frameCount"));assertEquals(30,doc.getInt("fps"));
            String before=SketchDocument.canonical(doc);List<JSONObject> requests=new ArrayList<>();
            Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});AlertDialog editor=dialog();View form=root();
            ((CheckBox)control(form,"Timed speech")).setChecked(true);spinner(form,"Coat material").setSelection(1);ShadowLooper.idleMainLooper();
            JSONArray choices=SketchSpeechAssets.choices(a.get());int choiceIndex=-1;
            for(int i=0;i<choices.length();i++)if(source.id.equals(choices.getJSONObject(i).getString("requestId"))&&choices.getJSONObject(i).getInt("lineIndex")==0){choiceIndex=i;break;}
            assertTrue("Imported fixture line must be offered by the production picker",choiceIndex>=0);
            click(form,"Load generated dialogue");drain(owner);AlertDialog picker=dialog();assertNotNull(picker.getListView());
            assertEquals(choices.getJSONObject(choiceIndex).getString("label"),picker.getListView().getAdapter().getItem(choiceIndex).toString());
            Shadows.shadowOf(picker).clickOnItem(choiceIndex);ShadowLooper.idleMainLooper();drain(owner);
            JSONObject loaded=SketchSpeechAssets.load(a.get(),source.id,0,6000),speech=loaded.getJSONObject("speech");JSONArray cues=speech.getJSONArray("cues");
            assertTrue(text(form).contains(loaded.getString("text")));assertFalse(loaded.getBoolean("forcedAlignmentVerified"));assertFalse(loaded.getBoolean("audioMixedIntoSketch"));
            byte[] audio=SketchSpeechStudyTest.resource("english.wav");assertEquals(OpenPipelineArtifacts.hash(audio),loaded.getString("audioSha256"));
            assertEquals(SketchDocument.canonical(cues),SketchDocument.canonical((JSONArray)SketchSolidUiTest.field(owner,"cues")));
            int cueIndex=-1,frame=-1;
            for(int i=0;i<cues.length();i++){
                JSONObject cue=cues.getJSONObject(i);int midpoint=(int)Math.floor((cue.getDouble("startMs")+cue.getDouble("endMs"))*30/2000d);
                if(midpoint<180&&SketchSpeechTiming.sample(speech,midpoint*1000d/30)[0]>.1){cueIndex=i;frame=midpoint;break;}
            }
            assertTrue("Fixture must contain a visibly open speech sample on the unchanged frame clock",cueIndex>=0);
            spinner(form,"Speech cue").setSelection(cueIndex);ShadowLooper.idleMainLooper();click(form,"Use selected cue frame");
            assertEquals(String.valueOf(frame+1),edit(form,"Preview frame (1 based)").getText().toString());
            click(form,"Preview character");drain(owner);ImageView preview=(ImageView)control(form,"Character preview");
            assertTrue("Generated-line native preview must finish: "+text(form),preview.getDrawable() instanceof BitmapDrawable);
            Bitmap manual=((BitmapDrawable)preview.getDrawable()).getBitmap().copy(Bitmap.Config.ARGB_8888,false);
            try{
                assertTrue("Preview must not save",requests.isEmpty());assertEquals(before,SketchDocument.canonical(doc));
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals(1,requests.size());assertFalse(editor.isShowing());
                JSONObject request=requests.get(0),op=operation(request);assertEquals("leather",op.getJSONObject("settings").getString("material"));
                assertEquals(SketchDocument.canonical(speech),SketchDocument.canonical(op.getJSONObject("speech")));assertFalse(op.getJSONObject("speech").has("generated"));
                JSONObject saved=SketchDocument.copy(doc);JSONArray ops=request.getJSONArray("operations");for(int i=0;i<ops.length();i++)SketchEditOps.apply(saved,ops.getJSONObject(i));SketchDocument.validate(saved);
                JSONObject generated=SketchDocument.copy(op).put("speech",new JSONObject().put("generated",new JSONObject().put("requestId",source.id).put("lineIndex",0)));
                // Expansion copies the operation through JSON, whose numeric spelling
                // changes 0.0 to 0. Compare at that same boundary; cue/pixel parity
                // below still verifies the actual speech and native artwork.
                JSONObject resolved=SketchSpeechAssets.expand(a.get(),doc,generated);assertEquals(SketchDocument.canonical(SketchDocument.copy(op)),SketchDocument.canonical(SketchDocument.copy(resolved)));
                JSONObject nativeDoc=SketchDocument.copy(doc);SketchEditOps.apply(nativeDoc,ops.getJSONObject(0));SketchEditOps.apply(nativeDoc,resolved);
                assertEquals("Manual cues and generated reference must construct the same editable artwork",SketchDocument.canonical(saved),SketchDocument.canonical(nativeDoc));
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),nativeDoc)){
                    Bitmap expected=renderer.render(frame);try{assertTrue("Manual generated-line preview must match the native generated-reference operation",manual.sameAs(expected));}finally{expected.recycle();}
                }
                JSONObject restOp=SketchDocument.copy(op);JSONArray restCues=restOp.getJSONObject("speech").getJSONArray("cues");for(int i=0;i<restCues.length();i++)restCues.getJSONObject(i).put("shape","rest");
                JSONObject restDoc=SketchDocument.copy(doc);SketchEditOps.apply(restDoc,ops.getJSONObject(0));SketchEditOps.apply(restDoc,restOp);
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),restDoc)){
                    Bitmap rest=renderer.render(frame);try{assertFalse("Loaded speech must visibly change the mouth from rest",manual.sameAs(rest));}finally{rest.recycle();}
                }
                String evidence=System.getenv("VIDEOSTUDIO_CHARACTER_EVIDENCE");File root=new File(evidence==null?"build/character-evidence":evidence,"manual-generated-dialogue");
                SketchActionFilmTest.png(new File(root,"preview.png"),manual);Files.write(new File(root,"audio.wav").toPath(),audio);
                SketchSpeechStudyTest.text(new File(root,"operation.json").toPath(),op.toString(2));SketchSpeechStudyTest.text(new File(root,"cues.json").toPath(),speech.toString(2));
                SketchSpeechStudyTest.text(new File(root,"document.json").toPath(),saved.toString());
                SketchSpeechStudyTest.text(new File(root,"evidence.json").toPath(),new JSONObject().put("source","SketchSpeechAssetsTest English actual-PCM fixture envelope replay")
                        .put("requestId",source.id).put("lineIndex",0).put("frame",frame).put("frameCount",180).put("fps",30).put("audioSha256",loaded.getString("audioSha256"))
                        .put("previewSha256",SketchProjectStore.fileHash(new File(root,"preview.png"))).put("nativePreviewMatchesGeneratedReference",true)
                        .put("freshInference",false).put("forcedAlignmentVerified",false).put("audioMixedIntoSketch",false).put("phoneExecution",false).toString(2));
                assertEquals(before,SketchDocument.canonical(doc));
            }finally{manual.recycle();editor.cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void authoredCueEditingAndPreviewUseTheSameNativeSpeechOperation()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();JSONObject doc=document();Object owner=open(a.get(),doc,(r,c)->{requests.add(r);c.accept(null);});View form=root();
            ((CheckBox)control(form,"Timed speech")).setChecked(true);int count=spinner(form,"Speech cue").getCount();
            edit(form,"Cue start (ms)").setText("100");edit(form,"Cue end (ms)").setText("200");spinner(form,"Cue shape").setSelection(2);
            edit(form,"Cue strength (0..1)").setText("0.7");click(form,"Add cue");assertEquals(count+1,spinner(form,"Speech cue").getCount());
            click(form,"Delete cue");assertEquals(count,spinner(form,"Speech cue").getCount());
            edit(form,"Cue start (ms)").setText("500");edit(form,"Cue end (ms)").setText("650");spinner(form,"Cue shape").setSelection(2);
            click(form,"Apply cue edits");click(form,"Use selected cue frame");assertEquals("18",edit(form,"Preview frame (1 based)").getText().toString());
            click(form,"Preview character");drain(owner);ImageView preview=(ImageView)control(form,"Character preview");
            assertTrue("Speech preview must render: "+text(form),preview.getDrawable() instanceof BitmapDrawable);
            Bitmap actual=((BitmapDrawable)preview.getDrawable()).getBitmap().copy(Bitmap.Config.ARGB_8888,false);
            try{
                dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());JSONObject op=operation(requests.get(0));assertTrue(op.has("speech"));
                assertEquals(500,op.getJSONObject("speech").getJSONArray("cues").getJSONObject(0).getInt("startMs"));
                JSONObject saved=SketchDocument.copy(doc);JSONArray ops=requests.get(0).getJSONArray("operations");for(int i=0;i<ops.length();i++)SketchEditOps.apply(saved,ops.getJSONObject(i));
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),saved)){Bitmap expected=renderer.render(17);try{assertTrue(actual.sameAs(expected));}finally{expected.recycle();}}
            }finally{actual.recycle();}
        }
    }

    @Test public void asynchronousSaveFailureRetainsInputsAndIgnoresDuplicateCallbacks()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();List<Consumer<Exception>> callbacks=new ArrayList<>();
            open(a.get(),document(),(r,c)->{requests.add(r);callbacks.add(c);});View form=root();AlertDialog editor=dialog();
            edit(form,"Arm lift (0..1)").setText("0.42");Button save=editor.getButton(AlertDialog.BUTTON_POSITIVE);save.performClick();save.performClick();editor.onBackPressed();
            assertEquals(1,requests.size());assertFalse(save.isEnabled());assertFalse(editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());assertTrue(editor.isShowing());
            Thread callback=new Thread(()->callbacks.get(0).accept(new IllegalStateException("Save conflict")));callback.start();callback.join();ShadowLooper.idleMainLooper();
            assertTrue(save.isEnabled());assertEquals("0.42",edit(form,"Arm lift (0..1)").getText().toString());assertTrue(text(form).contains("Save conflict"));
            save.performClick();assertEquals(2,requests.size());assertEquals(SketchDocument.canonical(operation(requests.get(0)).getJSONObject("settings")),SketchDocument.canonical(operation(requests.get(1)).getJSONObject("settings")));
            callbacks.get(0).accept(null);ShadowLooper.idleMainLooper();assertTrue(editor.isShowing());callbacks.get(1).accept(null);ShadowLooper.idleMainLooper();assertFalse(editor.isShowing());
        }
    }

    @Test public void cancellationStopsPendingNativePreviewWithoutSubmitting()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();JSONObject doc=document();String before=SketchDocument.canonical(doc);
            Object owner=open(a.get(),doc,(r,c)->requests.add(r));ExecutorService worker=(ExecutorService)SketchSolidUiTest.field(owner,"worker");
            AlertDialog editor=dialog();View form=root();
            CountDownLatch entered=new CountDownLatch(1);Future<?> blocker=worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try{
                assertTrue("Worker gate must be running",entered.await(10,TimeUnit.SECONDS));click(form,"Preview character");
                Future<?> queuedPreview=(Future<?>)SketchSolidUiTest.field(owner,"previewTask");assertNotNull("Preview must be submitted",queuedPreview);
                assertFalse("Preview must remain queued behind the gate",queuedPreview.isDone());
                editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                // Android posts OnDismissListener to the main looper; cleanup runs there.
                ShadowLooper.idleMainLooper();
                assertTrue("Dismissal must close the dialog owner",(Boolean)SketchSolidUiTest.field(owner,"closed"));
                assertTrue("Dismissal must cancel the queued native preview",queuedPreview.isCancelled());
                assertTrue("Dismissal callback must shut down the worker",worker.isShutdown());
                assertTrue("Shutdown must interrupt the gated worker",worker.awaitTermination(10,TimeUnit.SECONDS));
                assertTrue("Cancel must not submit artwork",requests.isEmpty());assertFalse(editor.isShowing());
                assertNull("Cancelled preview must not publish an image",((ImageView)control(form,"Character preview")).getDrawable());
                assertEquals("Cancel must preserve source artwork",before,SketchDocument.canonical(doc));
            }finally{editor.cancel();ShadowLooper.idleMainLooper();blocker.cancel(true);worker.shutdownNow();}
        }
    }

    @Test public void activityRejectsTimingAdoptedBySameSessionAfterCharacterControlsOpened()throws Exception {
        assertConstructionRejectsAdoptedGeometry(new JSONObject().put("type","set_timing").put("frameCount",120).put("fps",30));
    }

    @Test public void activityRejectsCanvasDimensionsAdoptedBySameSessionAfterCharacterControlsOpened()throws Exception {
        for(String dimension:new String[]{"width","height"})
            assertConstructionRejectsAdoptedGeometry(new JSONObject().put("type","set_canvas").put(dimension,960));
    }

    private static void assertConstructionRejectsAdoptedGeometry(JSONObject nativeEdit)throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,180,30);
        try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).setup()){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);Field watching=SketchStudioActivity.class.getDeclaredField("watching");watching.setAccessible(true);watching.setBoolean(studio,false);
            Method stop=SketchStudioActivity.class.getDeclaredMethod("stopLivePlayer");stop.setAccessible(true);stop.invoke(studio);
            click(studio.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Character performance");ShadowLooper.idleMainLooper();
            AlertDialog editor=dialog();View form=root();edit(form,"Arm lift (0..1)").setText("0.42");
            assertTrue(((TextView)control(form,"Character performance description")).getText().toString().contains("180 frames at 30 FPS"));
            SketchEditorSession origin=(SketchEditorSession)SketchSolidUiTest.field(studio,"session");long revision=origin.document().getLong("revision");
            int generation=(Integer)SketchSolidUiTest.field(studio,"loadGeneration"),page=(Integer)SketchSolidUiTest.field(studio,"pageGeneration");
            // A real native geometry/timing edit arrives through the live adoption path, so saving
            // against the session's new revision would succeed without a CAS conflict.
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId","live_geometry_change")
                    .put("operations",new JSONArray().put(nativeEdit)));
            JSONObject adopted;try(SketchProjectStore store=new SketchProjectStore(context)){adopted=store.get(id);}
            assertEquals(revision+1,adopted.getLong("revision"));
            for(String key:new String[]{"width","height","fps","frameCount"})if(nativeEdit.has(key))assertEquals(nativeEdit.getInt(key),adopted.getInt(key));
            assertTrue("A clean session must accept the native live geometry/timing edit",origin.adopt(adopted));
            Method present=SketchStudioActivity.class.getDeclaredMethod("present",JSONObject.class);present.setAccessible(true);present.invoke(studio,origin.document());ShadowLooper.idleMainLooper();
            assertSame("Live adoption must retain the dialog's original session",origin,SketchSolidUiTest.field(studio,"session"));
            assertEquals(generation,SketchSolidUiTest.field(studio,"loadGeneration"));assertEquals(page,SketchSolidUiTest.field(studio,"pageGeneration"));
            assertEquals(adopted.getLong("revision"),origin.document().getLong("revision"));
            String unchanged=SketchDocument.canonical(adopted);
            try{
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
                try(SketchProjectStore store=new SketchProjectStore(context)){
                    assertEquals("Saving stale controls must not create a character after adopted edit "+nativeEdit,unchanged,SketchDocument.canonical(store.get(id)));
                }
                assertTrue("Canvas/timing mismatch must keep controls open",editor.isShowing());assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertEquals("0.42",edit(form,"Arm lift (0..1)").getText().toString());
                assertTrue("Canvas/timing mismatch must explain that controls need reopening",text(form).toLowerCase(Locale.ROOT).contains("reopen"));
                assertFalse("Canvas/timing mismatch must not masquerade as a CAS conflict",text(form).contains("SKETCH_REVISION_CONFLICT"));
                assertSame(origin,SketchSolidUiTest.field(studio,"session"));assertEquals(unchanged,SketchDocument.canonical(origin.document()));
            }finally{editor.cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void activityConflictRetryPreservesConcurrentNativeArtworkAndCharacterInputs()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,180,30);
        try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).setup()){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);Field watching=SketchStudioActivity.class.getDeclaredField("watching");watching.setAccessible(true);watching.setBoolean(studio,false);
            Method stop=SketchStudioActivity.class.getDeclaredMethod("stopLivePlayer");stop.setAccessible(true);stop.invoke(studio);
            click(studio.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Character performance");ShadowLooper.idleMainLooper();
            AlertDialog editor=dialog();View form=root();edit(form,"Arm lift (0..1)").setText("0.42");
            SketchEditorSession origin=(SketchEditorSession)SketchSolidUiTest.field(studio,"session");long revision=origin.document().getLong("revision");
            JSONObject artwork=SketchEditOps.completeObject(new JSONObject().put("id","concurrent_character_ink").put("kind","line").put("points",new JSONArray("[[0.1,0.1,1],[0.2,0.2,1]]")));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId","other_artist")
                    .put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",artwork))));
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);assertTrue(editor.isShowing());assertEquals("0.42",edit(form,"Arm lift (0..1)").getText().toString());
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);assertFalse("Retry uses adopted authoritative revision",editor.isShowing());
            try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject saved=store.get(id);assertEquals(revision+2,saved.getLong("revision"));assertEquals(2,saved.getJSONArray("layers").length());assertEquals(SketchDocument.canonical(artwork),SketchDocument.canonical(SketchDocument.object(saved,"concurrent_character_ink")));}
        }
    }

    private static Object open(Activity activity,JSONObject doc,SketchConstructionSubmit submit)throws Exception {
        Constructor<SketchCharacterDialog> constructor=SketchCharacterDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);constructor.setAccessible(true);
        Object owner=constructor.newInstance(activity,doc,submit);Method show=SketchCharacterDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();return owner;
    }
    private static JSONObject document()throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","character_manual").put("width",1280).put("height",720).put("frameCount",180).put("fps",30));}
    private static JSONObject operation(JSONObject request)throws Exception {JSONArray ops=request.getJSONArray("operations");assertEquals(2,ops.length());assertEquals("add_layer",ops.getJSONObject(0).getString("type"));assertEquals(ops.getJSONObject(0).getJSONObject("layer").getString("id"),ops.getJSONObject(1).getString("layerId"));return ops.getJSONObject(1);}
    private static void drain(Object owner)throws Exception {((ExecutorService)SketchSolidUiTest.field(owner,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View root(){return dialog().getWindow().getDecorView();}
    private static View control(View root,String label){View view=SketchSolidUiTest.description(root,label);assertNotNull("Missing manual control: "+label,view);return view;}
    private static EditText edit(View root,String label){return (EditText)control(root,label);}
    private static Spinner spinner(View root,String label){return (Spinner)control(root,label);}
    private static String text(View root){return ((TextView)control(root,"Character performance status")).getText().toString();}
    private static void click(View root,String label){Button button=SketchSolidUiTest.button(root,label);assertNotNull("Missing manual action: "+label,button);button.performClick();ShadowLooper.idleMainLooper();}
}
