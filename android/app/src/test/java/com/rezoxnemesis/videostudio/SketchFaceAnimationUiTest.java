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
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchFaceAnimationUiTest {
    private static final String[] CONTROLS={"Horizontal gaze","Vertical gaze","Left eyelid opening","Right eyelid opening","Pupil dilation","Near focus","Mouth opening","Mouth width","Smile / frown","Jaw horizontal offset","Tongue extension","Upper teeth","Lower teeth","Lip roundness","Brow raise","Brow tilt"};

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
                click(studio.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Face animation");ShadowLooper.idleMainLooper();
                AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
                EditText frame=(EditText)control(root,"New key frame (1 based)");frame.setText("61");click(root,"Add / select key");
                SeekBar opening=(SeekBar)control(root,"Mouth opening");opening.setProgress(45);EditText colour=(EditText)control(root,"Skin colour");colour.setText("#FFC36677");
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
                String error=((TextView)control(root,"Face animation status")).getText().toString();assertTrue("Real persistence failure must reach the retained dialog",error.contains("REVISION"));
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
            SketchFaceDialog.show(a.get(),document(),submit);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
            EditText frame=(EditText)control(root,"New key frame (1 based)");frame.setText("61");click(root,"Add / select key");
            SeekBar opening=(SeekBar)control(root,"Mouth opening");opening.setProgress(45);
            EditText colour=(EditText)control(root,"Skin colour");colour.setText("#FFC36677");
            Button create=dialog.getButton(AlertDialog.BUTTON_POSITIVE),cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            create.performClick();ShadowLooper.idleMainLooper();assertEquals(1,requests.size());assertTrue("Dialog must remain open until persistence completes",dialog.isShowing());
            assertFalse(create.isEnabled());assertFalse(cancel.isEnabled());assertFalse(opening.isEnabled());assertFalse(colour.isEnabled());
            create.performClick();cancel.performClick();click(root,"Delete key");dialog.onBackPressed();ShadowLooper.idleMainLooper();
            assertEquals("Saving must admit only one request",1,requests.size());assertTrue("Cancel and Back must not discard an in-flight save",dialog.isShowing());
            Thread failure=new Thread(()->completions.get(0).accept(new IllegalStateException("Drawing budget exceeded")));failure.start();failure.join();ShadowLooper.idleMainLooper();
            assertTrue(dialog.isShowing());assertTrue(create.isEnabled());assertTrue(cancel.isEnabled());assertTrue(opening.isEnabled());assertTrue(colour.isEnabled());
            assertEquals("61",frame.getText().toString());assertEquals(45,opening.getProgress());assertEquals("#FFC36677",colour.getText().toString());
            assertTrue(((TextView)control(root,"Face animation status")).getText().toString().contains("Drawing budget exceeded"));
            create.performClick();ShadowLooper.idleMainLooper();assertEquals(2,requests.size());assertTrue(dialog.isShowing());assertFalse(create.isEnabled());
            JSONObject first=requests.get(0).getJSONArray("operations").getJSONObject(1),retry=requests.get(1).getJSONArray("operations").getJSONObject(1);
            assertKeysEqual(first.getJSONArray("poses"),retry.getJSONArray("poses"));assertEquals(SketchDocument.canonical(first.getJSONObject("settings")),SketchDocument.canonical(retry.getJSONObject("settings")));
            boolean retained=false;JSONArray keys=retry.getJSONArray("poses");for(int i=0;i<keys.length();i++)if(keys.getJSONObject(i).getInt("frame")==60){retained=true;assertEquals(.45,keys.getJSONObject(i).getDouble("open"),0);}assertTrue("Retry must include the edited pose",retained);
            completions.get(0).accept(null);ShadowLooper.idleMainLooper();assertTrue("An old callback cannot close a newer save",dialog.isShowing());assertFalse(create.isEnabled());
            completions.get(1).accept(null);ShadowLooper.idleMainLooper();assertFalse("Successful commit closes the dialog",dialog.isShowing());assertEquals(2,requests.size());
        }
    }

    @Test public void allSixViewsAndEveryFaceSettingReachTheNativeRequest()throws Exception {
        String[] views={"front","left","right","back","high","low"},shapes={"oval","round","angular"},eyes={"round","almond","slit"},mouths={"soft","cartoon","fang"},presets={"reaction","playful","chew"};
        for(int index=0;index<views.length;index++)try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject doc=document();JSONObject[] result={null};SketchFaceDialog.show(a.get(),doc,request->result[0]=request);ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();Spinner view=(Spinner)control(root,"Face view");
            assertEquals(6,view.getCount());assertEquals("Default face view is front",0,view.getSelectedItemPosition());view.setSelection(index);
            select(root,"Face shape",index%3);select(root,"Eye shape",index%3);select(root,"Mouth shape",index%3);select(root,"Face motion",index%3);ShadowLooper.idleMainLooper();
            String[] labels={"Centre X (%)","Centre Y (%)","Face width (%)","Face height (%)","Jaw width (%)","Nose width (%)","Nose length (%)","Eye size (%)","Mouth size (%)","Outline width (px)","Face tilt (degrees)"};
            String[] keys={"centerX","centerY","width","height","jawWidth","noseWidth","noseLength","eyeSize","mouthSize","outlinePx","tilt"};
            double[] input={45,42,36,68,65,14,15,110,90,3,-5};
            for(int i=0;i<labels.length;i++)((EditText)control(root,labels[i])).setText(String.valueOf(input[i]));
            String[] colours={"Skin colour","Hair colour","Iris colour","Lip colour","Outline colour"},colourKeys={"skinColor","hairColor","irisColor","lipColor","ink"};
            String[] pigments={"#FFE3B698","#FF443355","#FF8C7744","#FFC36677","#FF29263B"};
            for(int i=0;i<colours.length;i++)((EditText)control(root,colours[i])).setText(pigments[i]);((CheckBox)control(root,"Hair")).setChecked(false);
            assertEquals("Preset changes preserve view",index,view.getSelectedItemPosition());String context=((TextView)control(root,"Face preview context")).getText().toString().toLowerCase(java.util.Locale.ROOT);assertTrue(context.contains(views[index]));
            if(index==3){assertTrue(context.contains("hidden"));assertTrue(context.contains("head"));}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);JSONObject op=result[0].getJSONArray("operations").getJSONObject(1),settings=op.getJSONObject("settings");
            assertEquals("add_face_animation",op.getString("type"));assertTrue(op.getString("faceId").length()<=28);assertEquals(views[index],settings.getString("view"));
            assertEquals(shapes[index%3],settings.getString("shape"));assertEquals(eyes[index%3],settings.getString("eyeStyle"));assertEquals(mouths[index%3],settings.getString("mouthStyle"));assertEquals(presets[index%3],op.getString("preset"));assertFalse(settings.getBoolean("hair"));
            for(int i=0;i<keys.length;i++)assertEquals(keys[i],input[i]/(i>=9?1:100),settings.getDouble(keys[i]),0);
            for(int i=0;i<colourKeys.length;i++)assertEquals(pigments[i],settings.getString(colourKeys[i]));assertKeysEqual(SketchFaceAnimation.preset(doc,presets[index%3]),op.getJSONArray("poses"));ShadowLooper.idleMainLooper();
        }
    }

    @Test public void allSixteenSlidersAndKeyEditingPreserveAuthoredPoses()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject[] result={null};SketchFaceDialog.show(a.get(),document(),request->result[0]=request);ShadowLooper.idleMainLooper();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
            EditText frame=(EditText)control(root,"New key frame (1 based)");frame.setText("61");click(root,"Add / select key");
            int[] progress={170,40,35,65,80,70,75,80,160,30,55,65,25,85,175,20};double[] values={.7,-.6,.35,.65,1.3,.35,.75,1.3,.6,-.7,.55,.65,.25,.85,.75,-.8};
            for(int i=0;i<CONTROLS.length;i++)((SeekBar)control(root,CONTROLS[i])).setProgress(progress[i]);int count=((Spinner)control(root,"Face pose")).getCount();click(root,"Add / select key");assertEquals(count,((Spinner)control(root,"Face pose")).getCount());
            for(int i=0;i<CONTROLS.length;i++)assertEquals(progress[i],((SeekBar)control(root,CONTROLS[i])).getProgress());
            frame.setText("81");click(root,"Add / select key");click(root,"Delete key");frame.setText("81");click(root,"Add / select key");click(root,"Delete key");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);JSONArray ops=result[0].getJSONArray("operations");assertEquals(2,ops.length());assertEquals("add_layer",ops.getJSONObject(0).getString("type"));JSONObject op=ops.getJSONObject(1);
            assertEquals(ops.getJSONObject(0).getJSONObject("layer").getString("id"),op.getString("layerId"));assertEquals("front",op.getJSONObject("settings").getString("view"));assertTrue(op.getJSONObject("settings").getBoolean("hair"));
            JSONArray poses=op.getJSONArray("poses");SketchFaceAnimation.validatePoses(poses,120);boolean found=false;assertEquals(0,poses.getJSONObject(0).getInt("frame"));
            for(int i=0;i<poses.length();i++){JSONObject pose=poses.getJSONObject(i);assertNotEquals(80,pose.getInt("frame"));if(pose.getInt("frame")==60){found=true;for(int c=0;c<values.length;c++)assertEquals(SketchFaceAnimation.CHANNELS[c],values[c],pose.getDouble(SketchFaceAnimation.CHANNELS[c]),1e-9);}}
            assertTrue("Edited pose must survive creating the face",found);ShadowLooper.idleMainLooper();
        }
    }

    @Test public void selectingSparsePoseDisplaysInheritedChannelsWithoutMaterializingThem()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject[] result={null};SketchFaceDialog owner=owner(a.get(),request->result[0]=request);
            double[] state={.7,-.6,.35,.65,1.3,.35,.75,1.3,.6,-.7,.55,.65,.25,.85,.75,-.8};JSONArray sparse=new JSONArray().put(SketchFaceAnimation.pose(0,state)).put(new JSONObject().put("frame",60).put("open",.8).put("interpolation","smooth"));
            java.lang.reflect.Field keys=SketchFaceDialog.class.getDeclaredField("poses");keys.setAccessible(true);keys.set(owner,sparse);invoke(owner,"refresh");invoke(owner,"show");ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();select(root,"Face pose",1);ShadowLooper.idleMainLooper();int[] progress={170,40,35,65,80,70,80,80,160,30,55,65,25,85,175,20};
            for(int i=0;i<CONTROLS.length;i++)assertEquals(CONTROLS[i],progress[i],((SeekBar)control(root,CONTROLS[i])).getProgress());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);JSONArray actual=result[0].getJSONArray("operations").getJSONObject(1).getJSONArray("poses");assertKeysEqual(sparse,actual);assertEquals(3,actual.getJSONObject(1).length());ShadowLooper.idleMainLooper();
        }
    }

    @Test public void invalidSettingsAndStartingPoseDeletionKeepTheEditorOpen()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject[] result={null};SketchFaceDialog.show(a.get(),document(),request->result[0]=request);ShadowLooper.idleMainLooper();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();Spinner poses=(Spinner)control(root,"Face pose");int count=poses.getCount();
            click(root,"Delete key");assertEquals(count,poses.getCount());assertTrue(((TextView)control(root,"Face animation status")).getText().toString().contains("starting pose"));
            for(String bad:new String[]{"0","121","abc"}){((EditText)control(root,"New key frame (1 based)")).setText(bad);click(root,"Add / select key");assertEquals(count,poses.getCount());}
            ((EditText)control(root,"Face width (%)")).setText("99");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNull(result[0]);assertTrue(dialog.isShowing());
            ((EditText)control(root,"Face width (%)")).setText("34");((EditText)control(root,"Skin colour")).setText("invalid");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNull(result[0]);assertTrue(dialog.isShowing());dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();
        }
    }

    @Test public void actualFaceMenuPreviewAndSaveMatchNativeAgentGeometry()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(c,120,30);
        try(ActivityController<SketchStudioActivity> a=activity(c,id)){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);click(studio.getWindow().getDecorView(),"Brush / Text");SketchSolidUiTest.choose("Face animation");ShadowLooper.idleMainLooper();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();((SeekBar)control(root,"Horizontal gaze")).setProgress(170);((SeekBar)control(root,"Mouth opening")).setProgress(70);((SeekBar)control(root,"Brow raise")).setProgress(165);((EditText)control(root,"Skin colour")).setText("#FFE3B698");
            click(root,"Preview pose");ImageView preview=(ImageView)control(root,"Face pose preview");awaitPreview(preview);SketchActionFilmTest.png(new java.io.File(SketchFaceAnimationTest.evidence("face_manual"),"native-pose-preview.png"),((android.graphics.drawable.BitmapDrawable)preview.getDrawable()).getBitmap());
            try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject saved=store.get(id);assertEquals(1,saved.getInt("revision"));assertEquals(1,saved.getJSONArray("layers").length());}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
            try(SketchProjectStore store=new SketchProjectStore(c)){
                JSONObject saved=store.get(id);assertEquals(2,saved.getInt("revision"));assertEquals(2,saved.getJSONArray("layers").length());assertEquals(0,saved.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());assertEquals(120,saved.getInt("frameCount"));assertEquals(30,saved.getInt("fps"));
                JSONObject layer=saved.getJSONArray("layers").getJSONObject(1),expected=document();JSONArray poses=SketchFaceAnimation.preset(expected,"reaction");poses.getJSONObject(0).put("gazeX",.7).put("open",.7).put("browRaise",.65);
                SketchFaceAnimation.apply(expected,new JSONObject().put("type","add_face_animation").put("layerId","layer_1").put("faceId",layer.getString("id")).put("settings",new JSONObject().put("skinColor","#FFE3B698")).put("preset","reaction").put("poses",poses));
                JSONArray actual=layer.getJSONArray("objects");assertTrue(actual.length()>0);JSONArray expectedObjects=new JSONArray(expected.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").toString());assertEquals(SketchDocument.canonical(expectedObjects),SketchDocument.canonical(actual));
            }
            capture(studio,0);capture(studio,45);
        }
    }

    @Test public void dismissingInFlightFacePreviewCancelsWorkerAndReleasesBitmap()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject[] result={null};SketchFaceDialog owner=owner(a.get(),request->result[0]=request);invoke(owner,"show");ShadowLooper.idleMainLooper();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();ImageView image=(ImageView)control(root,"Face pose preview");
            click(root,"Preview pose");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();ExecutorService worker=(ExecutorService)SketchSolidUiTest.field(owner,"worker");assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));ShadowLooper.idleMainLooper();assertNull(image.getDrawable());assertNull(result[0]);
        }
    }

    private static void assertKeysEqual(JSONArray expected,JSONArray actual)throws Exception {
        assertEquals(expected.length(),actual.length());for(int i=0;i<expected.length();i++){JSONObject a=expected.getJSONObject(i),b=actual.getJSONObject(i);assertEquals(a.length(),b.length());java.util.Iterator<String> fields=a.keys();while(fields.hasNext())assertTrue(b.has(fields.next()));assertEquals(a.getInt("frame"),b.getInt("frame"));assertEquals(a.optString("interpolation"),b.optString("interpolation"));for(String channel:SketchFaceAnimation.CHANNELS)if(a.has(channel))assertEquals(channel,a.getDouble(channel),b.getDouble(channel),0);if(a.has("curve")){JSONArray x=a.getJSONArray("curve"),y=b.getJSONArray("curve");assertEquals(x.length(),y.length());for(int c=0;c<x.length();c++)assertEquals(x.getDouble(c),y.getDouble(c),0);}}
    }
    private static JSONObject document()throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","face_manual").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));}
    private static SketchFaceDialog owner(android.app.Activity activity,Consumer<JSONObject> callback)throws Exception{java.lang.reflect.Constructor<SketchFaceDialog> c=SketchFaceDialog.class.getDeclaredConstructor(android.app.Activity.class,JSONObject.class,Consumer.class);c.setAccessible(true);return c.newInstance(activity,document(),callback);}
    private static void invoke(SketchFaceDialog owner,String name)throws Exception{java.lang.reflect.Method m=SketchFaceDialog.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(owner);}
    private static ActivityController<SketchStudioActivity> activity(Context c,String id){ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id));a.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",false).remove("lastBookId").commit();return a.create().start().resume().visible();}
    private static View control(View root,String name){View view=SketchSolidUiTest.description(root,name);assertNotNull("Missing manual control: "+name,view);return view;}
    private static void select(View root,String name,int index){((Spinner)control(root,name)).setSelection(index);}
    private static void click(View root,String name){Button b=SketchSolidUiTest.button(root,name);assertNotNull("Missing action: "+name,b);b.performClick();ShadowLooper.idleMainLooper();}
    private static void awaitPreview(ImageView image)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(image.getDrawable()==null&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper();Thread.sleep(20);}assertNotNull("Native face preview must finish",image.getDrawable());}
    private static void capture(SketchStudioActivity studio,int frame)throws Exception {
        java.lang.reflect.Method set=SketchStudioActivity.class.getDeclaredMethod("setFrame",int.class);set.setAccessible(true);set.invoke(studio,frame);SketchSolidUiTest.flush(studio);View root=studio.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);Bitmap image=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);
        try{root.draw(new Canvas(image));Object canvas=SketchSolidUiTest.field(studio,"canvas");((ExecutorService)SketchSolidUiTest.field(canvas,"rasterWorker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();root.draw(new Canvas(image));int ink=0;for(int y=650;y<1240;y++)for(int x=20;x<1060;x++){int colour=image.getPixel(x,y);if(Color.blue(colour)<110&&Color.red(colour)<110)ink++;}assertTrue("Actual Activity must display the native face",ink>100);SketchActionFilmTest.png(new java.io.File(SketchFaceAnimationTest.evidence("face_manual"),"editor-frame-"+frame+".png"),image);}finally{image.recycle();}
    }
}
