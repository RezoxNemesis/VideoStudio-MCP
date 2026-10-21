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
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchPathClipUiTest {
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void settle(SketchStudioActivity a)throws Exception{ShadowLooper.idleMainLooper();((SketchEditorSession)field(a,"session")).awaitIdle(30000);((ExecutorService)field(a,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
 static View find(View v,String label){if(label.equals(v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=find(((ViewGroup)v).getChildAt(i),label);if(r!=null)return r;}return null;}
 static AlertDialog open(SketchStudioActivity a)throws Exception{((SketchCanvasView)field(a,"canvas")).selectObject("ink");Method menu=SketchStudioActivity.class.getDeclaredMethod("brushMenu");menu.setAccessible(true);menu.invoke(a);AlertDialog list=ShadowAlertDialog.getLatestAlertDialog();ListView choices=list.getListView();int index=-1;for(int i=0;i<choices.getAdapter().getCount();i++)if("Path clipping".equals(choices.getAdapter().getItem(i).toString()))index=i;assertTrue("Manual path clipping must be accessible from Brush / Text",index>=0);choices.performItemClick(null,index,index);ShadowLooper.idleMainLooper();return ShadowAlertDialog.getLatestAlertDialog();}
 static JSONObject load(Context c,String id)throws Exception{try(SketchProjectStore s=new SketchProjectStore(c)){return s.get(id);}}
 @Test public void manualApplyInspectCancelAndClearUseOrdinaryUndoablePathEdits()throws Exception {
  Context c=RuntimeEnvironment.getApplication();JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","clip_ui_"+java.util.UUID.randomUUID()).put("width",256).put("height",256));d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchPathClipTest.stroke());String id=d.getString("id");try(SketchProjectStore s=new SketchProjectStore(c)){s.importOwnedDocument(d,"fixture");}
  try(ActivityController<SketchStudioActivity> ctl=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
   SketchStudioActivity a=ctl.get();settle(a);AlertDialog dialog=open(a);EditText rings=(EditText)find(dialog.getWindow().getDecorView(),"Hidden path regions");assertNotNull(rings);rings.setText(SketchPathClipTest.rings().toString());dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a);assertEquals(SketchPathClipTest.rings().toString(),SketchDocument.object(load(c,id),"ink").getJSONArray("clipOut").toString());
   dialog=open(a);rings=(EditText)find(dialog.getWindow().getDecorView(),"Hidden path regions");assertEquals(SketchPathClipTest.rings().toString(),rings.getText().toString());String before=SketchDocument.canonical(load(c,id));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();settle(a);assertEquals(before,SketchDocument.canonical(load(c,id)));
   dialog=open(a);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();settle(a);assertFalse(SketchDocument.object(load(c,id),"ink").has("clipOut"));SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("operationId","undo_clear"));assertEquals(SketchPathClipTest.rings().toString(),SketchDocument.object(load(c,id),"ink").getJSONArray("clipOut").toString());
  }
 }
}
