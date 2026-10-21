package com.rezoxnemesis.videostudio;
import android.app.AlertDialog;
import android.content.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchContourPlacementUiTest {
 private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 private static void waitIo(SketchStudioActivity a)throws Exception{((ExecutorService)field(a,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
 private static View find(View v,String label){if(label.equals(v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=find(((ViewGroup)v).getChildAt(i),label);if(r!=null)return r;}return null;}
 private JSONObject load(Context c,String id)throws Exception{try(SketchProjectStore s=new SketchProjectStore(c)){return s.get(id);}}
 private String fixture(Context c)throws Exception{ExecutorService e=Executors.newSingleThreadExecutor();try{return e.submit(()->{String id="fill_ui_"+java.util.UUID.randomUUID();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",1));JSONObject path=SketchDocument.object(SketchContourPaintTest.fixture(),"authored_outline");SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","path").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",path)).put(new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","existing"))));return id;}).get(30,TimeUnit.SECONDS);}finally{e.shutdownNow();}}
 private AlertDialog open(SketchStudioActivity a)throws Exception{waitIo(a);((SketchCanvasView)field(a,"canvas")).selectObject("authored_outline");Method m=SketchStudioActivity.class.getDeclaredMethod("fillContourDialog");m.setAccessible(true);m.invoke(a);waitIo(a);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());return dialog;}
 private void checkRequest(boolean checked)throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id=fixture(c);try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
   SketchStudioActivity a=controller.get();AlertDialog dialog=open(a);View root=dialog.getWindow().getDecorView();CheckBox placement=(CheckBox)find(root,"Place new cel under selected contour");assertNotNull("Native fill placement control must be visible",placement);assertTrue(placement.isChecked());placement.setChecked(checked);((EditText)find(root,"Contour fill color")).setText("#FFFFE8CC");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();((SketchEditorSession)field(a,"session")).awaitIdle(30000);waitIo(a);
   JSONObject d=load(c,id);assertEquals(3,d.getLong("revision"));JSONObject paint=null;JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=0;i<objects.length();i++)if(objects.getJSONObject(i).getString("id").startsWith("base_"))paint=objects.getJSONObject(i);assertNotNull(paint);
   try(SketchPaintStore store=new SketchPaintStore(c,id)){JSONObject request=store.journal(paint.getJSONObject("paint"),0,8).getJSONObject(0).getJSONObject("request");if(checked)assertEquals("under_contour",request.getString("placement"));else assertFalse(request.has("placement"));}
  }
 }
 @Test public void manualNewFillUsesTheSameOwnContourPlacementAsMcp()throws Exception{checkRequest(true);}
 @Test public void manualLayerBackChoiceKeepsTheOldRequestShape()throws Exception{checkRequest(false);}
 @Test public void existingCelFillKeepsItsCurrentOrder()throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id=fixture(c);try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
   SketchStudioActivity a=controller.get();AlertDialog dialog=open(a);View root=dialog.getWindow().getDecorView();((Spinner)find(root,"Contour paint target")).setSelection(1);ShadowLooper.idleMainLooper();((EditText)find(root,"Contour fill color")).setText("#FFFFE8CC");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();((SketchEditorSession)field(a,"session")).awaitIdle(30000);waitIo(a);JSONObject d=load(c,id);assertEquals(3,d.getLong("revision"));assertEquals("existing",d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(1).getString("id"));try(SketchPaintStore store=new SketchPaintStore(c,id)){assertEquals("preserve",store.journal(SketchDocument.object(d,"existing").getJSONObject("paint"),0,8).getJSONObject(0).getJSONObject("request").getString("placement"));}
  }
 }
 @Test public void choosingExistingCelDisablesNewCelPlacementAndCancelWritesNothing()throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id=fixture(c);String before=SketchDocument.canonical(load(c,id));try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
   SketchStudioActivity a=controller.get();AlertDialog d=open(a);View root=d.getWindow().getDecorView();CheckBox placement=(CheckBox)find(root,"Place new cel under selected contour");assertNotNull(placement);Spinner target=(Spinner)find(root,"Contour paint target");target.setSelection(1);ShadowLooper.idleMainLooper();assertFalse(placement.isEnabled());target.setSelection(0);ShadowLooper.idleMainLooper();assertTrue(placement.isEnabled());d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();waitIo(a);assertEquals(before,SketchDocument.canonical(load(c,id)));
  }
 }
}
