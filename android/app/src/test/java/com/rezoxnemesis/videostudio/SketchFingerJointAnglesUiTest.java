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

/** Real ordinary hand dialog, with blank optional per-key degrees preserving old input. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchFingerJointAnglesUiTest {
 @Test public void optionalPerPoseJointFieldsOmitByDefaultAndSubmitExplicitTriplesThroughSameOperation()throws Exception{
  for(boolean explicit:new boolean[]{false,true})try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
   List<JSONObject> requests=new ArrayList<>();open(activity.get(),(request,done)->{requests.add(request);done.accept(null);});View form=dialog().getWindow().getDecorView();CheckBox custom=(CheckBox)SketchSolidUiTest.description(form,"Custom poses");assertNotNull(custom);custom.setChecked(true);ShadowLooper.idleMainLooper();
   for(String finger:SketchFingerJointAnglesTest.FINGERS)for(String joint:new String[]{"MCP","PIP","DIP"}){EditText field=field(form,finger+" "+joint+" (degrees)");assertEquals("Optional explicit fields must not invent defaults","",field.getText().toString());}
   if(explicit){field(form,"index MCP (degrees)").setText("90");field(form,"index PIP (degrees)").setText("80");field(form,"index DIP (degrees)").setText("35");}
   dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals(1,requests.size());JSONObject op=requests.get(0).getJSONArray("operations").getJSONObject(1);assertEquals("add_hand_animation",op.getString("type"));assertFalse(op.getJSONObject("settings").has("fingerJointAngles"));JSONArray poses=op.getJSONArray("poses");if(explicit){JSONObject map=poses.getJSONObject(0).getJSONObject("fingerJointAngles");assertEquals(1,map.length());assertEquals("[90,80,35]",map.getJSONArray("index").toString());for(int i=1;i<poses.length();i++)assertFalse(poses.getJSONObject(i).has("fingerJointAngles"));}else for(int i=0;i<poses.length();i++)assertFalse(poses.getJSONObject(i).has("fingerJointAngles"));
  }
 }
 @Test public void incompleteOrOutOfRangeTripleReportsErrorAndCannotSubmit()throws Exception{
  for(boolean incomplete:new boolean[]{true,false})try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
   List<JSONObject> requests=new ArrayList<>();open(activity.get(),(request,done)->{requests.add(request);done.accept(null);});View form=dialog().getWindow().getDecorView();((CheckBox)SketchSolidUiTest.description(form,"Custom poses")).setChecked(true);ShadowLooper.idleMainLooper();field(form,"index MCP (degrees)").setText(incomplete?"90":"111");if(!incomplete){field(form,"index PIP (degrees)").setText("80");field(form,"index DIP (degrees)").setText("35");}dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertTrue(requests.isEmpty());assertTrue(((TextView)SketchSolidUiTest.description(form,"Hand animation status")).getText().length()>0);dialog().cancel();
  }
 }
 private static EditText field(View form,String name){View view=SketchSolidUiTest.description(form,name);assertNotNull("Missing actual joint-angle control: "+name,view);assertTrue(view instanceof EditText);return (EditText)view;}
 private static void open(Activity activity,SketchConstructionSubmit submit)throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","finger_angles_ui").put("width",512).put("height",512).put("frameCount",96).put("fps",24));Constructor<SketchHandDialog> c=SketchHandDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);c.setAccessible(true);Object owner=c.newInstance(activity,d,submit);Method show=SketchHandDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();}
 private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
}
