package com.rezoxnemesis.videostudio;

import android.app.*;
import android.view.View;
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

/** Actual manual load/create controls, without executing geometry. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchHandStudiesUiTest {
    @Test public void eachStudyLoadsAndCreatesIdenticalHelperKeysAtBaselineAndAdaptedClock()throws Exception {
        for(int[] clock:new int[][]{{96,24},{120,30}})for(int pattern=0;pattern<4;pattern++)try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document(clock[0],clock[1]);String unchanged=SketchHandStudiesTest.json(doc);List<JSONObject> requests=new ArrayList<>();Object owner=open(activity.get(),doc,(request,done)->{requests.add(request);done.accept(null);});View form=root();Spinner chooser=spinner(form,"Study example");assertEquals(4,chooser.getCount());String beforeSelection=SketchHandStudiesTest.json(field(owner,"poses"));chooser.setSelection(pattern);ShadowLooper.idleMainLooper();assertEquals(beforeSelection,SketchHandStudiesTest.json(field(owner,"poses")));
            spinner(form,"Hand view").setSelection(3);spinner(form,"Handedness").setSelection(1);edit(form,"Size (0.4..1.2)").setText(clock[0]==96?"0.95":"0.83");edit(form,"Motion (0..1)").setText(clock[0]==96?"1":"0.4");if(clock[0]==96)spinner(form,"Render style").setSelection(1);ShadowLooper.idleMainLooper();
            String old=SketchHandStudiesTest.json(field(owner,"poses"));assertFalse(custom(form).isChecked());assertEquals(old,SketchHandStudiesTest.json(field(owner,"poses")));
            click(form,"Load study");assertTrue(custom(form).isChecked());assertEquals(clock[0]==96?"cel":"smooth",spinner(form,"Render style").getSelectedItem());assertEquals("back",spinner(form,"Hand view").getSelectedItem());
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());JSONArray ops=requests.get(0).getJSONArray("operations");assertEquals(2,ops.length());JSONObject op=ops.getJSONObject(1);assertEquals("add_hand_animation",op.getString("type"));if(clock[0]==96){JSONObject template=SketchCapabilities.handAnimation().getJSONArray("studyExamples").getJSONObject(pattern).getJSONObject("operation");JSONObject expectedSettings=SketchHandAnimation.settings(template.getJSONObject("settings").put("view","back").put("handedness","left"));assertEquals(SketchHandStudiesTest.json(expectedSettings),SketchHandStudiesTest.json(op.getJSONObject("settings")));}assertEquals(SketchHandStudiesTest.json(SketchHandStudiesTest.poses(SketchHandStudiesTest.PATTERNS[pattern],clock[0],clock[1],op.getJSONObject("settings"))),SketchHandStudiesTest.json(op.getJSONArray("poses")));assertEquals(unchanged,SketchHandStudiesTest.json(doc));
        }
    }
    @Test public void studySelectionAloneRetainsKeysAndResetKeepsNativePresetBehaviour()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(activity.get(),document(96,24),(request,done)->fail("No create"));View form=root();String before=SketchHandStudiesTest.json(field(owner,"poses"));spinner(form,"Study example").setSelection(2);ShadowLooper.idleMainLooper();assertEquals(before,SketchHandStudiesTest.json(field(owner,"poses")));assertFalse(custom(form).isChecked());click(form,"Load study");assertEquals(6,((JSONArray)field(owner,"poses")).length());edit(form,"Motion (0..1)").setText("0.4");click(form,"Reset preset");assertFalse(custom(form).isChecked());assertEquals(SketchHandStudiesTest.json(SketchHandAnimation.preset(96,24,SketchHandAnimation.defaults().put("motion",.4))),SketchHandStudiesTest.json(field(owner,"poses")));dialog().cancel();
        }
    }
    @Test public void explicitLoadCancelsStalePreview()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(activity.get(),document(96,24),(request,done)->fail("Load must not save"));View form=root();ExecutorService worker=(ExecutorService)field(owner,"worker");CountDownLatch entered=new CountDownLatch(1);Future<?> blocker=worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));custom(form).setChecked(true);click(form,"Preview hand");Future<?> task=(Future<?>)field(owner,"previewTask");assertNotNull(task);spinner(form,"Study example").setSelection(1);ShadowLooper.idleMainLooper();assertFalse("Selection alone must not cancel the preview",task.isCancelled());click(form,"Load study");assertTrue(task.isCancelled());assertNull(field(owner,"previewTask"));assertEquals("0.3",edit(form,"indexCurl").getText().toString());assertEquals(6,((JSONArray)field(owner,"poses")).length());assertTrue(custom(form).isChecked());assertTrue(((TextView)SketchSolidUiTest.description(form,"Hand animation status")).getText().toString().contains("96"));}finally{dialog().cancel();blocker.cancel(true);worker.shutdownNow();}
        }
    }
    @Test public void loadReplacesPendingCustomEditsWithoutRequiringThemToValidate()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(activity.get(),document(96,24),(request,done)->fail("Load must not save"));View form=root();custom(form).setChecked(true);edit(form,"indexCurl").setText("invalid pending key");spinner(form,"Study example").setSelection(1);ShadowLooper.idleMainLooper();click(form,"Load study");assertEquals("0.3",edit(form,"indexCurl").getText().toString());assertEquals(6,((JSONArray)field(owner,"poses")).length());dialog().cancel();
        }
    }
    private static JSONObject document(int frames,int fps)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","study_ui").put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps));}
    private static Object open(Activity activity,JSONObject doc,SketchConstructionSubmit submit)throws Exception {Constructor<SketchHandDialog> c=SketchHandDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);c.setAccessible(true);Object owner=c.newInstance(activity,doc,submit);Method m=SketchHandDialog.class.getDeclaredMethod("show");m.setAccessible(true);m.invoke(owner);ShadowLooper.idleMainLooper();return owner;}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View root(){return dialog().getWindow().getDecorView();}
    private static Spinner spinner(View form,String name){View control=SketchSolidUiTest.description(form,name);assertNotNull("Missing "+name,control);return (Spinner)control;}
    private static EditText edit(View form,String name){return (EditText)SketchSolidUiTest.description(form,name);}
    private static CheckBox custom(View form){return (CheckBox)SketchSolidUiTest.description(form,"Custom poses");}
    private static void click(View form,String name){SketchSolidUiTest.button(form,name).performClick();ShadowLooper.idleMainLooper();}
}
