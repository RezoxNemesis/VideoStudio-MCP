package com.rezoxnemesis.videostudio;
import android.app.AlertDialog;
import android.content.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSolidUiTest {
 @Test public void everyManualStudyConstructsNativeCelsWithTheDisplayedSettings()throws Exception{
  for(int selected=0;selected<5;selected++)try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
   JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","manual_"+selected).put("width",1280).put("height",720).put("fps",30).put("frameCount",180));JSONObject[] result={null};SketchSolidDialog.show(a.get(),d,r->result[0]=r);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();((Spinner)description(dialog.getWindow().getDecorView(),"Solid shape")).setSelection(selected);ShadowLooper.idleMainLooper();button(dialog.getWindow().getDecorView(),"Load motion study").performClick();assertTrue(((CheckBox)description(dialog.getWindow().getDecorView(),"World lighting")).isChecked());assertTrue(((CheckBox)description(dialog.getWindow().getDecorView(),"Merge held cels")).isChecked());dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);
   JSONArray ops=result[0].getJSONArray("operations");for(int n=0;n<ops.length();n++)SketchEditOps.apply(d,ops.getJSONObject(n));SketchDocument.validate(d);assertEquals(2,d.getJSONArray("layers").length());assertEquals(0,d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());JSONObject faces=SketchDocument.copy(d);JSONArray source=faces.getJSONArray("layers").getJSONObject(1).getJSONArray("objects"),onlyFaces=new JSONArray();for(int n=0;n<source.length();n++){JSONObject object=source.getJSONObject(n);if(object.getBoolean("fill")&&!object.getString("id").endsWith("_shadow")&&!object.getString("id").endsWith("_body"))onlyFaces.put(object);}faces.getJSONArray("layers").getJSONObject(1).put("objects",onlyFaces);try(SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),faces)){android.graphics.Bitmap frame=renderer.render(90);try{int[] pixels=new int[1280*720];frame.getPixels(pixels,0,1280,0,0,1280,720);int changed=0;for(int pixel:pixels)if(pixel!=pixels[0])changed++;assertTrue("Manual study must render actual surface art, independently of guides or shadows",changed>2000);}finally{frame.recycle();}}
  }
 }
 @Test public void manualShapeAndRollingStudyUseNativeSphereGeometry()throws Exception{
  try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
   JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","sphere_controls").put("width",1280).put("height",720).put("frameCount",180));JSONObject[] result={null};SketchSolidDialog.show(a.get(),d,r->result[0]=r);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();Spinner shape=(Spinner)description(dialog.getWindow().getDecorView(),"Solid shape");assertNotNull("Manual shape selection",shape);shape.setSelection(1);ShadowLooper.idleMainLooper();button(dialog.getWindow().getDecorView(),"Load motion study").performClick();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
   JSONObject p=result[0].getJSONArray("operations").getJSONObject(1).getJSONObject("program");assertEquals("sphere",p.getString("shape"));assertEquals(80,p.getDouble("rollingRadius"),0);assertEquals(160,p.getJSONArray("size").getDouble(1),0);assertTrue(new SketchSolidOps.Model(d,p).vertices(0).length>100);
  }
 }
 @Test public void manualFlightAndSquashControlsReachTheSameNativeProgram()throws Exception{
  try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
   JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","flight_controls").put("width",1280).put("height",720).put("frameCount",61));JSONObject[] result={null};SketchSolidDialog.show(a.get(),d,r->result[0]=r);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
   CheckBox volume=(CheckBox)description(dialog.getWindow().getDecorView(),"Preserve squash volume");assertNotNull("Native volume control",volume);volume.setChecked(true);CheckBox world=(CheckBox)description(dialog.getWindow().getDecorView(),"Squash in world axes");assertNotNull(world);world.setChecked(true);
   button(dialog.getWindow().getDecorView(),"Pose keys").performClick();choose("Pose 1 · frame 1");AlertDialog pose=ShadowAlertDialog.getLatestAlertDialog();EditText flight=(EditText)description(pose.getWindow().getDecorView(),"Pose flightHeight");assertNotNull("Native flight arc control",flight);flight.setText("120");assertNotNull(description(pose.getWindow().getDecorView(),"Scale timing"));pose.getButton(AlertDialog.BUTTON_POSITIVE).performClick();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
   JSONObject program=result[0].getJSONArray("operations").getJSONObject(1).getJSONObject("program");assertTrue(program.getBoolean("preserveVolume"));assertEquals("world",program.getString("scaleSpace"));assertEquals(120,program.getJSONArray("keys").getJSONObject(0).getDouble("flightHeight"),0);new SketchSolidOps.Model(d,program);
  }
 }
 @Test public void editingLastPosePreservesAuthoredTimingWithoutChangingSketchClock()throws Exception{
  try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
   JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","manual").put("width",1280).put("height",720).put("fps",24).put("frameCount",61));JSONObject[] result={null};
   SketchSolidDialog.show(a.get(),doc,r->result[0]=r);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
   button(dialog.getWindow().getDecorView(),"Pose keys").performClick();choose("Pose 2 · frame 61");AlertDialog pose=ShadowAlertDialog.getLatestAlertDialog();((EditText)description(pose.getWindow().getDecorView(),"Pose frame")).setText("31");pose.getButton(AlertDialog.BUTTON_POSITIVE).performClick();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
   assertNotNull(result[0]);JSONArray ops=result[0].getJSONArray("operations");assertEquals("add_layer",ops.getJSONObject(0).getString("type"));assertEquals(30,ops.getJSONObject(1).getJSONObject("program").getJSONArray("keys").getJSONObject(1).getInt("frame"));
  }
 }
 @Test public void loadingStudyPreservesTheSelectedSketchClock()throws Exception{
  try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
   JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","study").put("width",1280).put("height",720).put("fps",24).put("frameCount",121));JSONObject[] result={null};
   SketchSolidDialog.show(a.get(),doc,r->result[0]=r);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();button(dialog.getWindow().getDecorView(),"Load motion study").performClick();
   assertEquals("121",((EditText)description(dialog.getWindow().getDecorView(),"Animation frames")).getText().toString());assertEquals("24",((EditText)description(dialog.getWindow().getDecorView(),"Animation FPS")).getText().toString());
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);assertEquals("add_layer",result[0].getJSONArray("operations").getJSONObject(0).getString("type"));
  }
 }
 @Test public void manualConstructionUsesTheNativeOperationAndPreservesThePriorLayer()throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id=SketchSolidOpsTest.create(c,61);
  try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id))){
   SketchStudioActivity studio=a.get();studio.getPreferences(0).edit().putBoolean("watchLiveDrawing",false).remove("lastBookId").commit();a.create().start().resume().visible();flush(studio);
   button(studio.getWindow().getDecorView(),"Brush / Text").performClick();choose("Solid construction");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(description(dialog.getWindow().getDecorView(),"Solid width"));assertNotNull(description(dialog.getWindow().getDecorView(),"Solid depth"));
   button(dialog.getWindow().getDecorView(),"Pose keys").performClick();choose("Pose 2 · frame 61");AlertDialog pose=ShadowAlertDialog.getLatestAlertDialog();assertNotNull("Manual depth timing control",description(pose.getWindow().getDecorView(),"Depth timing"));((EditText)description(pose.getWindow().getDecorView(),"Pose yaw")).setText("180");pose.getButton(AlertDialog.BUTTON_POSITIVE).performClick();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();flush(studio);
   try(SketchProjectStore s=new SketchProjectStore(c)){JSONObject doc=s.get(id);assertEquals(2,doc.getInt("revision"));assertEquals(2,doc.getJSONArray("layers").length());assertEquals(0,doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());assertTrue(doc.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").length()>100);try(SketchFrameRenderer r=new SketchFrameRenderer(c,doc)){android.graphics.Bitmap image=r.render(60);try{assertEquals("The manual yaw edit must reach native geometry",0xff39b9ad,image.getPixel(640,360));}finally{image.recycle();}}}
  }
 }
 static void flush(SketchStudioActivity s)throws Exception{for(int i=0;i<8;i++){((ExecutorService)field(s,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);((ExecutorService)field(s,"transfers")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static Button button(View v,String text){if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return (Button)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){Button b=button(g.getChildAt(n),text);if(b!=null)return b;}}return null;}
 static View description(View v,String text){if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){View b=description(g.getChildAt(n),text);if(b!=null)return b;}}return null;}
 static void choose(String text){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();ListView list=d.getListView();for(int i=0;i<list.getCount();i++)if(text.equals(String.valueOf(list.getAdapter().getItem(i)))){list.performItemClick(list.getAdapter().getView(i,null,list),i,i);return;}fail("Missing manual tool: "+text);}
}
