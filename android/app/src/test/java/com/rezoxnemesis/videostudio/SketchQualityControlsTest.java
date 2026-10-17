package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchQualityControlsTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private String create()throws Exception {
        String id="quality_controls_"+UUID.randomUUID();
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId",id).put("width",256).put("height",256).put("frameCount",61));
        JSONObject o=SketchEditOps.completeObject(new JSONObject().put("id","subject").put("kind","ellipse").put("fill",true).put("color","#FFFFFFFF")
                .put("exposure",61).put("bounds",new JSONObject().put("x",.1).put("y",.1).put("width",.8).put("height",.8)));
        o.getJSONArray("keys").put(new JSONObject().put("frame",60).put("transform",SketchDocument.transform().put("x",1)).put("interpolation","hold"));
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(o);
        try(SketchProjectStore store=new SketchProjectStore(context)){store.importOwnedDocument(d,"create");}return id;
    }
    private ActivityController<SketchStudioActivity> open(String id)throws Exception {
        ActivityController<SketchStudioActivity> c=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id));
        c.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).remove("lastBookId").commit();
        c.create().start().resume().visible();settle(c.get());
        Object canvas=field(c.get(),"canvas");Field selected=canvas.getClass().getDeclaredField("selectedId");selected.setAccessible(true);selected.set(canvas,"subject");return c;
    }
    private JSONObject saved(String id)throws Exception {try(SketchProjectStore s=new SketchProjectStore(context)){return SketchDocument.object(s.get(id),"subject");}}
    @Test public void touchMaterialControlsPersistAndRemoveShadingUsingTheSharedEditOperation()throws Exception {
        String id=create();
        try(ActivityController<SketchStudioActivity> c=open(id)){
            invoke(c.get(),"materialShadingDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            edit(dialog,"Colours (#AARRGGBB, comma separated)").setText("#FFFFFFFF, #FF20A0DF, #FF050A20");
            edit(dialog,"Stop positions from 0 to 1 (optional)").setText("0, 0.5, 1");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(c.get());
            assertEquals("radial",saved(id).getJSONObject("gradient").getString("type"));
            invoke(c.get(),"materialShadingDialog");dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("[0,0.5,1]",new JSONArray("["+edit(dialog,"Stop positions from 0 to 1 (optional)").getText().toString()+"]").toString());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();settle(c.get());assertFalse(saved(id).has("gradient"));
        }
    }
    @Test public void touchTimingControlsSaveTheCurveAndAffectActualDocumentSampling()throws Exception {
        String id=create();
        try(ActivityController<SketchStudioActivity> c=open(id)){
            invoke(c.get(),"transformDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            Spinner timing=(Spinner)find(dialog.getWindow().getDecorView(),"Animation timing");assertNotNull(timing);timing.setSelection(5);ShadowLooper.idleMainLooper();
            edit(dialog,"Bezier timing: x1, y1, x2, y2").setText("0.42, 0, 1, 1");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(c.get());
            JSONObject o=saved(id);assertEquals("bezier",o.getJSONArray("keys").getJSONObject(0).getString("interpolation"));
            assertEquals(.3153568125,SketchDocument.transformAt(o,30).getDouble("x"),.000001);
        }
    }
    @Test public void preparedPoseAndCameraDialogsSaveAndPreserveIndependentCurves()throws Exception {
        String id=create();Intent intent=new Intent(context,SketchAnimationActivity.class).putExtra("sketchId",id);
        try(ActivityController<SketchAnimationActivity> c=Robolectric.buildActivity(SketchAnimationActivity.class,intent).create().start().resume().visible()){
            SketchAnimationActivity activity=c.get();settle(activity);
            invoke(activity,"poseTimingCurve");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            ((Spinner)find(dialog.getWindow().getDecorView(),"Animation timing")).setSelection(6);ShadowLooper.idleMainLooper();
            edit(dialog,"Bezier timing: x1, y1, x2, y2").setText("0.25, 0.1, 0.25, 1");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(activity);
            JSONObject program=(JSONObject)field(activity,"program");JSONObject key=program.getJSONArray("rigs").getJSONObject(0).getJSONArray("poses").getJSONObject(0);
            assertEquals("bezier",key.getString("interpolation"));assertEquals(.25,key.getJSONArray("curve").getDouble(0),0);
            invoke(activity,"camera");dialog=ShadowAlertDialog.getLatestAlertDialog();
            ((Spinner)find(dialog.getWindow().getDecorView(),"Animation timing")).setSelection(6);ShadowLooper.idleMainLooper();
            edit(dialog,"Bezier timing: x1, y1, x2, y2").setText("0.42, 0, 1, 1");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(activity);
            invoke(activity,"camera");dialog=ShadowAlertDialog.getLatestAlertDialog();
            edit(dialog,"Camera X").setText("0.1");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(activity);
            JSONObject camera=program.getJSONArray("camera").getJSONObject(0);
            assertEquals(.1,camera.getDouble("x"),0);assertEquals("bezier",camera.getString("interpolation"));
            assertEquals(.42,camera.getJSONArray("curve").getDouble(0),0);
            assertEquals(.25,key.getJSONArray("curve").getDouble(0),0);
        }
    }
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void invoke(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}
    private static void settle(Object a)throws Exception{for(int i=0;i<4;i++){((ExecutorService)field(a,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    private static EditText edit(AlertDialog d,String label){View v=find(d.getWindow().getDecorView(),label);assertNotNull(label,v);return (EditText)v;}
    private static View find(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){View hit=find(g.getChildAt(n),label);if(hit!=null)return hit;}}return null;}
}
