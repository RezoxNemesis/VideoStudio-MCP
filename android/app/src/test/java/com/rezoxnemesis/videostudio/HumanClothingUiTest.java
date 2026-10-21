package com.rezoxnemesis.videostudio;

import android.app.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

/** Manual clothing request/preview protection, separate from native garment/pixel acceptance. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class HumanClothingUiTest {
    @Test public void legacyDefaultOmitsNewFieldAndKeepsExistingSettingsAndSource()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject source=document();String before=json(source);List<JSONObject> requests=new ArrayList<>();open(activity.get(),source,requests);assertEquals(2,spinner("Clothing").getCount());assertEquals("legacy",spinner("Clothing").getSelectedItem());
            JSONObject settings=save(requests).getJSONObject("settings");assertFalse(settings.has("clothingStyle"));assertEquals(json(SketchHumanFigure.defaults()),json(settings));assertEquals(13,settings.length());assertEquals(before,json(source));
        }
    }
    @Test public void tailoredUsesSameNativeRequestWithBothBuildsFacesAndShadingModes()throws Exception {
        for(String build:new String[]{"slender","broad"})for(String face:new String[]{"graphic","angular","soft"})for(String shading:new String[]{"cel","soft"})try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject source=document();String before=json(source);List<JSONObject> requests=new ArrayList<>();open(activity.get(),source,requests);select("Clothing","tailored");select("Adult build",build);select("Face structure",face);select("Surface shading",shading);edit("lightAngle").setText("-42.5");
            JSONObject operation=save(requests),settings=operation.getJSONObject("settings");assertEquals("add_human_animation",operation.getString("type"));assertEquals("tailored",settings.getString("clothingStyle"));assertEquals(build,settings.getString("build"));assertEquals(face,settings.getString("faceStyle"));assertEquals(shading,settings.getString("shading"));assertEquals(-42.5,settings.getDouble("lightAngle"),0);assertEquals(json(settings),json(SketchHumanFigure.settings(settings)));assertEquals(before,json(source));assertFalse(operation.has("clothingStyle"));
        }
    }
    @Test public void clothingChangeCancelsQueuedPreviewWithoutChangingCustomPosesOrDocument()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject source=document();String before=json(source);Object owner=open(activity.get(),source,new ArrayList<>());check("Edit custom poses").setChecked(true);ShadowLooper.idleMainLooper();ExecutorService worker=(ExecutorService)field(owner,"worker");CountDownLatch entered=new CountDownLatch(1);Future<?> blocker=worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));SketchSolidUiTest.button(root(),"Preview human").performClick();Future<?> task=(Future<?>)field(owner,"task");assertNotNull(task);String poses=SketchDocument.canonical(new JSONArray(field(owner,"poses").toString()));int generation=(int)field(owner,"generation");select("Clothing","tailored");assertTrue(task.isCancelled());assertNull(field(owner,"task"));assertTrue((int)field(owner,"generation")>generation);assertEquals(poses,SketchDocument.canonical(new JSONArray(field(owner,"poses").toString())));assertEquals(before,json(source));assertEquals("graphic",spinner("Face structure").getSelectedItem());assertEquals("cel",spinner("Surface shading").getSelectedItem());}finally{dialog().cancel();blocker.cancel(true);worker.shutdownNow();}
        }
    }
    @Test public void returningToLegacyPreservesCustomGestureAndSpeechWithoutInjectingStyle()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            List<JSONObject> requests=new ArrayList<>();open(activity.get(),document(),requests);check("Edit custom poses").setChecked(true);edit("nearWrist").setText("35");edit("handOpen").setText("0.4");check("Timed speech").setChecked(true);edit("Speech timing (editable JSON)").setText("{\"cues\":[{\"startMs\":0,\"endMs\":100,\"shape\":\"closed\"}]}");select("Clothing","tailored");select("Clothing","legacy");JSONObject op=save(requests);assertFalse(op.getJSONObject("settings").has("clothingStyle"));assertEquals(35,op.getJSONArray("poses").getJSONObject(0).getDouble("nearWrist"),0);assertEquals(.4,op.getJSONArray("poses").getJSONObject(0).getDouble("handOpen"),0);assertEquals("closed",op.getJSONObject("speech").getJSONArray("cues").getJSONObject(0).getString("shape"));
        }
    }
    @Test public void nativeOptionalFieldKeepsLegacyCanonicalBytesAndRejectsMalformedStyles()throws Exception {
        JSONObject defaults=SketchHumanFigure.defaults();String before=json(defaults);assertFalse(defaults.has("clothingStyle"));assertEquals(before,json(SketchHumanFigure.settings(defaults)));
        for(String style:new String[]{"legacy","tailored"}){JSONObject input=new JSONObject().put("clothingStyle",style);String original=json(input);assertEquals(style,SketchHumanFigure.settings(input).getString("clothingStyle"));assertEquals(original,json(input));}
        for(Object style:new Object[]{"Tailored","",JSONObject.NULL,true,0,new JSONArray(),new JSONObject()})assertThrows(Exception.class,()->SketchHumanFigure.settings(new JSONObject().put("clothingStyle",style)));
    }
    private static JSONObject document()throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","clothing_ui").put("width",1280).put("height",720).put("frameCount",96).put("fps",24));}
    private static Object open(Activity activity,JSONObject doc,List<JSONObject> requests)throws Exception{Constructor<SketchHumanDialog> c=SketchHumanDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);c.setAccessible(true);Object owner=c.newInstance(activity,doc,(SketchConstructionSubmit)(request,done)->{requests.add(request);done.accept(null);});Method show=SketchHumanDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();return owner;}
    private static JSONObject save(List<JSONObject> requests)throws Exception{dialog().getButton(-1).performClick();ShadowLooper.idleMainLooper();assertEquals("Manual create must dispatch once",1,requests.size());JSONArray ops=requests.get(0).getJSONArray("operations");for(int i=0;i<ops.length();i++)if(ops.getJSONObject(i).getString("type").equals("add_human_animation"))return ops.getJSONObject(i);throw new AssertionError("No human operation");}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static String json(JSONObject object)throws Exception{return SketchDocument.canonical(SketchDocument.copy(object));}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View root(){return dialog().getWindow().getDecorView();}
    private static View control(String label){View v=SketchSolidUiTest.description(root(),label);assertNotNull("Missing manual control: "+label,v);return v;}
    private static Spinner spinner(String label){return (Spinner)control(label);}
    private static EditText edit(String label){return (EditText)control(label);}
    private static void select(String label,String value){Spinner s=spinner(label);for(int i=0;i<s.getCount();i++)if(value.equals(s.getItemAtPosition(i).toString())){s.setSelection(i);ShadowLooper.idleMainLooper();return;}throw new AssertionError("Missing option: "+value);}
    private static CheckBox check(String label){CheckBox b=findCheck(root(),label);assertNotNull(label,b);return b;}
    private static CheckBox findCheck(View v,String label){if(v instanceof CheckBox&&label.contentEquals(((CheckBox)v).getText()))return (CheckBox)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){CheckBox b=findCheck(g.getChildAt(i),label);if(b!=null)return b;}}return null;}
}
