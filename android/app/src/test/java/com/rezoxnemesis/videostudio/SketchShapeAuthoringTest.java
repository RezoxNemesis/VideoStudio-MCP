package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchShapeAuthoringTest {
    private final Context context=RuntimeEnvironment.getApplication();
    @Test public void nativeShapeDialogCreatesACurveKeyAndRetainsItsControlsWhenReopened()throws Exception {
        JSONObject d=SketchShapeAnimationTest.document();String id="shape_touch_"+java.util.UUID.randomUUID();d.put("id",id);
        try(SketchProjectStore store=new SketchProjectStore(context)){store.importOwnedDocument(d,"create");}
        Intent intent=new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id);
        try(ActivityController<SketchStudioActivity> c=Robolectric.buildActivity(SketchStudioActivity.class,intent)){
            c.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).remove("lastBookId").commit();
            c.create().start().resume().visible();settle(c.get());((SketchCanvasView)field(c.get(),"canvas")).selectObject("subject");
            invoke(c.get(),"shapeAnimationDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            ((Spinner)find(dialog.getWindow().getDecorView(),"Shape timing")).setSelection(6);ShadowLooper.idleMainLooper();
            ((EditText)find(dialog.getWindow().getDecorView(),"Bezier timing: x1, y1, x2, y2")).setText("0.42, 0, 1, 1");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(c.get());
            try(SketchProjectStore store=new SketchProjectStore(context)){
                JSONObject key=SketchDocument.object(store.get(id),"subject").getJSONArray("shapeKeys").getJSONObject(0);
                assertEquals("bezier",key.getString("interpolation"));assertEquals(.42,key.getJSONArray("curve").getDouble(0),0);
            }
            invoke(c.get(),"shapeAnimationDialog");dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(6,((Spinner)find(dialog.getWindow().getDecorView(),"Shape timing")).getSelectedItemPosition());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();settle(c.get());
            try(SketchProjectStore store=new SketchProjectStore(context)){assertFalse(SketchShapeKeys.animated(SketchDocument.object(store.get(id),"subject")));}
        }
    }
    @Test public void draggingASampledCurveNodeCreatesAShapeKeyWithoutChangingItsRestGeometry()throws Exception {
        JSONObject d=SketchShapeAnimationTest.animated();String before=SketchDocument.canonical(d);List<JSONObject> operations=new ArrayList<>();
        SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);
        canvas.setDocument(d);canvas.layout(0,0,256,256);canvas.setFrame(30);canvas.setTool("nodes");canvas.selectObject("subject");
        canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject o){operations.add(o);}public void onSelection(String id){}public void onMessage(String m){fail(m);}});
        Bitmap b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        try{
            canvas.draw(new Canvas(b));float x=(float)(256*(.1+.6*.3153568125)),y=256*.3f;
            event(canvas,MotionEvent.ACTION_DOWN,x,y);event(canvas,MotionEvent.ACTION_MOVE,100,100);event(canvas,MotionEvent.ACTION_UP,100,100);
            assertEquals(1,operations.size());JSONObject op=operations.get(0);
            assertEquals("set_shape_key",op.getString("type"));assertEquals(30,op.getInt("frame"));assertEquals("bezier",op.getString("interpolation"));
            assertEquals(100/256d,op.getJSONArray("commands").getJSONArray(0).getDouble(1),.00001);
            assertEquals(before,SketchDocument.canonical(d));
            JSONObject changed=SketchDocument.copy(d);SketchEditOps.apply(changed,op);
            assertEquals(.1,SketchDocument.object(changed,"subject").getJSONArray("commands").getJSONArray(0).getDouble(1),0);
        }finally{b.recycle();canvas.dispose();}
    }
    private static void event(SketchCanvasView canvas,int action,float x,float y){MotionEvent e=MotionEvent.obtain(1,2+action,action,x,y,0);canvas.onTouchEvent(e);e.recycle();}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void invoke(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}
    private static void settle(Object o)throws Exception{for(int i=0;i<4;i++){((ExecutorService)field(o,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    private static View find(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),label);if(found!=null)return found;}}return null;}
}
