package com.rezoxnemesis.videostudio;
import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchPaintMeshDialogTest {
 Activity activity;SketchPaintMeshDialog owner;QueueWorker queue;String id,sourceSha;JSONObject document;int callbacks;
 @After public void close(){if(owner!=null)owner.close();if(activity!=null)activity.finish();}
 void setup()throws Exception {
  activity=Robolectric.buildActivity(Activity.class).setup().get();id="mesh_ui_"+UUID.randomUUID();ExecutorService worker=Executors.newSingleThreadExecutor();try{document=worker.submit(()->{try{SketchEditOps.execute(activity,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",320).put("height",320).put("frameCount",24));SketchPaintMeshTest.edit(activity,id,1,"paint",new JSONArray().put(new JSONObject().put("type","add_paint_cel").put("objectId","paint").put("layerId","layer_1").put("exposure",24)));SketchPaintMeshTest.edit(activity,id,2,"stroke",new JSONArray().put(new JSONObject().put("type","paint_stroke").put("objectId","paint").put("strokeId","authored_mark").put("points",new JSONArray("[[.5,.5,1]]")).put("brush",new JSONObject().put("mode","paint").put("color","#FFFFFFFF").put("sizePx",40))));return SketchPaintMeshTest.load(activity,id);}catch(Exception failure){throw new RuntimeException(failure);}}).get(30,TimeUnit.SECONDS);}finally{worker.shutdownNow();}sourceSha=SketchDocument.object(document,"paint").getJSONObject("paint").getString("sha256");
  queue=new QueueWorker();owner=new SketchPaintMeshDialog(activity,document,"paint",3,receipt->callbacks++,new SketchPaintMeshDialog.Backend(){public JSONObject prepare(Context c,JSONObject args)throws Exception{assertNotSame(Looper.getMainLooper(),Looper.myLooper());return SketchPaintMeshPrepare.prepare(c,args);}public Bitmap load(Context c,JSONObject d,JSONObject item,JSONObject patch)throws Exception{assertNotSame(Looper.getMainLooper(),Looper.myLooper());return SketchPaintMeshPrepare.texture(c,d,item);}public JSONObject edit(Context c,JSONObject args)throws Exception{assertNotSame(Looper.getMainLooper(),Looper.myLooper());return SketchEditOps.execute(c,"sketch_edit",args);}},queue);owner.open();idle();
 }
 static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
 View root()throws Exception{java.lang.reflect.Field field=owner.getClass().getDeclaredField("form");field.setAccessible(true);return (View)field.get(owner);}
 View find(View v,String name){if(name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||v instanceof Button&&name.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View result=find(((ViewGroup)v).getChildAt(i),name);if(result!=null)return result;}return null;}
 View view(String name)throws Exception{View result=find(root(),name);assertNotNull(name,result);return result;}
 void click(String name)throws Exception{assertTrue(name,view(name).isEnabled());view(name).performClick();idle();}
 void text(String name,String value)throws Exception{((EditText)view(name)).setText(value);}
 void work()throws Exception{queue.runNext();idle();}
 @Test public void manualPreparationBackdropsNumericVertexKeysNormalSaveAndUndo()throws Exception {
  setup();click("Prepare grid");work();assertTrue(view("Apply grid").isEnabled());SketchPaintMeshViewport preview=(SketchPaintMeshViewport)view("Paint mesh viewport");preview.layout(0,0,320,320);((CheckBox)view("Show paint grid")).setChecked(false);Spinner backdrop=(Spinner)view("Mesh preview background");Bitmap screen=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);try{backdrop.setSelection(1);idle();preview.layout(0,0,320,320);preview.draw(new Canvas(screen));assertEquals(0xff20232b,screen.getPixel(5,5));backdrop.setSelection(2);idle();preview.layout(0,0,320,320);preview.draw(new Canvas(screen));assertEquals(0xfff5f5f5,screen.getPixel(5,5));assertEquals(Color.WHITE,screen.getPixel(160,160));}finally{screen.recycle();}
  text("Vertex local X (-4..4)",".05");text("Vertex local Y (-4..4)",".02");click("Update vertex");((CheckBox)view("Edit mesh key pose")).setChecked(true);idle();text("Vertex local X (-4..4)",".09");click("Update vertex");assertFalse(view("Apply grid").isEnabled());click("Set mesh key");assertTrue(view("Apply grid").isEnabled());click("Apply grid");work();assertEquals(1,callbacks);JSONObject stored=SketchPaintMeshTest.load(activity,id),paint=SketchDocument.object(stored,"paint");assertEquals(sourceSha,paint.getJSONObject("paint").getString("sha256"));assertEquals(3,paint.getJSONObject("paintMesh").getJSONArray("keys").getJSONObject(0).getInt("frame"));assertEquals(.09,SketchPaintMesh.pointsAt(paint,3).getJSONArray(0).getDouble(0),0);assertEquals(.05,SketchPaintMesh.pointsAt(paint,0).getJSONArray(0).getDouble(0),0);
  SketchEditOps.execute(activity,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","undo"));assertFalse(SketchDocument.object(SketchPaintMeshTest.load(activity,id),"paint").has("paintMesh"));
 }
 @Test public void focusedManualPreparationFitsThePartInvalidatesStaleDraftAndSavesTheSameNativePatch()throws Exception {
  setup();CheckBox focus=(CheckBox)view("Focus grid on retained paint");focus.setChecked(true);idle();click("Prepare grid");work();assertTrue(view("Apply grid").isEnabled());
  SketchPaintMeshViewport preview=(SketchPaintMeshViewport)view("Paint mesh viewport");preview.layout(0,0,320,320);((CheckBox)view("Show paint grid")).setChecked(false);click("Fit grid");Spinner backdrop=(Spinner)view("Mesh preview background");backdrop.setSelection(1);idle();Bitmap screen=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);try{preview.draw(new Canvas(screen));assertEquals(Color.WHITE,screen.getPixel(160,160));assertEquals(0xff20232b,screen.getPixel(5,5));}finally{screen.recycle();}
  focus.setChecked(false);idle();assertFalse(view("Apply grid").isEnabled());focus.setChecked(true);idle();click("Prepare grid");work();click("Apply grid");work();JSONObject stored=SketchPaintMeshTest.load(activity,id),paint=SketchDocument.object(stored,"paint");assertEquals(sourceSha,paint.getJSONObject("paint").getString("sha256"));assertTrue(paint.getJSONObject("paintMesh").has("sourceRect"));assertTrue(SketchPaintMesh.regularAt(paint,0));
 }
 static final class QueueWorker extends AbstractExecutorService {
  final Queue<Runnable> pending=new ArrayDeque<>();boolean stopped;public void execute(Runnable task){if(stopped)throw new RejectedExecutionException();pending.add(task);}void runNext()throws Exception{Runnable task=pending.remove();ExecutorService thread=Executors.newSingleThreadExecutor();try{thread.submit(task).get(30,TimeUnit.SECONDS);}finally{thread.shutdownNow();}}public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;List<Runnable> result=new ArrayList<>(pending);pending.clear();return result;}public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped&&pending.isEmpty();}public boolean awaitTermination(long t,TimeUnit u){return isTerminated();}
 }
}
