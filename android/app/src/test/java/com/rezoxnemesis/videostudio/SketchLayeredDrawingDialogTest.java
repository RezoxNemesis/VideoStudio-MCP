package com.rezoxnemesis.videostudio;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import org.robolectric.android.controller.ActivityController;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Executes the ordinary store and actual native preview behind human-facing controls. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchLayeredDrawingDialogTest {
    private Activity activity;private SketchLayeredDrawingDialog owner;private QueueWorker worker;
    private JSONObject document;private String id,sourceHash,drawLayer,drawTool;private int callbacks,failEdits;private boolean corruptPreview;
    private final List<JSONObject> requests=new ArrayList<>(),previews=new ArrayList<>();
    @After public void cleanup(){if(owner!=null)owner.close();if(activity!=null)activity.finish();}
    private void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();id="layered_ui_"+UUID.randomUUID().toString().replace("-","");
        SketchEditOps.execute(activity,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",320).put("height",320).put("frameCount",24));
        Bitmap source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);source.eraseColor(0xffe7d5b8);Canvas canvas=new Canvas(source);Paint paint=new Paint();paint.setColor(0xff445566);paint.setStrokeWidth(5);canvas.drawLine(14,50,32,12,paint);canvas.drawLine(32,12,50,50,paint);ByteArrayOutputStream encoded=new ByteArrayOutputStream();assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,encoded));source.recycle();sourceHash=SketchDocument.sha256(encoded.toByteArray());
        File references=new File(activity.getFilesDir(),"sketch_studio/"+id+"/references");assertTrue(references.mkdirs()||references.isDirectory());try(FileOutputStream out=new FileOutputStream(new File(references,sourceHash+".png"))){out.write(encoded.toByteArray());}
        JSONObject image=SketchEditOps.completeObject(new JSONObject().put("id","reference_image").put("kind","image").put("frame",0).put("exposure",24).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)).put("reference",new JSONObject().put("file",sourceHash+".png").put("sha256",sourceHash).put("width",64).put("height",64).put("sourceProjectId","fixture_project").put("sourceAssetId","fixture_asset").put("sourceSha256",sourceHash).put("sourceWidth",64).put("sourceHeight",64).put("sourceOrientation",1)));
        SketchEditOps.execute(activity,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","reference").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",image))));document=load();open();
    }
    private JSONObject load()throws Exception{try(SketchProjectStore store=new SketchProjectStore(activity)){return store.get(id);}}
    private void open()throws Exception{
        worker=new QueueWorker();owner=new SketchLayeredDrawingDialog(activity,document,3,loaded->{callbacks++;document=loaded;},(layer,tool)->{drawLayer=layer;drawTool=tool;},new SketchLayeredDrawingDialog.Backend(){
            public JSONObject edit(Context c,JSONObject request)throws Exception{assertNotSame("Store edits leave UI thread",Looper.getMainLooper(),Looper.myLooper());requests.add(SketchDocument.copy(request));if(failEdits-->0)throw new IllegalStateException("Injected durable edit unavailable");return SketchEditOps.execute(c,"sketch_edit",request);}
            public JSONObject load(Context c,String sketchId)throws Exception{assertNotSame(Looper.getMainLooper(),Looper.myLooper());return SketchLayeredDrawingDialogTest.this.load();}
            public JSONObject preview(Context c,JSONObject doc,JSONObject request)throws Exception{assertNotSame("Raster preparation leaves UI thread",Looper.getMainLooper(),Looper.myLooper());previews.add(SketchDocument.copy(request));JSONObject result=SketchLayeredDrawingPreview.inspect(c,doc,request);if(corruptPreview)result.getJSONObject("sketchPreviewImage").put("sha256",String.join("",Collections.nCopies(64,"0")));return result;}
        },worker);owner.open();idle();
    }
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private View root()throws Exception{Field field=owner.getClass().getDeclaredField("form");field.setAccessible(true);return (View)field.get(owner);}
    private View find(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())||view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private View view(String label)throws Exception{View found=find(root(),label);assertNotNull(label,found);return found;}
    private void click(String label)throws Exception{assertTrue("Enabled: "+label,view(label).isEnabled());view(label).performClick();idle();}
    private void text(String label,String value)throws Exception{((EditText)view(label)).setText(value);idle();}
    private String status()throws Exception{return ((TextView)view("Layered drawing status")).getText().toString();}
    private void work()throws Exception{worker.runNext();idle();}
    private void begin()throws Exception{text("Clean part names · one per line","Front figure\nRear figure\nHands and hair");click("Begin layered drawing");work();}
    private void drawNativeStroke()throws Exception{
        owner.close();String layer=SketchLayeredDrawing.activeLayerIds(document).getString(0);JSONObject stroke=SketchEditOps.completeObject(new JSONObject().put("id","authored_stroke").put("kind","stroke").put("frame",3).put("exposure",21).put("points",new JSONArray("[[0.15,0.8,1],[0.25,0.25,1],[0.5,0.12,1],[0.75,0.25,1],[0.85,0.8,1]]")).put("width",.012).put("color","#FF161C2B"));
        JSONObject request=new JSONObject().put("sketchId",id).put("operationId","author_native_stroke").put("expectedRevision",document.getLong("revision")).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId",layer).put("object",stroke)));ExecutorService writer=Executors.newSingleThreadExecutor();try{writer.submit(()->{try{return SketchEditOps.execute(activity,"sketch_edit",request);}catch(Exception error){throw new RuntimeException(error);}}).get(20,TimeUnit.SECONDS);}finally{writer.shutdownNow();}document=load();open();
    }
    @Test public void stageOnlyPreviewIsReadOnlyStrictAndShowsOnlyCurrentNativeInk()throws Exception{
        setup();begin();drawNativeStroke();JSONObject snapshot=SketchDocument.copy(document);
        SketchEditOps.apply(snapshot,new JSONObject().put("type","layered_drawing_review").put("stage","construction").put("reviewedRevision",snapshot.getLong("revision")).put("frameIndex",3).put("status","ACCEPTABLE").put("note","Reviewed construction for preview isolation test."));
        SketchEditOps.apply(snapshot,new JSONObject().put("type","layered_drawing_stage").put("stage","refinement"));
        JSONObject mark=SketchLayeredDrawingTest.stroke(SketchLayeredDrawing.activeLayerIds(snapshot).getString(0),"red_refinement");mark.getJSONObject("object").put("color","#FFCC2222").put("width",.02).put("frame",3).put("exposure",21);SketchEditOps.apply(snapshot,mark);
        String before=SketchDocument.canonical(snapshot);ExecutorService nativeWorker=Executors.newSingleThreadExecutor();try{nativeWorker.submit(()->{try{
            JSONObject request=new JSONObject().put("view","artwork").put("frameIndex",3).put("maxDimension",320).put("stageOnly",true);
            JSONObject isolated=SketchLayeredDrawingPreview.inspect(activity,snapshot,request);assertTrue(isolated.getBoolean("stageOnly"));
            Bitmap pixels=SketchLayeredDrawingDialog.decodePreview(isolated.getJSONObject("sketchPreviewImage"),320);boolean oldInk=false,newInk=false;try{for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++){int c=pixels.getPixel(x,y);oldInk|=c==0xff161c2b;newInk|=c==0xffcc2222;}}finally{pixels.recycle();}assertFalse("Earlier construction must be excluded",oldInk);assertTrue("Current authored ink remains",newInk);
            for(Object invalid:new Object[]{"true",1,JSONObject.NULL}){request.put("stageOnly",invalid);assertThrows(IllegalArgumentException.class,()->SketchLayeredDrawingPreview.inspect(activity,snapshot,request));}
            request.put("stageOnly",true).put("view","reference");assertThrows(IllegalArgumentException.class,()->SketchLayeredDrawingPreview.inspect(activity,snapshot,request));
            return null;
        }catch(Exception failure){throw new RuntimeException(failure);}}).get(20,TimeUnit.SECONDS);}finally{nativeWorker.shutdownNow();}assertEquals(before,SketchDocument.canonical(snapshot));
    }
    @Test public void manualStageToggleInvalidatesComparedAcceptance()throws Exception{
        setup();begin();drawNativeStroke();click("Compare reference and artwork");work();assertTrue(view("Accept reviewed stage").isEnabled());
        ((CheckBox)view("Show current stage only")).setChecked(true);idle();assertFalse(view("Accept reviewed stage").isEnabled());click("Compare reference and artwork");work();assertTrue(previews.get(previews.size()-1).getBoolean("stageOnly"));assertTrue(view("Accept reviewed stage").isEnabled());
        ((CheckBox)view("Show current stage only")).setChecked(false);idle();assertFalse(view("Accept reviewed stage").isEnabled());
    }
    @Test public void beginUsesOrdinaryPersistenceAndRetryKeepsExactRequestThenNativeLayerToolIsChosen()throws Exception{
        setup();String initial=SketchDocument.canonical(document);text("Clean part names · one per line","Front figure\nRear figure");failEdits=1;click("Begin layered drawing");assertEquals(initial,SketchDocument.canonical(load()));work();assertTrue(status(),status().contains("Injected"));assertEquals(0,callbacks);click("Begin layered drawing");work();assertEquals(requests.get(0).toString(),requests.get(1).toString());assertEquals(1,callbacks);
        JSONObject summary=SketchLayeredDrawing.summary(document);assertTrue(summary.getBoolean("enabled"));assertEquals("construction",summary.getString("stage"));assertEquals(2,summary.getJSONArray("parts").length());JSONObject guide=SketchDocument.layer(document,summary.getString("referenceLayerId"));assertTrue(guide.getBoolean("locked"));assertFalse(guide.getBoolean("visible"));assertEquals(sourceHash,SketchDocument.object(document,"reference_image").getJSONObject("reference").getString("sha256"));
        for(int i=0;i<document.getJSONArray("layers").length();i++){JSONObject layer=document.getJSONArray("layers").getJSONObject(i);if(!layer.getString("id").equals(guide.getString("id")))assertEquals("Begin does not copy reference pixels into authored layers",0,layer.getJSONArray("objects").length());}
        ((Spinner)view("Layer drawing tool")).setSelection(1);idle();String expected=SketchLayeredDrawing.activeLayerIds(document).getString(0);click("Draw selected layer");assertEquals(expected,drawLayer);assertEquals("precision",drawTool);assertEquals(3,load().getLong("revision"));
    }
    @Test public void actualComparisonAndTypedReviewAreBoundToCurrentFrameRevisionAndStageThenNormalUndoRestoresPass()throws Exception{
        setup();begin();drawNativeStroke();assertFalse(view("Accept reviewed stage").isEnabled());click("View reference only");work();assertFalse("Reference alone cannot unlock artistic acceptance",view("Accept reviewed stage").isEnabled());click("Compare reference and artwork");work();assertTrue(view("Accept reviewed stage").isEnabled());assertEquals(3,previews.get(previews.size()-1).getInt("frameIndex"));click("Accept reviewed stage");assertTrue("Blank note publishes no operation",worker.jobs.isEmpty());assertTrue(status(),status().contains("review note"));
        text("Review note · what works or needs correction","Construction fixture inspected; broad placement is acceptable for this technical test.");click("Compare reference and artwork");work();assertTrue("Capture follows a successful current comparison",status().startsWith("Native comparison displayed."));long inspected=document.getLong("revision");capture("native-layered-comparison.png");click("Accept reviewed stage");work();JSONObject review=requests.get(requests.size()-1).getJSONArray("operations").getJSONObject(0);assertEquals("layered_drawing_review",review.getString("type"));assertEquals(3,review.getInt("frameIndex"));assertEquals(inspected,review.getLong("reviewedRevision"));assertFalse("A saved review requires a fresh current-revision comparison for another decision",view("Accept reviewed stage").isEnabled());
        click("2 · Refinement");work();assertEquals("refinement",SketchLayeredDrawing.summary(document).getString("stage"));SketchEditOps.execute(activity,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","normal_undo").put("expectedRevision",document.getLong("revision")));assertEquals("construction",SketchLayeredDrawing.summary(load()).getString("stage"));assertEquals(sourceHash,SketchDocument.object(load(),"reference_image").getJSONObject("reference").getString("sha256"));
    }
    @Test public void corruptPreviewDoesNotUnlockReviewAndClosingDiscardsLateRasterWithoutAnotherSave()throws Exception{
        setup();begin();corruptPreview=true;click("Compare reference and artwork");work();assertFalse(view("Accept reviewed stage").isEnabled());assertTrue(status(),status().contains("bytes changed"));corruptPreview=false;click("Compare reference and artwork");Runnable preview=worker.jobs.poll();int saves=callbacks;owner.close();Thread thread=new Thread(preview,"late-layered-preview");thread.start();thread.join(10000);assertFalse(thread.isAlive());idle();assertEquals(saves,callbacks);Field image=owner.getClass().getDeclaredField("previewBitmap");image.setAccessible(true);assertNull(image.get(owner));assertEquals(3,load().getLong("revision"));
    }
    @Test public void studioEntryDrawsActualTouchPenOnStageLayerAndShowsSeparateReferenceWithNormalUndo()throws Exception{
        setup();begin();owner.close();
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(activity,SketchStudioActivity.class).putExtra("sketchId",id))){
            SketchStudioActivity studio=controller.get();studio.getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).remove("lastBookId").commit();controller.create().start().resume().visible();settleStudio(studio);
            Method menu=studio.getClass().getDeclaredMethod("moreMenu");menu.setAccessible(true);menu.invoke(studio);AlertDialog choices=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();boolean found=false;for(int i=0;i<choices.getListView().getCount();i++)found|="Layer-by-layer drawing".equals(choices.getListView().getAdapter().getItem(i));assertTrue(found);choices.dismiss();
            Method launch=studio.getClass().getDeclaredMethod("layeredDrawingDialog");launch.setAccessible(true);launch.invoke(studio);settleStudio(studio);owner=(SketchLayeredDrawingDialog)field(studio,"layeredDrawingDialog");assertNotNull(owner);click("Draw selected layer");settleStudio(studio);
            SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");String active=SketchLayeredDrawing.activeLayerIds(load()).getString(0);assertEquals(active,canvas.getLayerId());assertEquals("stroke",field(canvas,"tool"));assertEquals(View.VISIBLE,((View)field(studio,"layeredReferencePanel")).getVisibility());assertNotNull("Actual owned reference loaded in separate panel",((ImageView)field(studio,"layeredReferenceImage")).getDrawable());
            canvas.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY));canvas.layout(0,0,320,320);Bitmap rendered=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(rendered));rendered.recycle();
            long clock=1;for(int i=0;i<3;i++){MotionEvent event=MotionEvent.obtain(clock,clock+i,i==0?MotionEvent.ACTION_DOWN:i==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,64+i*80,80+i*50,0);assertTrue(canvas.onTouchEvent(event));event.recycle();}idle();SketchEditorSession session=(SketchEditorSession)field(studio,"session");session.awaitIdle(20000);idle();JSONObject persisted=load();JSONArray marks=SketchDocument.layer(persisted,active).getJSONArray("objects");assertEquals(1,marks.length());assertEquals("stroke",marks.getJSONObject(0).getString("kind"));assertFalse(marks.getJSONObject(0).has("reference"));assertEquals(sourceHash,SketchDocument.object(persisted,"reference_image").getJSONObject("reference").getString("sha256"));captureStudio(studio);
            Method undo=studio.getClass().getDeclaredMethod("history",boolean.class);undo.setAccessible(true);undo.invoke(studio,false);settleStudio(studio);assertEquals(0,SketchDocument.layer(load(),active).getJSONArray("objects").length());assertTrue(SketchLayeredDrawing.summary(load()).getBoolean("enabled"));

            // Re-enter through the actual manual tool chooser. Precision fitting must
            // reach the prepared native worker as an unfilled editable pen path.
            launch.invoke(studio);settleStudio(studio);owner=(SketchLayeredDrawingDialog)field(studio,"layeredDrawingDialog");((Spinner)view("Layer drawing tool")).setSelection(1);idle();click("Draw selected layer");settleStudio(studio);
            assertEquals("precision",field(canvas,"tool"));assertEquals(active,canvas.getLayerId());
            canvas.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY));canvas.layout(0,0,320,320);Bitmap preciseViewport=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(preciseViewport));preciseViewport.recycle();
            for(int i=0;i<=12;i++){float x=48+i*(224f/12),y=160+(float)Math.sin(i*Math.PI/12)*52;MotionEvent event=MotionEvent.obtain(20,20+i,i==0?MotionEvent.ACTION_DOWN:i==12?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,x,y,0);assertTrue(canvas.onTouchEvent(event));event.recycle();}
            idle();SketchEditorSession preciseSession=(SketchEditorSession)field(studio,"session");preciseSession.awaitIdle(20000);settleStudio(studio);assertNull("Native precision must not leave a rejected local intent",preciseSession.error());
            JSONObject precise=load();JSONArray paths=SketchDocument.layer(precise,active).getJSONArray("objects");assertEquals("One touch gesture creates one native authored path",1,paths.length());JSONObject path=paths.getJSONObject(0);assertEquals("path",path.getString("kind"));assertFalse("Layered precision pen must remain an unfilled contour",path.getBoolean("fill"));assertFalse(path.has("reference"));assertFalse(path.has("image"));JSONArray commands=path.getJSONArray("commands");assertEquals("M",commands.getJSONArray(0).getString(0));boolean cubic=false;for(int i=1;i<commands.length();i++){String command=commands.getJSONArray(i).getString(0);cubic|="C".equals(command);assertNotEquals("Open precision pen must not become a filled silhouette","Z",command);}assertTrue("Measured native fitting retains editable cubic geometry",cubic);
            JSONObject guide=SketchDocument.layer(precise,precise.getJSONObject("layeredDrawing").getString("referenceLayerId"));assertTrue(guide.getBoolean("locked"));assertFalse(guide.getBoolean("visible"));assertEquals(sourceHash,SketchDocument.object(precise,"reference_image").getJSONObject("reference").getString("sha256"));
            ExecutorService nativePreview=Executors.newSingleThreadExecutor();try{boolean[] pixels=nativePreview.submit(()->{JSONObject reply=SketchLayeredDrawingPreview.inspect(activity,precise,new JSONObject().put("view","artwork").put("frameIndex",path.getInt("frame")).put("maxDimension",320));Bitmap image=SketchLayeredDrawingDialog.decodePreview(reply.getJSONObject("sketchPreviewImage"),320);try{boolean ink=false,guidePigment=false;int[] row=new int[image.getWidth()];for(int y=0;y<image.getHeight();y++){image.getPixels(row,0,row.length,0,y,row.length,1);for(int pixel:row){ink|=pixel==0xff161c2b;guidePigment|=pixel==0xffe7d5b8;}}return new boolean[]{ink,guidePigment};}finally{image.recycle();}}).get(20,TimeUnit.SECONDS);assertTrue("Actual authored preview contains the fitted pen's pigment",pixels[0]);assertFalse("Reference background pixels stay out of the authored preview",pixels[1]);}finally{nativePreview.shutdownNow();}
            undo.invoke(studio,false);settleStudio(studio);assertEquals("Normal Undo removes the precision gesture",0,SketchDocument.layer(load(),active).getJSONArray("objects").length());assertEquals(sourceHash,SketchDocument.object(load(),"reference_image").getJSONObject("reference").getString("sha256"));assertTrue(SketchLayeredDrawing.summary(load()).getBoolean("enabled"));
        }
    }
    private static Object field(Object object,String name)throws Exception{Field value=object.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(object);}
    private static void settleStudio(SketchStudioActivity studio)throws Exception{for(int i=0;i<5;i++){((ExecutorService)field(studio,"io")).submit(()->{}).get(20,TimeUnit.SECONDS);((ExecutorService)field(studio,"transfers")).submit(()->{}).get(20,TimeUnit.SECONDS);idle();}}
    private static void captureStudio(SketchStudioActivity studio)throws Exception{
        View decor=studio.getWindow().getDecorView();SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");ExecutorService raster=(ExecutorService)field(canvas,"rasterWorker");
        decor.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY));decor.layout(0,0,720,1280);idle();
        Bitmap pixels=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888);
        try{
            // Layout changes the raster viewport. Drain each actual native render,
            // deliver its UI publication, then draw again before reading evidence.
            for(int cycle=0;cycle<8;cycle++){
                decor.draw(new Canvas(pixels));raster.submit(()->{}).get(20,TimeUnit.SECONDS);idle();decor.draw(new Canvas(pixels));
                JSONObject shown=(JSONObject)field(canvas,"document");if(canvas.previewStatus().startsWith("Preview ready")&&((Long)field(canvas,"lastRasterRevision"))==shown.getLong("revision")&&(Integer)field(canvas,"rasterInFlight")==-1)break;
            }
            assertFalse("Native evidence cannot use a failed preview",canvas.previewFailed());assertTrue("Native evidence waits for published artwork: "+canvas.previewStatus(),canvas.previewStatus().startsWith("Preview ready"));
            assertEquals("Native evidence shows the current saved revision",((JSONObject)field(canvas,"document")).getLong("revision"),((Long)field(canvas,"lastRasterRevision")).longValue());
            int[] origin=new int[2],window=new int[2];canvas.getLocationInWindow(origin);decor.getLocationInWindow(window);int left=Math.max(0,origin[0]-window[0]),top=Math.max(0,origin[1]-window[1]),right=Math.min(pixels.getWidth(),left+canvas.getWidth()),bottom=Math.min(pixels.getHeight(),top+canvas.getHeight());boolean pigment=false;for(int y=top;y<bottom&&!pigment;y++)for(int x=left;x<right;x++)if(pixels.getPixel(x,y)==0xff161c2b){pigment=true;break;}assertTrue("Captured canvas must contain actual authored pen pigment",pigment);
            String output=System.getProperty("layered.drawing.ui.images");if(output==null||output.isEmpty())output=System.getenv("VIDEOSTUDIO_LAYERED_DRAWING_EVIDENCE");if(output==null||output.isEmpty())return;File directory=new File(output);assertTrue(directory.mkdirs()||directory.isDirectory());try(FileOutputStream out=new FileOutputStream(new File(directory,"native-studio-authored-pen-separate-reference.png"))){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,out));}
        }finally{pixels.recycle();}
    }
    private void capture(String filename)throws Exception{
        String output=System.getProperty("layered.drawing.ui.images");if(output==null||output.isEmpty())output=System.getenv("VIDEOSTUDIO_LAYERED_DRAWING_EVIDENCE");if(output==null||output.isEmpty())return;File directory=new File(output);assertTrue(directory.mkdirs()||directory.isDirectory());Field field=owner.getClass().getDeclaredField("dialog");field.setAccessible(true);AlertDialog actual=(AlertDialog)field.get(owner);View decor=actual.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY));decor.layout(0,0,720,1280);idle();ScrollView scroll=(ScrollView)root().getParent();scroll.fullScroll(View.FOCUS_DOWN);idle();Bitmap image=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888);try{decor.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(directory,filename))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{image.recycle();}
        JSONObject label=new JSONObject().put("execution","Actual Android AlertDialog and Canvas under Robolectric native graphics").put("phoneScreenshot",false).put("source","App-private synthetic PNG technical reference; authored native stroke is distinct").put("artisticAcceptance",false).put("frameIndex",3).put("revision",document.getLong("revision"));try(FileOutputStream out=new FileOutputStream(new File(directory,"Evidence.json"))){out.write(label.toString(2).getBytes(StandardCharsets.UTF_8));}
    }
    private static final class QueueWorker extends AbstractExecutorService{final Queue<Runnable> jobs=new ArrayDeque<>();boolean stopped;public void execute(Runnable job){if(stopped)throw new RejectedExecutionException();jobs.add(job);}public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;List<Runnable> result=new ArrayList<>(jobs);jobs.clear();return result;}public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped&&jobs.isEmpty();}public boolean awaitTermination(long t,TimeUnit unit){return isTerminated();}void runNext()throws Exception{Runnable task=jobs.poll();assertNotNull("Queued worker expected",task);Thread thread=new Thread(task,"layered-dialog-test-worker");thread.start();thread.join(20000);assertFalse("Worker finished",thread.isAlive());}}
}
