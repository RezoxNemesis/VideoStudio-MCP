package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSymmetryTest {
    private JSONObject document(int width,int height)throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId","symmetry_geometry").put("width",width).put("height",height).put("frameCount",1));
    }
    private JSONObject operation(String mode)throws Exception {
        return new JSONObject().put("type","symmetry_stroke").put("layerId","layer_1")
                .put("object",new JSONObject().put("id","authored").put("kind","stroke").put("color","#FF4488AA")
                        .put("points",new JSONArray("[[0.55,0.5,0.2],[0.6,0.55,0.8]]")))
                .put("symmetry",new JSONObject().put("mode",mode));
    }
    private JSONArray points(JSONArray expanded,int index)throws Exception {return expanded.getJSONObject(index).getJSONObject("object").getJSONArray("points");}
    private void point(JSONArray points,int index,double x,double y,double pressure)throws Exception {
        JSONArray value=points.getJSONArray(index);assertEquals(x,value.getDouble(0),1e-12);assertEquals(y,value.getDouble(1),1e-12);assertEquals(pressure,value.getDouble(2),0);
    }
    private interface Checked {void run()throws Exception;}
    private void rejects(Checked check)throws Exception {try{check.run();fail("Invalid symmetry accepted");}catch(IllegalArgumentException expected){}}

    @Test public void mirrorsPreservePressureBrushAndSourceWithoutMutation()throws Exception {
        JSONObject op=operation("vertical");op.getJSONObject("object").put("brush",SketchBrushes.style("calligraphy",.4,.7,true,35));
        String before=SketchDocument.canonical(op);JSONArray expanded=SketchSymmetry.expand(document(512,256),op);
        assertEquals(2,expanded.length());point(points(expanded,1),0,.45,.5,.2);point(points(expanded,1),1,.4,.55,.8);
        assertEquals("authored",expanded.getJSONObject(0).getJSONObject("object").getString("id"));
        assertEquals("authored_sym1",expanded.getJSONObject(1).getJSONObject("object").getString("id"));
        assertEquals(SketchDocument.canonical(SketchDocument.copy(op.getJSONObject("object").getJSONObject("brush"))),SketchDocument.canonical(expanded.getJSONObject(1).getJSONObject("object").getJSONObject("brush")));
        assertEquals(before,SketchDocument.canonical(op));
        JSONArray horizontal=SketchSymmetry.expand(document(512,256),operation("horizontal"));point(points(horizontal,1),1,.6,.45,.8);
        JSONArray both=SketchSymmetry.expand(document(512,256),operation("both"));assertEquals(4,both.length());point(points(both,3),1,.4,.45,.8);
    }
    @Test public void radialAnglesUseCanvasPixelsOnRectangularDocuments()throws Exception {
        JSONObject op=operation("radial");op.getJSONObject("symmetry").put("segments",4);
        JSONArray expanded=SketchSymmetry.expand(document(512,256),op);assertEquals(4,expanded.length());
        point(points(expanded,1),0,.5,.6,.2);point(points(expanded,2),0,.45,.5,.2);point(points(expanded,3),0,.5,.4,.2);
        JSONObject shifted=operation("vertical");shifted.getJSONObject("symmetry").put("centerX",.4).put("centerY",.3);
        point(points(SketchSymmetry.expand(document(512,256),shifted),1),0,.25,.5,.2);
    }
    @Test public void centerAxisGeometryIsNotDoublePaintedAndIdsRemainStable()throws Exception {
        JSONObject op=operation("both");op.getJSONObject("object").put("points",new JSONArray("[[0.5,0.5,0.4]]"));
        assertEquals(1,SketchSymmetry.expand(document(256,256),op).length());
        op=operation("radial");op.getJSONObject("symmetry").put("segments",8);
        assertEquals(SketchDocument.canonical(SketchSymmetry.expand(document(512,256),op)),SketchDocument.canonical(SketchSymmetry.expand(document(512,256),op)));
    }
    @Test public void cardinalRotationsKeepExactEdgeCoordinatesAndPreservePressure()throws Exception {
        JSONObject op=operation("radial");op.getJSONObject("symmetry").put("segments",4);op.getJSONObject("object").put("points",new JSONArray("[[0,0,0],[1,0,1]]"));
        JSONArray expanded=SketchSymmetry.expand(document(256,256),op);assertEquals(4,expanded.length());
        point(points(expanded,1),0,1,0,0);point(points(expanded,1),1,1,1,1);point(points(expanded,2),0,1,1,0);point(points(expanded,3),0,0,1,0);
        op.getJSONObject("object").put("points",new JSONArray("[[0.5,0,0.5]]"));op.getJSONObject("symmetry").put("segments",8);
        assertEquals(8,SketchSymmetry.expand(document(256,256),op).length());
    }
    @Test public void outsideGeometryAndStrictConfigurationRejectBeforeMutation()throws Exception {
        JSONObject doc=document(512,256),outside=operation("vertical");outside.getJSONObject("symmetry").put("centerX",0);
        String before=SketchDocument.canonical(doc);rejects(()->SketchSymmetry.expand(doc,outside));assertEquals(before,SketchDocument.canonical(doc));
        for(JSONObject config:new JSONObject[]{new JSONObject().put("mode","radial"),new JSONObject().put("mode","radial").put("segments",9),new JSONObject().put("mode","radial").put("segments",2.5),new JSONObject().put("mode","vertical").put("segments",2),new JSONObject().put("mode","horizontal").put("centerX","0.5"),new JSONObject().put("mode","both").put("centerY",-0.1),new JSONObject().put("mode","vertical").put("trusted",true)}){
            JSONObject op=operation("vertical").put("symmetry",config);rejects(()->SketchSymmetry.expand(doc,op));
        }
        JSONObject invalid=operation("both");invalid.getJSONObject("object").put("reference",new JSONObject());rejects(()->SketchSymmetry.expand(doc,invalid));
    }
    @Test public void legacyVerticalAndConfiguredHumanGesturesUseSharedNativeOperation()throws Exception {
        Context context=RuntimeEnvironment.getApplication();SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);
        canvas.setDocument(document(512,256));canvas.layout(0,0,512,256);canvas.setBrushStyle(SketchBrushes.style("ink",1,1,true,0),0);canvas.setOnion(false);
        List<JSONObject> admitted=new ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){admitted.add(op);}public void onSelection(String id){}public void onMessage(String msg){fail(msg);}});
        Bitmap image=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888);
        try{
            canvas.setGuides(false,true);canvas.draw(new Canvas(image));touch(canvas,MotionEvent.ACTION_DOWN,280,128);touch(canvas,MotionEvent.ACTION_UP,290,140);
            assertEquals("symmetry_stroke",admitted.get(0).getString("type"));assertEquals("vertical",admitted.get(0).getJSONObject("symmetry").getString("mode"));
            canvas.setSymmetry(new JSONObject().put("mode","radial").put("segments",4).put("centerX",.5).put("centerY",.5));canvas.draw(new Canvas(image));touch(canvas,MotionEvent.ACTION_DOWN,280,128);canvas.draw(new Canvas(image));
            int rotated=image.getPixel(256,152);assertTrue("Radial touch preview must ink the rotated point over its pale guide",android.graphics.Color.red(rotated)<100&&android.graphics.Color.blue(rotated)<100);touch(canvas,MotionEvent.ACTION_UP,290,140);
            assertEquals(4,SketchSymmetry.expand(document(512,256),admitted.get(1)).length());
            assertEquals(4,admitted.get(1).getJSONObject("symmetry").getInt("segments"));
        }finally{image.recycle();canvas.dispose();}
    }
    @Test public void rejectedHumanGestureReportsOutsideGeometryWithoutPublishingAnOperation()throws Exception {
        Context context=RuntimeEnvironment.getApplication();SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);
        JSONObject doc=document(256,256);canvas.setDocument(doc);canvas.layout(0,0,256,256);canvas.setSymmetry(new JSONObject().put("mode","vertical").put("centerX",0));
        List<JSONObject> admitted=new ArrayList<>();List<String> messages=new ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){admitted.add(op);}public void onSelection(String id){}public void onMessage(String message){messages.add(message);}});
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        try{canvas.draw(new Canvas(image));touch(canvas,MotionEvent.ACTION_DOWN,120,120);canvas.draw(new Canvas(image));touch(canvas,MotionEvent.ACTION_UP,130,130);assertTrue(admitted.isEmpty());assertEquals(1,messages.size());assertTrue(messages.get(0).contains("outside the canvas"));assertEquals(0,SketchDocument.layer(doc,"layer_1").getJSONArray("objects").length());assertFalse(canvas.isDrawing());}
        finally{image.recycle();canvas.dispose();}
    }
    @Test public void humanChooserAppliesAndRetainsModeCenterSectorsAndCancelLeavesItUntouched()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=java.util.UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",256));
        android.content.Intent intent=new android.content.Intent(context,SketchStudioActivity.class).putExtra("sketchId",id);
        try(org.robolectric.android.controller.ActivityController<SketchStudioActivity> controller=org.robolectric.Robolectric.buildActivity(SketchStudioActivity.class,intent).create().start().resume().visible()){
            SketchStudioActivity studio=controller.get();java.lang.reflect.Method open=SketchStudioActivity.class.getDeclaredMethod("symmetryDialog");open.setAccessible(true);open.invoke(studio);
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();android.view.View root=dialog.getWindow().getDecorView();
            android.widget.Spinner mode=(android.widget.Spinner)description(root,"Symmetry mode");mode.setSelection(3);
            ((android.widget.EditText)description(root,"Center X 0..1")).setText("0.4");((android.widget.EditText)description(root,"Center Y 0..1")).setText("0.55");
            ((android.widget.EditText)description(root,"Radial sectors 2..8")).setText("6");findCheckbox(root).setChecked(true);
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(dialog.isShowing());
            JSONObject saved=new JSONObject(studio.getPreferences(Context.MODE_PRIVATE).getString("symmetrySettings","{}"));
            assertEquals("radial",saved.getString("mode"));assertEquals(6,saved.getInt("segments"));assertEquals(.4,saved.getDouble("centerX"),0);assertEquals(.55,saved.getDouble("centerY"),0);assertTrue(studio.getPreferences(Context.MODE_PRIVATE).getBoolean("symmetry",false));
            open.invoke(studio);dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();root=dialog.getWindow().getDecorView();
            assertEquals("radial",((android.widget.Spinner)description(root,"Symmetry mode")).getSelectedItem());
            ((android.widget.EditText)description(root,"Center X 0..1")).setText("0.9");dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            assertEquals(SketchDocument.canonical(saved),SketchDocument.canonical(new JSONObject(studio.getPreferences(Context.MODE_PRIVATE).getString("symmetrySettings","{}"))));
        }
        try(org.robolectric.android.controller.ActivityController<SketchStudioActivity> controller=org.robolectric.Robolectric.buildActivity(SketchStudioActivity.class,intent).create().start().resume().visible()){
            java.lang.reflect.Field field=SketchStudioActivity.class.getDeclaredField("symmetrySettings");field.setAccessible(true);JSONObject restored=(JSONObject)field.get(controller.get());
            assertEquals("radial",restored.getString("mode"));assertEquals(6,restored.getInt("segments"));assertEquals(.4,restored.getDouble("centerX"),0);
        }
    }
    private android.view.View description(android.view.View view,String value){
        if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View found=description(group.getChildAt(i),value);if(found!=null)return found;}}
        return null;
    }
    private android.widget.CheckBox findCheckbox(android.view.View view){
        if(view instanceof android.widget.CheckBox)return (android.widget.CheckBox)view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.widget.CheckBox found=findCheckbox(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private void touch(SketchCanvasView canvas,int action,float x,float y){MotionEvent event=MotionEvent.obtain(0,0,action,x,y,0);try{canvas.onTouchEvent(event);}finally{event.recycle();}}
}
