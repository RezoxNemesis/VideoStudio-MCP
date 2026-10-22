package com.rezoxnemesis.videostudio;

import android.app.*;
import android.view.View;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

/** Actual manual selector and shared constructor requests, including invalid combinations. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchHandPartPassUiTest {
 @Test public void manualPartsDefaultOmittedAndExplicitChoicesUseTheOrdinaryHandOperation()throws Exception{
  for(int choice=0;choice<4;choice++)try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
   List<JSONObject> requests=new ArrayList<>();open(activity.get(),(request,done)->{requests.add(request);done.accept(null);});View form=dialog().getWindow().getDecorView();Spinner parts=spinner(form,"Hand parts");assertEquals(4,parts.getCount());assertEquals("Legacy default (complete hand)",parts.getSelectedItem());
   if(choice>=2)spinner(form,"Render style").setSelection(1);parts.setSelection(choice);ShadowLooper.idleMainLooper();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals(1,requests.size());JSONObject operation=requests.get(0).getJSONArray("operations").getJSONObject(1);assertEquals("add_hand_animation",operation.getString("type"));assertFalse(operation.getJSONObject("settings").has("parts"));if(choice==0)assertFalse(operation.has("parts"));else assertEquals(new String[]{"all","palm_forearm","digits"}[choice-1],operation.getString("parts"));
  }
 }
 @Test public void partialSmoothIsReportedWithoutSubmissionOrSilentStyleChange()throws Exception{
  try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
   List<JSONObject> requests=new ArrayList<>();open(activity.get(),(request,done)->{requests.add(request);done.accept(null);});View form=dialog().getWindow().getDecorView();spinner(form,"Hand parts").setSelection(3);ShadowLooper.idleMainLooper();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertTrue(requests.isEmpty());assertEquals("smooth",spinner(form,"Render style").getSelectedItem());assertTrue(((TextView)SketchSolidUiTest.description(form,"Hand animation status")).getText().toString().contains("cel"));dialog().cancel();
  }
 }
 private static void open(Activity activity,SketchConstructionSubmit submit)throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","hand_part_ui").put("width",512).put("height",512).put("frameCount",96).put("fps",24));Constructor<SketchHandDialog> c=SketchHandDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);c.setAccessible(true);Object owner=c.newInstance(activity,d,submit);Method show=SketchHandDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();}
 private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
 private static Spinner spinner(View form,String name){View view=SketchSolidUiTest.description(form,name);assertNotNull("Missing actual manual control: "+name,view);return (Spinner)view;}
}
