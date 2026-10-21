package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Dialog-to-native-operation checks, separate from the shared HumanDialog test matrix. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchHumanAppearanceGroundUiTest {
    @Test public void defaultsRemainGraphicCelWithNativeLightAndNoSceneGround()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject source=document();String before=json(source);List<JSONObject> requests=new ArrayList<>();open(activity.get(),source,requests);
            assertEquals("graphic",spinner("Face structure").getSelectedItem());assertEquals("cel",spinner("Surface shading").getSelectedItem());
            assertEquals(135,Double.parseDouble(edit("lightAngle").getText().toString()),0);assertTrue(check("Follow scene ground (preset actions)").isChecked());
            JSONObject op=save(requests),settings=op.getJSONObject("settings");assertEquals("graphic",settings.getString("faceStyle"));assertEquals("cel",settings.getString("shading"));assertEquals(135,settings.getDouble("lightAngle"),0);assertFalse(op.has("ground"));assertEquals(before,json(source));
        }
    }

    @Test public void angularAndSoftFaceControlsReachTheRetainedNativeSettings()throws Exception {
        for(String style:new String[]{"angular","soft"})try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject source=document();String before=json(source);List<JSONObject> requests=new ArrayList<>();open(activity.get(),source,requests);
            select("Face structure",style);select("Surface shading","soft");edit("lightAngle").setText("-42.5");
            JSONObject op=save(requests),settings=op.getJSONObject("settings");assertEquals(style,settings.getString("faceStyle"));assertEquals("soft",settings.getString("shading"));assertEquals(-42.5,settings.getDouble("lightAngle"),0);
            JSONObject rendered=apply(source,requests.get(0)),retained=SketchDocument.object(rendered,op.getString("actorId")+"_rig").getJSONObject("human").getJSONObject("settings");
            assertEquals(style,retained.getString("faceStyle"));assertEquals("soft",retained.getString("shading"));assertEquals(-42.5,retained.getDouble("lightAngle"),0);assertEquals(before,json(source));
        }
    }

    @Test public void sceneryUsesSharedGroundAndOmitsBothSlopeKeysAndSoloFit()throws Exception {
        for(String scene:new String[]{"forest_path","bridge","shelter","village"})try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject source=document();List<JSONObject> requests=new ArrayList<>();open(activity.get(),source,requests);select("Optional scenery",scene);
            JSONObject op=save(requests);assertEquals(scene,op.getString("ground"));assertFalse(op.optBoolean("fit",false));assertFalse(op.has("poses"));
            assertFalse(op.getJSONObject("motion").has("groundSlope"));assertFalse(op.getJSONObject("motion").has("groundSlopeX"));
            JSONObject out=apply(source,requests.get(0)),human=SketchDocument.object(out,op.getString("actorId")+"_rig").getJSONObject("human");
            JSONObject placement=SketchHumanGround.placement(source,op.getJSONObject("settings"),scene);assertEquals(placement.getDouble("floorY"),human.getJSONObject("settings").getDouble("floorY"),0);
            assertFalse(human.has("ground"));assertFalse(human.has("motion"));
        }
    }

    @Test public void uncheckingFollowGroundKeepsExplicitMotionSlopes()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(activity.get(),document(),requests);select("Optional scenery","bridge");check("Follow scene ground (preset actions)").setChecked(false);
            edit("Motion controls (editable JSON)").setText("{\"groundSlope\":0.1,\"groundSlopeX\":-0.2}");JSONObject op=save(requests);
            assertFalse(op.has("ground"));assertEquals(.1,op.getJSONObject("motion").getDouble("groundSlope"),0);assertEquals(-.2,op.getJSONObject("motion").getDouble("groundSlopeX"),0);
        }
    }

    @Test public void customPosesWithSceneryNeverSendGroundOrPresetMotion()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(activity.get(),document(),requests);select("Optional scenery","bridge");check("Edit custom poses").setChecked(true);ShadowLooper.idleMainLooper();
            JSONObject op=save(requests);assertTrue(op.has("poses"));assertFalse(op.has("ground"));assertFalse(op.has("motion"));assertFalse(op.optBoolean("fit",false));
        }
    }

    private static JSONObject document()throws Exception {return SketchDocument.create(new JSONObject().put("sketchId","appearance_ground_ui").put("width",1280).put("height",720).put("frameCount",96).put("fps",24));}
    private static void open(Activity activity,JSONObject doc,List<JSONObject> requests)throws Exception {
        Constructor<SketchHumanDialog> constructor=SketchHumanDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);constructor.setAccessible(true);
        Object owner=constructor.newInstance(activity,doc,(SketchConstructionSubmit)(request,done)->{requests.add(request);done.accept(null);});Method show=SketchHumanDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();
    }
    private static JSONObject save(List<JSONObject> requests)throws Exception {
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals("Manual controls must submit one request",1,requests.size());JSONArray ops=requests.get(0).getJSONArray("operations");
        for(int i=0;i<ops.length();i++)if("add_human_animation".equals(ops.getJSONObject(i).getString("type")))return ops.getJSONObject(i);throw new AssertionError("Missing human operation");
    }
    private static JSONObject apply(JSONObject source,JSONObject request)throws Exception {JSONObject copy=SketchDocument.copy(source);JSONArray ops=request.getJSONArray("operations");for(int i=0;i<ops.length();i++)SketchEditOps.apply(copy,ops.getJSONObject(i));SketchDocument.validate(copy);return copy;}
    private static String json(JSONObject object)throws Exception {return SketchDocument.canonical(SketchDocument.copy(object));}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View control(String label){View v=SketchSolidUiTest.description(dialog().getWindow().getDecorView(),label);assertNotNull("Missing control "+label,v);return v;}
    private static Spinner spinner(String label){return (Spinner)control(label);}
    private static EditText edit(String label){return (EditText)control(label);}
    private static void select(String label,String value){Spinner s=spinner(label);for(int i=0;i<s.getCount();i++)if(value.equals(s.getItemAtPosition(i).toString())){s.setSelection(i);ShadowLooper.idleMainLooper();return;}throw new AssertionError("Missing option "+value);}
    private static CheckBox check(String label){CheckBox box=findCheck(dialog().getWindow().getDecorView(),label);assertNotNull("Missing checkbox "+label,box);return box;}
    private static CheckBox findCheck(View view,String label){if(view instanceof CheckBox&&label.contentEquals(((CheckBox)view).getText()))return (CheckBox)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){CheckBox box=findCheck(group.getChildAt(i),label);if(box!=null)return box;}}return null;}
}
