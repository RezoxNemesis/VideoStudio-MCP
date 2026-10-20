package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchPoseTargetUiTest {
 ActivityController<SketchAnimationActivity> controller;SketchAnimationActivity activity;AlertDialog dialog;JSONObject source,program;
 @After public void close(){if(dialog!=null&&dialog.isShowing())dialog.dismiss();if(controller!=null)controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 static void put(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 static void invoke(Object object,String name)throws Exception{Method m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(object);}
 void setup()throws Exception{
  SketchPoseSamplingTest f=new SketchPoseSamplingTest();f.worker(()->source=f.source());program=f.program();JSONObject rig=program.getJSONArray("rigs").getJSONObject(0);rig.put("joints",new JSONArray("[{id:'root',x:.4,y:.4},{id:'bend',parent:'root',x:.5,y:.4},{id:'tip',parent:'bend',x:.6,y:.4}]")).put("ik",new JSONArray());
  controller=Robolectric.buildActivity(SketchAnimationActivity.class,new Intent(f.context,SketchAnimationActivity.class).putExtra("sketchId",source.getString("id")));activity=controller.create().start().resume().visible().get();settle();put(activity,"program",program);put(activity,"selectedRig","actor");put(activity,"selectedJoint","tip");put(activity,"frame",99);invoke(activity,"refreshPickers");invoke(activity,"changed");settle();invoke(activity,"contacts");dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
 }
 void settle()throws Exception{for(int i=0;i<4;i++){((ExecutorService)field(activity,"io")).submit(()->{}).get(20,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
 View find(View v,String name){if(name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||v instanceof Button&&name.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View result=find(((ViewGroup)v).getChildAt(i),name);if(result!=null)return result;}return null;}
 View view(String name){View v=find(dialog.getWindow().getDecorView(),name);assertNotNull(name,v);return v;}
 ExecutorService pickerWorker()throws Exception{return (ExecutorService)field(view("Posed contact target picker"),"worker");}
 void sample()throws Exception{((Spinner)view("Posed contact target joint")).setSelection(2);Shadows.shadowOf(Looper.getMainLooper()).idle();view("Use posed joint target").performClick();pickerWorker().submit(()->{}).get(20,TimeUnit.SECONDS);}
 @Test public void manualTargetUsesNativePreCameraPoseAndAddsAnOrdinaryContact()throws Exception{
  setup();assertNotNull(view("Use posed joint target"));((Spinner)view("IK root joint")).setSelection(0);((Spinner)view("IK bend joint")).setSelection(1);((Spinner)view("IK contact joint")).setSelection(2);sample();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(.4,Double.parseDouble(((EditText)view("Contact target X")).getText().toString()),1e-12);assertEquals(256d/480,Double.parseDouble(((EditText)view("Contact target Y")).getText().toString()),1e-12);assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();JSONObject added=((JSONObject)field(activity,"program")).getJSONArray("rigs").getJSONObject(0).getJSONArray("ik").getJSONObject(0);assertEquals("root",added.getString("hip"));assertEquals("bend",added.getString("knee"));assertEquals("tip",added.getString("ankle"));assertEquals(99,added.getInt("start"));assertEquals(.4,added.getDouble("x"),1e-12);assertEquals(256d/480,added.getDouble("y"),1e-12);
  try(SketchProjectStore store=new SketchProjectStore(activity)){assertEquals(SketchDocument.canonical(source),SketchDocument.canonical(store.get(source.getString("id"))));}
 }
 @Test public void cancellationPreservesCoordinatesAndTheCurrentDraft()throws Exception{
  setup();String before=SketchDocument.canonical(program),x=((EditText)view("Contact target X")).getText().toString(),y=((EditText)view("Contact target Y")).getText().toString();EditText targetX=(EditText)view("Contact target X"),targetY=(EditText)view("Contact target Y");sample();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(x,targetX.getText().toString());assertEquals(y,targetY.getText().toString());assertEquals(before,SketchDocument.canonical(program));
 }
 @Test public void staleDraftCannotOverwriteAnEditedTarget()throws Exception{
  setup();sample();program.put("fps",30);invoke(activity,"changed");EditText x=(EditText)view("Contact target X");x.setText(".25");Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(".25",x.getText().toString());assertTrue(((TextView)view("Posed contact target status")).getText().toString().contains("changed"));assertEquals(0,program.getJSONArray("rigs").getJSONObject(0).getJSONArray("ik").length());
 }
 @Test public void manualContactEasingUsesTheSameBoundedProgramFieldsAsMcp()throws Exception{
  setup();((Spinner)view("IK root joint")).setSelection(0);((Spinner)view("IK bend joint")).setSelection(1);((Spinner)view("IK contact joint")).setSelection(2);((EditText)view("First contact frame")).setText("21");((EditText)view("Last contact frame")).setText("61");((EditText)view("Contact target X")).setText(".4");((EditText)view("Contact target Y")).setText(".5");((EditText)view("Approach frames")).setText("12");((EditText)view("Release frames")).setText("12");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();JSONObject added=program.getJSONArray("rigs").getJSONObject(0).getJSONArray("ik").getJSONObject(0);assertEquals(12,added.getInt("blendInFrames"));assertEquals(12,added.getInt("blendOutFrames"));assertEquals(20,added.getInt("start"));assertEquals(60,added.getInt("end"));
 }
 @Test public void manualContactEasingCannotLeaveTheShotClock()throws Exception{
  setup();((EditText)view("First contact frame")).setText("2");((EditText)view("Approach frames")).setText("12");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());assertEquals(0,program.getJSONArray("rigs").getJSONObject(0).getJSONArray("ik").length());((EditText)view("First contact frame")).setText("21");((EditText)view("Last contact frame")).setText("100");((EditText)view("Release frames")).setText("1");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());assertEquals(0,program.getJSONArray("rigs").getJSONObject(0).getJSONArray("ik").length());
 }
 @Test public void changingTheFirstFrameWhileSamplingPreservesTheManualTarget()throws Exception{
  setup();String x=((EditText)view("Contact target X")).getText().toString();sample();((EditText)view("First contact frame")).setText("99");Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(x,((EditText)view("Contact target X")).getText().toString());assertTrue(((TextView)view("Posed contact target status")).getText().toString().contains("changed"));
 }
 @Test public void dialogCancelCannotAdmitAnAlreadyQueuedPoseResult()throws Exception{
  setup();EditText x=(EditText)view("Contact target X"),y=(EditText)view("Contact target Y");String beforeX=x.getText().toString(),beforeY=y.getText().toString();sample();dialog.cancel();assertFalse(dialog.isShowing());Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(beforeX,x.getText().toString());assertEquals(beforeY,y.getText().toString());assertEquals(0,program.getJSONArray("rigs").getJSONObject(0).getJSONArray("ik").length());
 }
 @Test public void backCannotAdmitAnAlreadyQueuedPoseResult()throws Exception{
  setup();EditText x=(EditText)view("Contact target X");String before=x.getText().toString();sample();dialog.onBackPressed();assertFalse(dialog.isShowing());Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,x.getText().toString());assertEquals(0,program.getJSONArray("rigs").getJSONObject(0).getJSONArray("ik").length());
 }
 @Test public void pausingWhileTheResultIsQueuedPreservesManualCoordinates()throws Exception{
  setup();EditText x=(EditText)view("Contact target X");String before=x.getText().toString();sample();controller.pause();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,x.getText().toString());assertTrue(((TextView)view("Posed contact target status")).getText().toString().contains("paused"));controller.resume();
 }
 @Test public void changingTheSelectedJointWhileQueuedPreservesManualCoordinates()throws Exception{
  setup();EditText x=(EditText)view("Contact target X");String before=x.getText().toString();sample();((Spinner)view("Posed contact target joint")).setSelection(0);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,x.getText().toString());assertTrue(((TextView)view("Posed contact target status")).getText().toString().contains("changed"));
 }
 @Test public void replacingTheSourceWhileQueuedPreservesManualCoordinates()throws Exception{
  setup();EditText x=(EditText)view("Contact target X");String before=x.getText().toString();sample();put(activity,"source",SketchDocument.copy(source));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,x.getText().toString());assertTrue(((TextView)view("Posed contact target status")).getText().toString().contains("changed"));
 }
}
