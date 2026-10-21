package com.rezoxnemesis.videostudio;
import android.content.*;
import android.widget.Spinner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchFittedBrushUiTest {
 private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 private static void waitIo(SketchStudioActivity a)throws Exception{((ExecutorService)field(a,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
 @Test @LooperMode(LooperMode.Mode.PAUSED) public void actualEditorToolAndWorkerSaveTheSamePressureBrushStroke()throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id="fitted_editor_"+java.util.UUID.randomUUID();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",512).put("height",512).put("frameCount",1));
  try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
   SketchStudioActivity a=controller.get();waitIo(a);Spinner tools=(Spinner)field(a,"toolPicker");assertEquals(16,tools.getCount());assertEquals("Precise pressure brush",tools.getItemAtPosition(15));tools.setSelection(15);ShadowLooper.idleMainLooper();SketchCanvasView canvas=(SketchCanvasView)field(a,"canvas");assertEquals("precision_brush",field(canvas,"tool"));canvas.setBrush("#FF302833",.012f,false);canvas.setBrushStyle(SketchBrushes.style("graphite",.7,.6,true,0),0);Field gesture=SketchCanvasView.class.getDeclaredField("gesture");gesture.setAccessible(true);gesture.set(canvas,new JSONArray("[[.2,.7,.1],[.4,.2,.8],[.8,.6,.2]]"));Method commit=SketchCanvasView.class.getDeclaredMethod("commitGesture");commit.setAccessible(true);commit.invoke(canvas);waitIo(a);((SketchEditorSession)field(a,"session")).awaitIdle(30000);waitIo(a);
   try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject d=store.get(id);assertEquals(2,d.getLong("revision"));JSONObject stroke=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0);assertEquals("stroke",stroke.getString("kind"));assertEquals("graphite",stroke.getJSONObject("brush").getString("preset"));assertTrue(stroke.getJSONArray("points").length()>3);}
  }
 }
 private JSONObject commit(String tool)throws Exception{
  SketchCanvasView canvas=new SketchCanvasView(RuntimeEnvironment.getApplication());JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","brush_ui").put("width",512).put("height",512).put("frameCount",1));canvas.setDocument(d);canvas.setBrush("#FF302833",.012f,false);canvas.setBrushStyle(SketchBrushes.style("graphite",.7,.6,true,0),0);canvas.setTool(tool);List<JSONObject> requests=new ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){requests.add(op);}public void onSelection(String id){}public void onMessage(String message){fail(message);}});
  Field gesture=SketchCanvasView.class.getDeclaredField("gesture");gesture.setAccessible(true);gesture.set(canvas,new JSONArray("[[.2,.7,.1],[.4,.2,.8],[.8,.6,.2]]"));Method commit=SketchCanvasView.class.getDeclaredMethod("commitGesture");commit.setAccessible(true);commit.invoke(canvas);assertEquals(1,requests.size());return requests.get(0);
 }
 @Test public void precisePressureToolEmitsTheOrdinaryNativeBrushRequest()throws Exception{
  JSONObject op=commit("precision_brush");assertEquals("precision_contour",op.getString("type"));assertEquals("graphite",op.getJSONObject("brush").getString("preset"));assertEquals(.7,op.getJSONObject("brush").getDouble("opacity"),0);assertFalse(op.optBoolean("pressureOutline"));assertFalse(op.optBoolean("closed"));assertEquals(.1,op.getJSONArray("points").getJSONArray(0).getDouble(2),0);
  JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","applied_ui").put("width",512).put("height",512).put("frameCount",1));SketchEditOps.apply(d,op);assertEquals("stroke",SketchDocument.object(d,op.getString("objectId")).getString("kind"));
 }
 @Test public void existingPrecisionPenKeepsItsPreviousOperationAndEditablePath()throws Exception{JSONObject op=commit("precision");assertEquals("precision_contour",op.getString("type"));assertFalse(op.has("brush"));assertTrue(op.getBoolean("pressureOutline"));}
}
