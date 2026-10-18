package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.view.View;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchMouthAnimationUiTest {
    private static final String[] CONTROLS={"Mouth opening","Mouth width","Smile / frown","Jaw horizontal offset","Tongue extension","Upper teeth","Lower teeth","Lip roundness"};

    @Test public void resumeDuringConstructionKeepsSessionAndFailedSaveCanRetryExistingDialog()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(context,120,30);
        try(ActivityController<SketchStudioActivity> controller=activity(context,id)){
            SketchStudioActivity studio=controller.get();SketchSolidUiTest.flush(studio);
            SketchEditorSession origin=(SketchEditorSession)SketchSolidUiTest.field(studio,"session");
            int generation=(Integer)SketchSolidUiTest.field(studio,"loadGeneration");long revision=origin.document().getLong("revision");
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            ExecutorService io=(ExecutorService)SketchSolidUiTest.field(studio,"io");
            Future<?> gate=io.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            try{
                assertTrue("Persistence queue must be gated before submitting",entered.await(10,TimeUnit.SECONDS));
                click(studio.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Mouth animation");ShadowLooper.idleMainLooper();
                AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
                EditText frame=(EditText)control(root,"New key frame (1 based)");frame.setText("61");click(root,"Add / select key");
                SeekBar opening=(SeekBar)control(root,"Mouth opening");opening.setProgress(45);EditText colour=(EditText)control(root,"Lip colour");colour.setText("#FFC36677");
                // Force a real revision failure after the queued construction resumes; do not change stored artwork.
                origin.document().put("revision",revision+1);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();
                assertEquals("Construction is not a pending stroke save",0,origin.pending());assertTrue((Boolean)SketchSolidUiTest.field(studio,"loadingDrawing"));
                controller.pause().resume();ShadowLooper.idleMainLooper();
                assertSame("Resume must keep the construction's captured editor session",origin,SketchSolidUiTest.field(studio,"session"));
                assertEquals("Resume must not queue a replacement drawing load",generation,((Integer)SketchSolidUiTest.field(studio,"loadGeneration")).intValue());assertTrue(dialog.isShowing());
                release.countDown();gate.get(10,TimeUnit.SECONDS);SketchSolidUiTest.flush(studio);
                assertSame(origin,SketchSolidUiTest.field(studio,"session"));assertTrue(dialog.isShowing());assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertFalse("Failed construction must clear loading before retry",(Boolean)SketchSolidUiTest.field(studio,"loadingDrawing"));
                assertTrue("Failed construction must release its lifecycle guard",((java.util.Map<?,?>)SketchSolidUiTest.field(studio,"activeConstructions")).isEmpty());
                String error=((TextView)control(root,"Mouth animation status")).getText().toString();assertTrue("Real persistence failure must reach the retained dialog",error.contains("REVISION"));
                assertEquals("61",frame.getText().toString());assertEquals(45,opening.getProgress());assertEquals("#FFC36677",colour.getText().toString());
                try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject saved=store.get(id);assertEquals(revision,saved.getLong("revision"));assertEquals(1,saved.getJSONArray("layers").length());}
                origin.document().put("revision",revision);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
                assertFalse("The same dialog must close after retry commits",dialog.isShowing());
                assertTrue("Successful construction must release its lifecycle guard",((java.util.Map<?,?>)SketchSolidUiTest.field(studio,"activeConstructions")).isEmpty());
                try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject saved=store.get(id);assertEquals(revision+1,saved.getLong("revision"));assertEquals(2,saved.getJSONArray("layers").length());assertTrue(saved.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").length()>0);}
                // Other loading flows still need the original resume recovery behavior.
                Object savedSession=SketchSolidUiTest.field(studio,"session");java.lang.reflect.Field loading=SketchStudioActivity.class.getDeclaredField("loadingDrawing");loading.setAccessible(true);loading.setBoolean(studio,true);
                controller.pause().resume();SketchSolidUiTest.flush(studio);assertNotSame("Resume must still reload unrelated interrupted loading",savedSession,SketchSolidUiTest.field(studio,"session"));
            }finally{release.countDown();origin.document().put("revision",revision);gate.get(10,TimeUnit.SECONDS);}
        }
    }

    @Test public void asyncSaveFailureRetainsEditedKeysAndAllowsOneRetryUntilCommit()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            java.util.ArrayList<JSONObject> requests=new java.util.ArrayList<>();
            java.util.ArrayList<java.util.function.Consumer<Exception>> completions=new java.util.ArrayList<>();
            SketchConstructionSubmit submit=(request,completion)->{requests.add(request);completions.add(completion);};
            SketchMouthDialog.show(a.get(),document(120),submit);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
            EditText frame=(EditText)control(root,"New key frame (1 based)");frame.setText("61");click(root,"Add / select key");
            SeekBar opening=(SeekBar)control(root,"Mouth opening");opening.setProgress(45);
            EditText colour=(EditText)control(root,"Lip colour");colour.setText("#FFC36677");
            Button create=dialog.getButton(AlertDialog.BUTTON_POSITIVE),cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            create.performClick();ShadowLooper.idleMainLooper();assertEquals(1,requests.size());assertTrue("Dialog must remain open until persistence completes",dialog.isShowing());
            assertFalse(create.isEnabled());assertFalse(cancel.isEnabled());assertFalse(opening.isEnabled());assertFalse(colour.isEnabled());
            create.performClick();cancel.performClick();click(root,"Delete key");dialog.onBackPressed();ShadowLooper.idleMainLooper();
            assertEquals("Saving must admit only one request",1,requests.size());assertTrue("Cancel and Back must not discard an in-flight save",dialog.isShowing());
            Thread failure=new Thread(()->completions.get(0).accept(new IllegalStateException("Drawing budget exceeded")));failure.start();failure.join();ShadowLooper.idleMainLooper();
            assertTrue(dialog.isShowing());assertTrue(create.isEnabled());assertTrue(cancel.isEnabled());assertTrue(opening.isEnabled());assertTrue(colour.isEnabled());
            assertEquals("61",frame.getText().toString());assertEquals(45,opening.getProgress());assertEquals("#FFC36677",colour.getText().toString());
            assertTrue(((TextView)control(root,"Mouth animation status")).getText().toString().contains("Drawing budget exceeded"));
            create.performClick();ShadowLooper.idleMainLooper();assertEquals(2,requests.size());assertTrue(dialog.isShowing());assertFalse(create.isEnabled());
            JSONObject first=requests.get(0).getJSONArray("operations").getJSONObject(1),retry=requests.get(1).getJSONArray("operations").getJSONObject(1);
            assertPoseKeysEqual(first.getJSONArray("poses"),retry.getJSONArray("poses"));assertEquals(SketchDocument.canonical(first.getJSONObject("settings")),SketchDocument.canonical(retry.getJSONObject("settings")));
            boolean retained=false;JSONArray keys=retry.getJSONArray("poses");for(int i=0;i<keys.length();i++)if(keys.getJSONObject(i).getInt("frame")==60){retained=true;assertEquals(.45,keys.getJSONObject(i).getDouble("open"),0);}assertTrue("Retry must include the edited pose",retained);
            completions.get(0).accept(null);ShadowLooper.idleMainLooper();assertTrue("An old callback cannot close a newer save",dialog.isShowing());assertFalse(create.isEnabled());
            completions.get(1).accept(null);ShadowLooper.idleMainLooper();assertFalse("Successful commit closes the dialog",dialog.isShowing());assertEquals(2,requests.size());
        }
    }

    @Test public void humanCanEditAllChannelsAndAddSelectDeleteKeysWithoutJson()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject[] result={null};SketchMouthDialog.show(a.get(),document(120),r->result[0]=r);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
            EditText frame=(EditText)control(root,"New key frame (1 based)");frame.setText("61");click(root,"Add / select key");
            int[] progress={35,80,170,40,65,75,25,90};double[] values={.35,1.3,.7,-.6,.65,.75,.25,.9};
            for(int i=0;i<CONTROLS.length;i++)((SeekBar)control(root,CONTROLS[i])).setProgress(progress[i]);
            int count=((Spinner)control(root,"Mouth pose")).getCount();click(root,"Add / select key");
            assertEquals("Selecting an existing key must not duplicate it",count,((Spinner)control(root,"Mouth pose")).getCount());
            for(int i=0;i<CONTROLS.length;i++)assertEquals(progress[i],((SeekBar)control(root,CONTROLS[i])).getProgress());
            frame.setText("81");click(root,"Add / select key");click(root,"Delete key");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);
            JSONArray operations=result[0].getJSONArray("operations");assertEquals(2,operations.length());assertEquals("add_layer",operations.getJSONObject(0).getString("type"));
            JSONObject op=operations.getJSONObject(1);assertEquals("add_mouth_animation",op.getString("type"));assertTrue(op.getString("mouthId").length()<=36);
            assertEquals("Manual mouth view defaults to front","front",op.getJSONObject("settings").getString("view"));
            assertEquals(operations.getJSONObject(0).getJSONObject("layer").getString("id"),op.getString("layerId"));
            JSONArray keys=op.getJSONArray("poses");SketchMouthAnimation.validatePoses(keys,120);boolean edited=false;
            for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i);assertNotEquals("Deleted key must not remain",80,key.getInt("frame"));
                if(key.getInt("frame")==60){edited=true;for(int c=0;c<values.length;c++)assertEquals(SketchMouthAnimation.CHANNELS[c],values[c],key.getDouble(SketchMouthAnimation.CHANNELS[c]),1e-9);}}
            assertTrue("All authored channels must be retained",edited);assertEquals(0,keys.getJSONObject(0).getInt("frame"));
        }
    }

    @Test public void manualStylePresetGeometryAndColoursReachTheNativeRequest()throws Exception {
        String[] styles={"soft","cartoon","fang"},presets={"articulation","tongue","chew"};
        for(int index=0;index<styles.length;index++)try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject doc=document(120);JSONObject[] result={null};SketchMouthDialog.show(a.get(),doc,r->result[0]=r);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
            ((Spinner)control(root,"Mouth shape")).setSelection(index);((Spinner)control(root,"Mouth motion")).setSelection(index);ShadowLooper.idleMainLooper();
            ((EditText)control(root,"Mouth width (%)")).setText("40");((EditText)control(root,"Mouth height (%)")).setText("30");
            ((EditText)control(root,"Mouth tilt (degrees)")).setText("-8");((EditText)control(root,"Outline width (px)")).setText("4");
            String[] labels={"Lip colour","Mouth cavity colour","Tongue colour","Tooth colour","Outline colour"};
            String[] fields={"lipColor","cavityColor","tongueColor","toothColor","ink"};String[] colours={"#FFAE5266","#FF351F35","#FFEC8293","#FFFFF6DF","#FF332B3C"};
            for(int i=0;i<labels.length;i++)((EditText)control(root,labels[i])).setText(colours[i]);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);JSONObject op=result[0].getJSONArray("operations").getJSONObject(1),settings=op.getJSONObject("settings");
            assertEquals(styles[index],settings.getString("style"));assertEquals(presets[index],op.getString("preset"));
            assertEquals(.4,settings.getDouble("width"),0);assertEquals(.3,settings.getDouble("height"),0);assertEquals(-8,settings.getDouble("tilt"),0);assertEquals(4,settings.getDouble("outlinePx"),0);
            for(int i=0;i<fields.length;i++)assertEquals(colours[i],settings.getString(fields[i]));
            assertPoseKeysEqual(SketchMouthAnimation.preset(doc,presets[index]),op.getJSONArray("poses"));
        }
    }

    @Test public void inheritedPoseChannelsStaySparseWhenSelectingAndCreating()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject[] result={null};
            java.lang.reflect.Constructor<SketchMouthDialog> constructor=SketchMouthDialog.class.getDeclaredConstructor(android.app.Activity.class,JSONObject.class,java.util.function.Consumer.class);constructor.setAccessible(true);
            SketchMouthDialog owner=constructor.newInstance(a.get(),document(120),(java.util.function.Consumer<JSONObject>)request->result[0]=request);
            JSONArray sparse=new JSONArray().put(SketchMouthAnimation.pose(0,.2,1.25,.6,-.4,.7,.8,.4,.9).put("interpolation","linear"))
                    .put(new JSONObject().put("frame",60).put("open",.8).put("interpolation","smooth"));
            SketchMouthAnimation.validatePoses(sparse,120);
            java.lang.reflect.Field poses=SketchMouthDialog.class.getDeclaredField("poses");poses.setAccessible(true);poses.set(owner,sparse);
            java.lang.reflect.Method refresh=SketchMouthDialog.class.getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(owner);
            java.lang.reflect.Method show=SketchMouthDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
            ((Spinner)control(root,"Mouth pose")).setSelection(1);ShadowLooper.idleMainLooper();
            int[] inheritedProgress={80,75,160,60,70,80,40,90};
            for(int i=0;i<CONTROLS.length;i++)assertEquals("Selected sparse pose must display inherited "+SketchMouthAnimation.CHANNELS[i],inheritedProgress[i],((SeekBar)control(root,CONTROLS[i])).getProgress());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);
            JSONArray actual=result[0].getJSONArray("operations").getJSONObject(1).getJSONArray("poses");assertPoseKeysEqual(sparse,actual);
            assertEquals("Selection must not materialize inherited channels",3,actual.getJSONObject(1).length());
            assertArrayEquals(new double[]{.8,1.25,.6,-.4,.7,.8,.4,.9},SketchMouthAnimation.sample(actual,60),0);
            ShadowLooper.idleMainLooper();
        }
    }

    @Test public void allSixManualViewsSurvivePresetAndPoseChanges()throws Exception {
        String[] views={"front","left","right","back","high","low"};
        for(int index=0;index<views.length;index++)try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject[] result={null};SketchMouthDialog.show(a.get(),document(120),request->result[0]=request);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();Spinner view=(Spinner)control(root,"Mouth view");
            assertEquals("All requested viewpoints must be available",6,view.getCount());assertEquals("Front must be selected initially",0,view.getSelectedItemPosition());
            view.setSelection(index);ShadowLooper.idleMainLooper();
            ((Spinner)control(root,"Mouth motion")).setSelection(index%3);((Spinner)control(root,"Mouth shape")).setSelection(index%3);ShadowLooper.idleMainLooper();
            ((Spinner)control(root,"Mouth pose")).setSelection(1);ShadowLooper.idleMainLooper();((SeekBar)control(root,"Mouth opening")).setProgress(65);
            assertEquals("Preset and pose edits must preserve the selected view",index,view.getSelectedItemPosition());
            String context=((TextView)control(root,"Mouth preview context")).getText().toString().toLowerCase(java.util.Locale.ROOT);
            assertTrue("Preview must identify the selected view",context.contains(views[index]));
            if("back".equals(views[index])){assertTrue("Rear view must explain mouth occlusion",context.contains("hidden"));assertTrue(context.contains("head"));}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);
            JSONObject op=result[0].getJSONArray("operations").getJSONObject(1);assertEquals(views[index],op.getJSONObject("settings").getString("view"));
            assertEquals(.65,op.getJSONArray("poses").getJSONObject(1).getDouble("open"),0);ShadowLooper.idleMainLooper();
        }
    }

    @Test public void invalidInputAndDeletingStartingPoseKeepTheDialogOpen()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject[] result={null};SketchMouthDialog.show(a.get(),document(120),r->result[0]=r);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();Spinner picker=(Spinner)control(root,"Mouth pose");int count=picker.getCount();
            click(root,"Delete key");assertEquals(count,picker.getCount());assertTrue(((TextView)control(root,"Mouth animation status")).getText().toString().contains("starting pose"));
            for(String bad:new String[]{"0","121","abc"}){((EditText)control(root,"New key frame (1 based)")).setText(bad);click(root,"Add / select key");assertEquals(count,picker.getCount());}
            ((EditText)control(root,"Mouth width (%)")).setText("NaN");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNull(result[0]);assertTrue(dialog.isShowing());
            ((EditText)control(root,"Mouth width (%)")).setText("35");((EditText)control(root,"Lip colour")).setText("invalid");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNull(result[0]);assertTrue(dialog.isShowing());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertNull(result[0]);
        }
    }

    @Test public void manualMouthPreviewAndSavedActivityMatchNativeAgentGeometry()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c);
        try(ActivityController<SketchStudioActivity> a=activity(c,id)){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);open(studio);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
            ((SeekBar)control(root,"Mouth opening")).setProgress(70);((SeekBar)control(root,"Tongue extension")).setProgress(60);((EditText)control(root,"Lip colour")).setText("#FFC36677");
            click(root,"Preview pose");ImageView preview=(ImageView)control(root,"Mouth pose preview");awaitPreview(preview);
            SketchActionFilmTest.png(new java.io.File(SketchMouthAnimationTest.evidence("manual"),"native-pose-preview.png"),((android.graphics.drawable.BitmapDrawable)preview.getDrawable()).getBitmap());
            try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject saved=store.get(id);assertEquals("Preview must not write history",1,saved.getInt("revision"));assertEquals(1,saved.getJSONArray("layers").length());}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
            try(SketchProjectStore store=new SketchProjectStore(c)){
                JSONObject saved=store.get(id);assertEquals(2,saved.getInt("revision"));assertEquals(2,saved.getJSONArray("layers").length());assertEquals(0,saved.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
                assertEquals(120,saved.getInt("frameCount"));assertEquals(30,saved.getInt("fps"));JSONObject layer=saved.getJSONArray("layers").getJSONObject(1);JSONArray actual=layer.getJSONArray("objects");assertTrue(actual.length()>0);
                JSONObject expected=document(120);JSONArray poses=SketchMouthAnimation.preset(expected,"articulation");poses.getJSONObject(0).put("open",.7).put("tongue",.6);
                SketchMouthAnimation.apply(expected,new JSONObject().put("type","add_mouth_animation").put("layerId","layer_1").put("mouthId",layer.getString("id"))
                        .put("settings",new JSONObject().put("lipColor","#FFC36677")).put("preset","articulation").put("poses",poses));
                assertEquals(SketchDocument.canonical(expected.getJSONArray("layers").getJSONObject(0).getJSONArray("objects")),SketchDocument.canonical(actual));
            }
            capture(studio,"manual",0);capture(studio,"manual",45);
        }
    }

    @Test public void cancellingAnInFlightPreviewCannotSaveOrPublishAfterDismissal()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c);
        try(ActivityController<SketchStudioActivity> a=activity(c,id)){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);
            java.lang.reflect.Constructor<SketchMouthDialog> constructor=SketchMouthDialog.class.getDeclaredConstructor(android.app.Activity.class,JSONObject.class,java.util.function.Consumer.class);constructor.setAccessible(true);
            SketchMouthDialog owner=constructor.newInstance(studio,document(120),(java.util.function.Consumer<JSONObject>)request->fail("Cancel cannot submit an edit"));
            java.lang.reflect.Method show=SketchMouthDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
            ImageView image=(ImageView)control(root,"Mouth pose preview");click(root,"Preview pose");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();
            // Drain this dialog's worker deterministically rather than sleeping past a race.
            java.lang.reflect.Field field=SketchMouthDialog.class.getDeclaredField("worker");field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(owner);
            assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));ShadowLooper.idleMainLooper();assertNull("Dismissed preview must release its bitmap",image.getDrawable());
            try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject doc=store.get(id);assertEquals(1,doc.getInt("revision"));assertEquals(1,doc.getJSONArray("layers").length());}
        }
    }

    private static void assertPoseKeysEqual(JSONArray expected,JSONArray actual)throws Exception {
        assertEquals("Pose count must be preserved",expected.length(),actual.length());
        for(int i=0;i<expected.length();i++){
            JSONObject before=expected.getJSONObject(i),after=actual.getJSONObject(i);String label="Pose "+i;
            assertEquals(label+" field count must be preserved",before.length(),after.length());
            java.util.Iterator<String> fields=before.keys();while(fields.hasNext()){String field=fields.next();assertTrue(label+" must preserve field "+field,after.has(field));}
            assertEquals(label+" frame",before.getInt("frame"),after.getInt("frame"));
            assertEquals(label+" timing",before.optString("interpolation"),after.optString("interpolation"));
            for(String channel:SketchMouthAnimation.CHANNELS)if(before.has(channel))assertEquals(label+" "+channel,before.getDouble(channel),after.getDouble(channel),0);
            if(before.has("curve")){JSONArray a=before.getJSONArray("curve"),b=after.getJSONArray("curve");assertEquals(label+" curve size",a.length(),b.length());for(int c=0;c<a.length();c++)assertEquals(label+" curve "+c,a.getDouble(c),b.getDouble(c),0);}
        }
    }
    private static JSONObject document(int frames)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","mouth_manual").put("width",1280).put("height",720).put("frameCount",frames).put("fps",30));}
    private static String create(Context c)throws Exception {String id="mouths_"+UUID.randomUUID();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",1280).put("height",720).put("frameCount",120).put("fps",30).put("background","#FFF2DFCB"));return id;}
    private static ActivityController<SketchStudioActivity> activity(Context c,String id){
        ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id));
        a.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",false).remove("lastBookId").commit();return a.create().start().resume().visible();
    }
    private static View control(View root,String label){View v=SketchSolidUiTest.description(root,label);assertNotNull("Missing manual control: "+label,v);return v;}
    private static void click(View root,String label){Button b=SketchSolidUiTest.button(root,label);assertNotNull("Missing manual action: "+label,b);b.performClick();ShadowLooper.idleMainLooper();}
    private static void awaitPreview(ImageView preview)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(preview.getDrawable()==null&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper();Thread.sleep(20);}assertNotNull("Native preview must finish",preview.getDrawable());}
    static void open(SketchStudioActivity studio){click(studio.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Mouth animation");ShadowLooper.idleMainLooper();}
    static void capture(SketchStudioActivity studio,String name,int frame)throws Exception {
        java.lang.reflect.Method set=SketchStudioActivity.class.getDeclaredMethod("setFrame",int.class);set.setAccessible(true);set.invoke(studio,frame);SketchSolidUiTest.flush(studio);
        View root=studio.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);
        Bitmap image=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);
        try{
            root.draw(new Canvas(image));Object canvas=SketchSolidUiTest.field(studio,"canvas");((ExecutorService)SketchSolidUiTest.field(canvas,"rasterWorker")).submit(()->{}).get(30,TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();root.draw(new Canvas(image));int ink=0;
            for(int y=650;y<1240;y++)for(int x=20;x<1060;x++){int colour=image.getPixel(x,y);if(Color.blue(colour)<110&&Color.red(colour)<110)ink++;}
            assertTrue("Actual Activity must display native mouth art",ink>100);
            SketchActionFilmTest.png(new java.io.File(SketchMouthAnimationTest.evidence(name),"editor-frame-"+frame+".png"),image);
        }finally{image.recycle();}
    }
}
