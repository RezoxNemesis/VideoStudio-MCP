package com.rezoxnemesis.videostudio;
import android.app.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.*;
import java.util.*;
import static org.junit.Assert.*;
/** Original spatial hair guide. Native landmarks do not establish actor likeness or accepted animation. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchCenterPartFringeTest {
 static final String[] VIEWS={"front","left","right","back","high","low","three_quarter_left","three_quarter_right"};
 static JSONObject settings(String view,String fringe)throws Exception{JSONObject s=new JSONObject().put("figureStyle","anatomy_sketch").put("view",view).put("hairStyle","long").put("hairColor","#FF202027").put("skinColor","#FFF1EEE8");if(fringe!=null)s.put("fringeStyle",fringe);return SketchHumanFigure.settings(s);}
 static JSONObject head(String view,String fringe)throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","fringe").put("width",512).put("height",640).put("frameCount",1).put("background","#00000000"));JSONObject layer=d.getJSONArray("layers").getJSONObject(0),s=settings(view,fringe);SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(s,new JSONObject());SketchAnatomyHead.draw(layer,"actor",s,p);double[] centre=p.camera.point(p.head);JSONArray objects=layer.getJSONArray("objects");for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);JSONArray commands=new JSONArray(o.getJSONArray("commands").toString());o.put("commands",commands);for(int j=0;j<commands.length();j++){JSONArray c=commands.getJSONArray(j);for(int k=1;k<c.length();k+=2){double x=(c.getDouble(k)*1280-640)*16-centre[0],y=(c.getDouble(k+1)*720-360)*16-centre[1];c.put(k,(256+x*6.5)/512).put(k+1,(232+y*6.5)/640);}}o.put("width",o.getDouble("width")*720*6.5/512);}SketchDocument.validate(d);return d;}
 static Bitmap render(JSONObject d)throws Exception{try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){return r.render(0,false);}}
 static boolean dark(int p){return Color.alpha(p)>220&&Color.red(p)<125&&Color.green(p)<125&&Color.blue(p)<135;}
 static int coord(double n){return(int)Math.round(256+n*6.5);}static int y(double n){return(int)Math.round(232+n*6.5);}
 @Test public void strictAnatomyOnlyFringeSelectionDoesNotInsertAnOmittedDefault()throws Exception {
  assertFalse(SketchHumanFigure.defaults().has("fringeStyle"));assertFalse(settings("front",null).has("fringeStyle"));assertEquals("center_part",settings("front","center_part").getString("fringeStyle"));assertEquals("swept",settings("front","swept").getString("fringeStyle"));
  for(Object bad:new Object[]{JSONObject.NULL,true,1,"center","Center_part",new JSONObject()})assertThrows(Exception.class,()->SketchHumanFigure.settings(new JSONObject().put("figureStyle","anatomy_sketch").put("fringeStyle",bad)));
  for(String value:new String[]{"swept","center_part"})assertThrows(Exception.class,()->SketchHumanFigure.settings(new JSONObject().put("fringeStyle",value)));
 }
 @Test public void explicitSweptAndOmissionRetainEveryExistingNativeCommandAcrossAllViews()throws Exception {
  for(String view:VIEWS)assertEquals("Existing hair geometry must remain unchanged for "+view,SketchDocument.canonical(head(view,null).getJSONArray("layers")),SketchDocument.canonical(head(view,"swept").getJSONArray("layers")));
 }
 @Test public void centeredPartHasTwoLongBrowLengthMassesAndAnOpenForehead()throws Exception {
  Bitmap a=render(head("front",null)),b=render(head("front","center_part"));try{for(int side:new int[]{-1,1}){int added=0;for(int yy=y(-8);yy<=y(4);yy++)for(int xx=coord(side<0?-13:2);xx<=coord(side<0?-2:13);xx++)if(dark(b.getPixel(xx,yy))&&!dark(a.getPixel(xx,yy)))added++;assertTrue("Each part needs actual dark fringe mass below the old short hairline: side"+side+" added"+added,added>150);}int open=0;for(int yy=y(-9);yy<=y(-1);yy++)for(int xx=coord(-1.5);xx<=coord(1.5);xx++){int p=b.getPixel(xx,yy);if(Color.alpha(p)>220&&Color.red(p)>180)open++;}assertTrue("The center part must leave an open forehead rather than a solid helmet",open>60);}finally{a.recycle();b.recycle();}
 }
 @Test public void bothExactProfilesRetainAVisibleFringeVolumeRatherThanAConstantDepthStrip()throws Exception {
  for(String view:new String[]{"left","right"}){Bitmap a=render(head(view,null)),b=render(head(view,"center_part"));try{int changed=0,maxRun=0;int left=coord(view.equals("right")?12:-28),right=coord(view.equals("right")?28:-12);for(int yy=y(-18);yy<=y(5);yy++){int run=0;for(int xx=left;xx<=right;xx++){if(dark(b.getPixel(xx,yy))&&!dark(a.getPixel(xx,yy))){changed++;run++;maxRun=Math.max(maxRun,run);}else run=0;}}assertTrue("Native profile fringe must have visible filled mass: "+view+" pixels"+changed,changed>100);assertTrue("Native profile fringe must retain thickness: "+view+" run"+maxRun,maxRun>=5);}finally{a.recycle();b.recycle();}}
 }
 static View find(View v,String description){if(description.equals(v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View q=find(((ViewGroup)v).getChildAt(i),description);if(q!=null)return q;}return null;}
 static JSONObject submittedSettings(JSONObject r)throws Exception{JSONArray ops=r.getJSONArray("operations");for(int i=0;i<ops.length();i++)if("add_human_animation".equals(ops.getJSONObject(i).getString("type")))return ops.getJSONObject(i).getJSONObject("settings");throw new AssertionError("Manual native request missing human animation");}
 @Test public void manualFringeSelectionSharesNativeRequestsAndDefaultOmission()throws Exception {
  try(ActivityController<Activity> ctl=Robolectric.buildActivity(Activity.class).setup()){
   JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","fringe_ui").put("width",1280).put("height",720).put("frameCount",48));List<JSONObject> requests=new ArrayList<>();SketchHumanDialog.show(ctl.get(),d,(r,done)->{requests.add(r);done.accept(new IllegalStateException("Retain edited controls"));});ShadowLooper.idleMainLooper();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();Spinner fringe=(Spinner)find(root,"Hair fringe");assertNotNull("Native acting must expose the same fringe choice manually",fringe);assertFalse(fringe.isEnabled());dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals("Initial Create must submit; "+((TextView)find(root,"Human animation status")).getText(),1,requests.size());assertFalse(submittedSettings(requests.get(0)).has("fringeStyle"));((Spinner)find(root,"Figure style")).setSelection(1);ShadowLooper.idleMainLooper();assertTrue(fringe.isEnabled());int center=-1;for(int i=0;i<fringe.getCount();i++)if("Center part".equals(fringe.getItemAtPosition(i)))center=i;assertTrue(center>=0);fringe.setSelection(center);ShadowLooper.idleMainLooper();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals("Center-part Create must submit; "+((TextView)find(root,"Human animation status")).getText(),2,requests.size());assertEquals("center_part",submittedSettings(requests.get(1)).getString("fringeStyle"));fringe.setSelection(0);ShadowLooper.idleMainLooper();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals("Swept Create must submit; "+((TextView)find(root,"Human animation status")).getText(),3,requests.size());assertFalse(submittedSettings(requests.get(2)).has("fringeStyle"));dialog.cancel();
  }
 }
}
