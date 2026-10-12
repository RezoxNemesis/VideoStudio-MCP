package com.rezoxnemesis.videostudio;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStudioInteractionTest {
    @Test public void humanCurveToolCreatesEditableBezierAndCanvasRemainsUsableOnPhoneLayout() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256));
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");View root=studio.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));root.layout(0,0,720,1440);
            assertTrue("Phone drawing surface should retain usable height",canvas.getHeight()>300);Bitmap ui=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);root.draw(new Canvas(ui));File target=new File("build/sketch-studio-qa");target.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(target,"workspace.png"))){ui.compress(Bitmap.CompressFormat.PNG,100,out);}ui.recycle();
            canvas.setTool("curve");int w=canvas.getWidth(),h=canvas.getHeight();float size=Math.min(w,h),left=(w-size)/2,top=(h-size)/2;for(float[] p:new float[][]{{.2f,.7f},{.5f,.1f},{.8f,.7f}}){long now=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,left+p[0]*size,top+p[1]*size,0),up=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_UP,left+p[0]*size,top+p[1]*size,0);canvas.onTouchEvent(down);canvas.onTouchEvent(up);down.recycle();up.recycle();}
            SketchEditorSession session=(SketchEditorSession)field(studio,"session");session.awaitIdle(10000);ShadowLooper.idleMainLooper();try(SketchProjectStore store=new SketchProjectStore(c)){JSONArray objects=SketchDocument.layer(store.get(id),"layer_1").getJSONArray("objects");assertEquals(1,objects.length());assertEquals("path",objects.getJSONObject(0).getString("kind"));assertEquals("Q",objects.getJSONObject(0).getJSONArray("commands").getJSONArray(1).getString(0));}
        }
    }
    @Test public void brushPresetEraserActuallyRemovesInkInsteadOfErasingAnEmptyOffscreenLayer() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",id).put("width",256).put("height",256).put("frameCount",1));
        JSONObject ink=SketchEditOps.completeObject(new JSONObject().put("id","ink").put("kind","rect").put("fill",true).put("bounds",new JSONObject().put("x",.1).put("y",.1).put("width",.8).put("height",.8)).put("color","#FF000000"));
        JSONObject eraser=SketchEditOps.completeObject(new JSONObject().put("id","erase").put("kind","eraser").put("width",.1).put("points",new JSONArray("[[0.2,0.5,1],[0.8,0.5,1]]")).put("brush",SketchBrushes.style("ink",1,.8,true,0)));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(ink).put(eraser);try(SketchFrameRenderer r=new SketchFrameRenderer(c,doc)){Bitmap b=r.render(0);try{assertEquals(Color.WHITE,b.getPixel(128,128));assertEquals(Color.BLACK,b.getPixel(128,80));}finally{b.recycle();}}
    }
    @Test public void strokeProgressRevealsOnlyTheSubmittedLeadingGeometry() throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString()).put("width",256).put("height",256).put("frameCount",1));JSONObject line=SketchEditOps.completeObject(new JSONObject().put("id","nose").put("kind","stroke").put("width",.04).put("points",new JSONArray("[[0.1,0.5,1],[0.9,0.5,1]]")));doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(line);
        try(SketchFrameRenderer r=new SketchFrameRenderer(c,doc)){r.setPresentation("nose",.25f);Bitmap partial=r.render(0);r.setPresentation(null,1);Bitmap full=r.render(0);try{assertNotEquals(Color.WHITE,partial.getPixel(51,128));assertEquals(Color.WHITE,partial.getPixel(205,128));assertNotEquals(Color.WHITE,full.getPixel(205,128));}finally{partial.recycle();full.recycle();}}
    }
    private static Object field(Object o,String name) throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void waitIo(SketchStudioActivity studio) throws Exception{((ExecutorService)field(studio,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
}
