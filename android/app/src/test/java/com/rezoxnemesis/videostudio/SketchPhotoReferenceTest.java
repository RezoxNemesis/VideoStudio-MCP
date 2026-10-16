package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.ExifInterface;
import android.net.Uri;
import android.widget.EditText;
import android.widget.ListView;
import android.view.View;
import android.view.ViewGroup;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPhotoReferenceTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private static final class Source {
        File file; ProjectStore.Project project; ProjectStore.Asset asset; String hash;
    }
    private Source source(Bitmap bitmap,boolean jpeg,int orientation) throws Exception {
        Source source=new Source();source.file=new File(context.getFilesDir(),"photo-reference-"+UUID.randomUUID()+(jpeg?".jpg":".png"));
        try(FileOutputStream out=new FileOutputStream(source.file)){assertTrue(bitmap.compress(jpeg?Bitmap.CompressFormat.JPEG:Bitmap.CompressFormat.PNG,100,out));}
        if(jpeg){ExifInterface exif=new ExifInterface(source.file.getAbsolutePath());exif.setAttribute(ExifInterface.TAG_ORIENTATION,String.valueOf(orientation));exif.saveAttributes();}
        source.hash=SketchProjectStore.fileHash(source.file);
        ProjectStore projects=new ProjectStore(context);source.project=projects.create("Owned photo fixture");source.asset=new ProjectStore.Asset();source.asset.id=UUID.randomUUID().toString();source.asset.name="Photo "+source.asset.id;source.asset.mime=jpeg?"image/jpeg":"image/png";source.asset.uri=Uri.fromFile(source.file).toString();source.project.assets.add(source.asset);projects.save(source.project);return source;
    }
    private String blank() throws Exception {
        String id=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1));return id;
    }
    private JSONObject imported(String id,Source source) throws Exception {
        SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","import").put("expectedRevision",1).put("projectId",source.project.id).put("sourceProjectUpdatedAt",source.project.updatedAt).put("assetId",source.asset.id).put("layerId","layer_1").put("objectId","guide"));
        try(SketchProjectStore store=new SketchProjectStore(context)){return SketchDocument.object(store.get(id),"guide").getJSONObject("reference");}
    }
    private Bitmap gradient(int width,int height) {
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Paint paint=new Paint();paint.setShader(new LinearGradient(0,0,width,height,Color.YELLOW,Color.rgb(220,40,100),Shader.TileMode.CLAMP));new Canvas(bitmap).drawRect(0,0,width,height,paint);return bitmap;
    }

    @Test public void portraitUnderDetailLimitRetainsEverySourcePixelAndOriginalProvenance() throws Exception {
        Bitmap image=gradient(941,1672);for(int y=0;y<image.getHeight();y++)image.setPixel(101,y,Color.BLACK);Source source=source(image,false,1);image.recycle();String id=blank();JSONObject reference=imported(id,source);
        assertEquals("A portrait must not be halved merely because it exceeds 1280 pixels",941,reference.getInt("width"));assertEquals(1672,reference.getInt("height"));
        assertEquals(941,reference.getInt("sourceWidth"));assertEquals(1672,reference.getInt("sourceHeight"));assertEquals(1,reference.getInt("sourceOrientation"));assertEquals(source.hash,reference.getString("sourceSha256"));
        try(SketchProjectStore store=new SketchProjectStore(context)){Bitmap retained=BitmapFactory.decodeFile(store.referenceFile(id,reference).getPath());try{assertEquals("A one-pixel source feature must remain sharp",Color.BLACK,retained.getPixel(101,100));assertNotEquals(Color.BLACK,retained.getPixel(100,100));}finally{retained.recycle();}}
        assertEquals(source.hash,SketchProjectStore.fileHash(source.file));
    }

    @Test public void largerPhotoUsesTheFullBoundedLongEdgeInsteadOfPowerOfTwoUndersampling() throws Exception {
        Bitmap image=gradient(3096,4128);Source source=source(image,false,1);image.recycle();String id=blank();JSONObject reference=imported(id,source);
        assertEquals(1536,reference.getInt("width"));assertEquals(2048,reference.getInt("height"));assertEquals(3096,reference.getInt("sourceWidth"));assertEquals(4128,reference.getInt("sourceHeight"));
        try(SketchProjectStore store=new SketchProjectStore(context)){File retainedFile=store.referenceFile(id,reference);Bitmap retained=BitmapFactory.decodeFile(retainedFile.getPath());try{assertTrue("Owned decoding remains at most 2048 squared ARGB pixels",retained.getAllocationByteCount()<=2048L*2048*4);assertTrue(retainedFile.length()<=8L*1024*1024);}finally{retained.recycle();}File[] leftovers=retainedFile.getParentFile().listFiles((dir,name)->name.startsWith("reference-"));assertNotNull(leftovers);assertEquals("Import must release temporary source and encoded files",0,leftovers.length);}
        assertEquals(source.hash,SketchProjectStore.fileHash(source.file));
    }

    @Test public void allEightExifOrientationsPreserveTheCorrectAsymmetricPhotoCorners() throws Exception {
        int[][] expected={{Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW},{Color.GREEN,Color.RED,Color.YELLOW,Color.BLUE},{Color.YELLOW,Color.BLUE,Color.GREEN,Color.RED},{Color.BLUE,Color.YELLOW,Color.RED,Color.GREEN},{Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW},{Color.BLUE,Color.RED,Color.YELLOW,Color.GREEN},{Color.YELLOW,Color.GREEN,Color.BLUE,Color.RED},{Color.GREEN,Color.YELLOW,Color.RED,Color.BLUE}};
        for(int orientation=1;orientation<=8;orientation++){
            Bitmap image=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);Paint paint=new Paint();int[] colours={Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW};for(int corner=0;corner<4;corner++){paint.setColor(colours[corner]);int x=(corner%2)*48,y=(corner/2)*32;canvas.drawRect(x,y,x+48,y+32,paint);}Source source=source(image,true,orientation);image.recycle();String id=blank();JSONObject reference=imported(id,source);
            assertEquals(orientation>=5?64:96,reference.getInt("width"));assertEquals(orientation>=5?96:64,reference.getInt("height"));assertTrue("Reference provenance must record its source EXIF orientation",reference.has("sourceOrientation"));assertEquals(orientation,reference.getInt("sourceOrientation"));assertEquals(96,reference.getInt("sourceWidth"));assertEquals(64,reference.getInt("sourceHeight"));
            try(SketchProjectStore store=new SketchProjectStore(context)){Bitmap retained=BitmapFactory.decodeFile(store.referenceFile(id,reference).getPath());try{for(int corner=0;corner<4;corner++){int actual=retained.getPixel((corner%2==0?1:3)*retained.getWidth()/4,(corner<2?1:3)*retained.getHeight()/4),colour=expected[orientation-1][corner];assertTrue("EXIF "+orientation+" corner "+corner+" red",Math.abs(Color.red(actual)-Color.red(colour))<25);assertTrue("EXIF "+orientation+" corner "+corner+" green",Math.abs(Color.green(actual)-Color.green(colour))<25);assertTrue("EXIF "+orientation+" corner "+corner+" blue",Math.abs(Color.blue(actual)-Color.blue(colour))<25);}}finally{retained.recycle();}}
            assertEquals("Orientation changes the owned working copy, never the original",source.hash,SketchProjectStore.fileHash(source.file));
        }
    }

    @Test public void humanReferenceImportIsHiddenBeforeTheFirstLiveDrawingBaseline() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String id=blank();
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity activity=controller.get();waitIo(activity);invoke(activity,"referenceDialog");choose(source.project.name+" / "+source.asset.name);waitIo(activity);waitIo(activity);
            JSONObject document;try(SketchProjectStore store=new SketchProjectStore(context)){document=store.get(id);}
            JSONObject guide=SketchDocument.layer(document,"layer_1").getJSONArray("objects").getJSONObject(0);assertEquals("Importing a guide must not display the finished photo as artwork",0,guide.getJSONObject("transform").getDouble("opacity"),0);
            SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","blank-baseline").put("expectedRevision",document.getLong("revision")));
            JSONObject baseline=SketchLiveDrawing.read(context,id,"blank-baseline").getJSONObject("document");try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,baseline)){Bitmap output=renderer.render(0);try{assertEquals("The native live baseline must remain blank",Color.WHITE,output.getPixel(128,128));}finally{output.recycle();}}
        }
    }

    @Test public void hiddenReferenceRemainsAvailableForExplicitManualGuideViewing() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String id=blank();
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity activity=controller.get();waitIo(activity);invoke(activity,"referenceDialog");choose(source.project.name+" / "+source.asset.name);waitIo(activity);waitIo(activity);invoke(activity,"referenceTools");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull("Hidden guides must remain reachable without tapping invisible pixels",edit(dialog.getWindow().getDecorView(),"Reference opacity 0..1"));
            EditText opacity=edit(dialog.getWindow().getDecorView(),"Reference opacity 0..1");opacity.setText("1");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();awaitGuideVisibilityCommit(activity,id);
            try(SketchProjectStore store=new SketchProjectStore(context);SketchFrameRenderer renderer=new SketchFrameRenderer(context,store.get(id))){Bitmap output=renderer.render(0);try{assertEquals(Color.RED,output.getPixel(128,128));}finally{output.recycle();}}
        }
    }

    @Test public void humanTracingHidesTheGuideBeforeCreatingTheLiveSnapshotAndTraceEvent() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String id=blank();imported(id,source);
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity activity=controller.get();waitIo(activity);((SketchCanvasView)field(activity,"canvas")).selectObject("guide");invoke(activity,"referenceTools");ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();waitIo(activity);waitIo(activity);
            JSONObject stream;try(SketchProjectStore store=new SketchProjectStore(context)){stream=store.nextLiveSession(0);assertNotNull("Tracing must create a visible native drawing session",stream);JSONArray events=store.liveEvents(id,stream.getLong("afterSequence"),4).getJSONArray("events");assertTrue(events.length()>0);for(int n=0;n<events.length();n++){JSONArray operations=events.getJSONObject(n).getJSONArray("operations");for(int k=0;k<operations.length();k++)assertFalse("Guide hiding belongs before the live trace event",operations.getJSONObject(k).optString("type").equals("set_transform")&&operations.getJSONObject(k).optString("objectId").equals("guide"));}}
            JSONObject baseline=SketchLiveDrawing.read(context,id,stream.getString("sessionId")).getJSONObject("document");assertEquals(0,SketchDocument.object(baseline,"guide").getJSONObject("transform").getDouble("opacity"),0);try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,baseline)){Bitmap output=renderer.render(0);try{assertEquals(Color.WHITE,output.getPixel(128,128));}finally{output.recycle();}}
        }
    }

    @Test public void manualModeSelectedDuringTracePreparationIsNotOverriddenWhenTheTraceFinishes() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String id=blank();imported(id,source);
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity activity=controller.get();waitIo(activity);button(activity.getWindow().getDecorView(),"Draw myself").performClick();waitIo(activity);assertFalse("Establish manual drawing before selecting a native trace",(boolean)field(activity,"watching"));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            ((ExecutorService)field(activity,"io")).execute(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            try{((SketchCanvasView)field(activity,"canvas")).selectObject("guide");invoke(activity,"referenceTools");ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();ShadowLooper.idleMainLooper();assertTrue("Trace should enter preparation before its IO lane is released; session="+field(activity,"session")+", watching="+field(activity,"watching")+", watchInFlight="+field(activity,"watchInFlight")+", toast="+org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),(boolean)field(activity,"loadingDrawing"));assertTrue("Starting trace replay must expose its Draw myself cancellation control immediately",(boolean)field(activity,"watching"));button(activity.getWindow().getDecorView(),"Draw myself").performClick();assertFalse((boolean)field(activity,"watching"));}finally{release.countDown();}
            waitIo(activity);waitIo(activity);
            assertFalse("Finishing automatic tracing must respect the user's newer manual-drawing selection",(boolean)field(activity,"watching"));assertNull(field(activity,"livePlayer"));assertFalse((boolean)field(activity,"loadingDrawing"));assertTrue(((SketchCanvasView)field(activity,"canvas")).isEnabled());
        }
    }

    @Test public void explicitlyHiddenReferenceIsImportedInOneAtomicNativeRevision() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String id=blank();
        JSONObject result=null;
        try{result=SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","hidden-import").put("expectedRevision",1).put("projectId",source.project.id).put("sourceProjectUpdatedAt",source.project.updatedAt).put("assetId",source.asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false));}
        catch(Exception unsupported){fail("The shared native import must support an atomically hidden guide: "+unsupported.getMessage());}
        assertEquals("Hidden reference import must commit once, rather than import followed by hide",2,result.getLong("revision"));
        try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject document=store.get(id);assertEquals(2,document.getLong("revision"));JSONObject guide=SketchDocument.object(document,"guide");assertEquals(0,guide.getJSONObject("transform").getDouble("opacity"),0);assertEquals(source.asset.id,guide.getJSONObject("reference").getString("sourceAssetId"));assertEquals(source.hash,guide.getJSONObject("reference").getString("sourceSha256"));try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)){Bitmap output=renderer.render(0);try{assertEquals(Color.WHITE,output.getPixel(128,128));}finally{output.recycle();}}}
    }

    @Test public void explicitlyShowingAHiddenGuideBeforeTracingIsReflectedInTheLiveBaseline() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String id=blank();imported(id,source);
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","hide-guide").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","set_transform").put("objectId","guide").put("transform",new JSONObject().put("opacity",0)))));
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity activity=controller.get();waitIo(activity);((SketchCanvasView)field(activity,"canvas")).selectObject("guide");invoke(activity,"referenceTools");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();firstCheckbox(dialog.getWindow().getDecorView()).setChecked(true);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();waitIo(activity);waitIo(activity);
            JSONObject stream;try(SketchProjectStore store=new SketchProjectStore(context)){stream=store.nextLiveSession(0);assertNotNull(stream);}
            JSONObject baseline=SketchLiveDrawing.read(context,id,stream.getString("sessionId")).getJSONObject("document");assertTrue("Explicit Show guide must reveal the hidden guide before the stream starts",SketchDocument.object(baseline,"guide").getJSONObject("transform").getDouble("opacity")>0);
        }
    }

    @Test public void finishingAReferenceImportDoesNotReopenAnOlderSketchAfterTheUserChoosesAnother() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String first=blank(),second=blank();
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",first)).create().start().resume().visible()){
            SketchStudioActivity activity=controller.get();waitIo(activity);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);((ExecutorService)field(activity,"io")).execute(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));
            try{invoke(activity,"referenceDialog");choose(source.project.name+" / "+source.asset.name);Method load=SketchStudioActivity.class.getDeclaredMethod("loadSketch",String.class,int.class);load.setAccessible(true);load.invoke(activity,second,0);}finally{release.countDown();}
            waitIo(activity);waitIo(activity);assertEquals("An import completion must not override the newer sketch selection",second,field(activity,"sketchId"));
            ShadowLooper.idleMainLooper(300,TimeUnit.MILLISECONDS);waitIo(activity);waitIo(activity);
            assertEquals("A later workspace observer must also respect the newer selection after a hidden-guide import",second,field(activity,"sketchId"));
            try(SketchProjectStore store=new SketchProjectStore(context)){JSONArray references=SketchDocument.layer(store.get(first),"layer_1").getJSONArray("objects");assertEquals(1,references.length());assertEquals(0,references.getJSONObject(0).getJSONObject("transform").getDouble("opacity"),0);assertEquals(0,SketchDocument.layer(store.get(second),"layer_1").getJSONArray("objects").length());}
        }
    }

    @Test public void keyedGuideIsHiddenBeforeTheLiveBaselineWithoutChangingAnyMotionKeys() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String id=blank();imported(id,source);JSONObject before=keyedGuide(id,0,.8,.2);
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity activity=controller.get();waitIo(activity);((SketchCanvasView)field(activity,"canvas")).selectObject("guide");invoke(activity,"referenceTools");ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();waitIo(activity);waitIo(activity);
            JSONObject stream;try(SketchProjectStore store=new SketchProjectStore(context)){stream=store.nextLiveSession(0);assertNotNull(stream);}
            JSONObject baseline=SketchLiveDrawing.read(context,id,stream.getString("sessionId")).getJSONObject("document"),hidden=SketchDocument.object(baseline,"guide");
            for(int frame=0;frame<3;frame++)assertEquals("Animation keys must not override the hidden guide at frame "+frame,0,SketchDocument.transformAt(hidden,frame).getDouble("opacity"),0);
            assertEquals("Hiding a guide preserves all authored motion and interpolation",motion(before),motion(hidden));
            JSONObject current;try(SketchProjectStore store=new SketchProjectStore(context)){current=store.get(id);}
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","show-keyed-guide").put("expectedRevision",current.getLong("revision")).put("operations",new JSONArray().put(new JSONObject().put("type","set_reference_visibility").put("objectId","guide").put("visible",true))));
            try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject shown=SketchDocument.object(store.get(id),"guide");assertEquals(motion(before),motion(shown));for(int frame=0;frame<3;frame++)assertTrue("Shared visibility operation also reveals keyed references",SketchDocument.transformAt(shown,frame).getDouble("opacity")>0);}
        }
        assertEquals(source.hash,SketchProjectStore.fileHash(source.file));
    }

    @Test public void showGuideRevealsZeroOpacityAnimationKeysInTheLiveBaseline() throws Exception {
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);Source source=source(image,false,1);image.recycle();String id=blank();imported(id,source);JSONObject before=keyedGuide(id,0,0,0);
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity activity=controller.get();waitIo(activity);((SketchCanvasView)field(activity,"canvas")).selectObject("guide");invoke(activity,"referenceTools");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();firstCheckbox(dialog.getWindow().getDecorView()).setChecked(true);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();waitIo(activity);waitIo(activity);
            JSONObject stream;try(SketchProjectStore store=new SketchProjectStore(context)){stream=store.nextLiveSession(0);assertNotNull(stream);}
            JSONObject baseline=SketchLiveDrawing.read(context,id,stream.getString("sessionId")).getJSONObject("document"),shown=SketchDocument.object(baseline,"guide");
            for(int frame=0;frame<3;frame++)assertEquals("Show guide must override zero-opacity keys at frame "+frame,.35,SketchDocument.transformAt(shown,frame).getDouble("opacity"),.000001);
            assertEquals(motion(before),motion(shown));
        }
    }

    private JSONObject keyedGuide(String id,double base,double first,double last) throws Exception {
        JSONArray operations=new JSONArray().put(new JSONObject().put("type","set_timing").put("frameCount",3))
                .put(new JSONObject().put("type","set_exposure").put("objectId","guide").put("frame",0).put("exposure",3))
                .put(new JSONObject().put("type","set_transform").put("objectId","guide").put("transform",new JSONObject().put("opacity",base).put("scaleX",.6).put("scaleY",.6).put("x",.01).put("y",-.02).put("rotation",11)))
                .put(new JSONObject().put("type","set_keyframe").put("objectId","guide").put("frame",0).put("interpolation","hold").put("transform",new JSONObject().put("opacity",first).put("x",-.03).put("rotation",7)))
                .put(new JSONObject().put("type","set_keyframe").put("objectId","guide").put("frame",2).put("interpolation","linear").put("transform",new JSONObject().put("opacity",last).put("x",.04).put("rotation",-6)));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","keyed-guide").put("expectedRevision",2).put("operations",operations));try(SketchProjectStore store=new SketchProjectStore(context)){return SketchDocument.copy(SketchDocument.object(store.get(id),"guide"));}
    }
    private static String motion(JSONObject guide) throws Exception {JSONObject value=SketchDocument.copy(guide);value.getJSONObject("transform").remove("opacity");JSONArray keys=value.getJSONArray("keys");for(int n=0;n<keys.length();n++)keys.getJSONObject(n).getJSONObject("transform").remove("opacity");return SketchDocument.canonical(value);}

    private static Object field(Object instance,String name) throws Exception {Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
    private static void invoke(Object instance,String name) throws Exception {Method method=instance.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(instance);}
    /** A reference opacity edit can cross UI -> IO -> main -> session writer lanes.
     * A single awaitIdle before a posted callback has run is not a save barrier.
     * Verify the actual persisted document, never accept a merely queued request. */
    private void awaitGuideVisibilityCommit(SketchStudioActivity activity,String id)throws Exception {
        double committed=0;
        for(int attempt=0;attempt<10;attempt++){
            ShadowLooper.idleMainLooper();
            waitIo(activity);
            SketchEditorSession session=(SketchEditorSession)field(activity,"session");
            if(session!=null){
                session.awaitIdle(10000);
                if(session.error()!=null)throw new AssertionError("Reference edit save failed: "+session.error());
            }
            ShadowLooper.idleMainLooper();
            try(SketchProjectStore store=new SketchProjectStore(context)){
                JSONObject saved=store.get(id);
                JSONArray objects=saved.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
                assertEquals("Manual reference test expects one imported owned image",1,objects.length());
                JSONObject importedImage=objects.getJSONObject(0);
                assertEquals("image",importedImage.getString("kind"));
                committed=importedImage.getJSONObject("transform").getDouble("opacity");
                if(committed==1)return;
            }
        }
        assertEquals("Explicit guide visibility must be durably committed after UI and IO settle",1d,committed,0d);
    }
    private static void waitIo(SketchStudioActivity activity) throws Exception {((ExecutorService)field(activity,"io")).submit(()->{}).get(15,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static void choose(String text){AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView list=dialog.getListView();for(int n=0;n<list.getCount();n++)if(text.equals(String.valueOf(list.getAdapter().getItem(n)))){list.performItemClick(list.getAdapter().getView(n,null,list),n,n);return;}fail("Missing choice "+text);}
    private static EditText edit(View view,String hint){if(view instanceof EditText&&hint.contentEquals(((EditText)view).getHint()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){EditText result=edit(group.getChildAt(n),hint);if(result!=null)return result;}}return null;}
    private static android.widget.Button button(View view,String text){if(view instanceof android.widget.Button&&text.contentEquals(((android.widget.Button)view).getText()))return (android.widget.Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){android.widget.Button result=button(group.getChildAt(n),text);if(result!=null)return result;}}return null;}
    private static android.widget.CheckBox firstCheckbox(View view){if(view instanceof android.widget.CheckBox)return (android.widget.CheckBox)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){android.widget.CheckBox result=firstCheckbox(group.getChildAt(n));if(result!=null)return result;}}return null;}
}
