package com.rezoxnemesis.videostudio;
import android.app.AlertDialog;
import android.content.*;
import android.widget.EditText;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSolidCollisionUiTest {
 @Test public void fineSphereMeshIsAlsoAvailableInManualSolidConstruction()throws Exception{
  try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){JSONObject[] result={null};SketchSolidDialog.show(a.get(),SketchSolidCollisionTest.doc(120,30),r->result[0]=r);AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();android.widget.CheckBox fine=(android.widget.CheckBox)SketchSolidUiTest.description(d.getWindow().getDecorView(),"Fine sphere mesh");assertNotNull("Fine native geometry must have a manual control",fine);fine.setChecked(true);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals("fine",result[0].getJSONArray("operations").getJSONObject(1).getJSONObject("program").getString("meshDetail"));}
 }
 @Test public void manualImpactControlsCreateASavedSceneWithTheChosenMassAndTiming()throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id="impact_manual";SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","blank_manual_impact").put("width",1280).put("height",720).put("fps",24).put("frameCount",120));
  try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id))){
   SketchStudioActivity studio=a.get();studio.getPreferences(0).edit().putBoolean("watchLiveDrawing",false).remove("lastBookId").commit();a.create().start().resume().visible();SketchSolidUiTest.flush(studio);
   SketchSolidUiTest.button(studio.getWindow().getDecorView(),"Brush / Text").performClick();SketchSolidUiTest.choose("Ball and box impact");AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();EditText mass=(EditText)SketchSolidUiTest.description(d.getWindow().getDecorView(),"Box mass");assertNotNull(mass);mass.setText("2");assertNotNull(SketchSolidUiTest.description(d.getWindow().getDecorView(),"Restitution"));d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
   try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject saved=store.get(id);assertEquals(2,saved.getInt("revision"));assertEquals(120,saved.getInt("frameCount"));assertEquals(24,saved.getInt("fps"));assertEquals(2,saved.getJSONArray("layers").length());JSONArray actual=saved.getJSONArray("layers").getJSONObject(1).getJSONArray("objects");assertTrue(actual.length()>100);JSONObject expected=SketchSolidCollisionTest.doc(120,24);SketchSolidCollision.apply(expected,SketchSolidCollisionTest.op(new JSONObject().put("boxMass",2)));JSONArray objects=expected.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertEquals(objects.length(),actual.length());for(int n=0;n<objects.length();n++)assertEquals(objects.getJSONObject(n).getJSONArray("commands").toString(),actual.getJSONObject(n).getJSONArray("commands").toString());}
  }
 }
}
