package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Spinner;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Settings and real dialog controls only; rendering/pixel acceptance belongs to Geometry tests. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchHandRenderStyleUiTest {
    @Test public void nativeDefaultsAndLegacyNormalizationUseSmoothWithoutMutatingLegacySettings()throws Exception {
        assertEquals("smooth",SketchHandAnimation.defaults().getString("renderStyle"));JSONObject legacy=SketchHandAnimation.defaults();legacy.remove("renderStyle");String before=SketchDocument.canonical(legacy);
        JSONObject normalized=SketchHandAnimation.settings(legacy);assertEquals("smooth",normalized.getString("renderStyle"));assertFalse(legacy.has("renderStyle"));assertEquals(before,SketchDocument.canonical(legacy));
    }
    @Test public void nativeStyleValidationIsStrictAndIndependentOfDetail()throws Exception {
        for(String style:new String[]{"smooth","cel"})for(String detail:new String[]{"anatomical","contour"}){
            JSONObject input=new JSONObject().put("renderStyle",style).put("detail",detail),settings=SketchHandAnimation.settings(input);assertEquals(style,settings.getString("renderStyle"));assertEquals(detail,settings.getString("detail"));
        }
        for(Object style:new Object[]{"Cel","flat","",JSONObject.NULL,true,1,new JSONArray(),new JSONObject()})assertThrows(Exception.class,()->SketchHandAnimation.settings(new JSONObject().put("renderStyle",style)));
    }
    @Test public void manualChooserDefaultsSmoothAndDispatchesBothStylesWithBothDetailModes()throws Exception {
        for(int style=0;style<2;style++)for(int detail=0;detail<2;detail++)try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject doc=document();String before=SketchDocument.canonical(doc);List<JSONObject> requests=new ArrayList<>();open(activity.get(),doc,(request,done)->{requests.add(request);done.accept(null);});View form=root();
            Spinner chooser=spinner(form,"Render style");assertEquals(2,chooser.getCount());assertEquals("smooth",chooser.getSelectedItem());chooser.setSelection(style);spinner(form,"Hand detail").setSelection(detail);ShadowLooper.idleMainLooper();
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals(1,requests.size());JSONObject operation=requests.get(0).getJSONArray("operations").getJSONObject(1),settings=operation.getJSONObject("settings");assertEquals("add_hand_animation",operation.getString("type"));assertEquals(style==0?"smooth":"cel",settings.getString("renderStyle"));assertEquals(detail==0?"anatomical":"contour",settings.getString("detail"));assertEquals(before,SketchDocument.canonical(doc));
        }
    }
    @Test public void changingOnlySurfaceStyleCancelsQueuedPreviewWithoutChangingPoseProgramme()throws Exception {
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(activity.get(),document(),(request,done)->fail("Style selection must not save"));View form=root();ExecutorService worker=(ExecutorService)field(owner,"worker");CountDownLatch entered=new CountDownLatch(1);Future<?> blocker=worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try{
                assertTrue(entered.await(10,TimeUnit.SECONDS));String before=SketchDocument.canonical(field(owner,"poses"));SketchSolidUiTest.button(form,"Preview hand").performClick();Future<?> preview=(Future<?>)field(owner,"previewTask");assertNotNull(preview);
                spinner(form,"Render style").setSelection(1);ShadowLooper.idleMainLooper();assertTrue(preview.isCancelled());assertEquals(before,SketchDocument.canonical(field(owner,"poses")));assertEquals("anatomical",spinner(form,"Hand detail").getSelectedItem());
            }finally{dialog().cancel();ShadowLooper.idleMainLooper();blocker.cancel(true);worker.shutdownNow();}
        }
    }
    private static JSONObject document()throws Exception {return SketchDocument.create(new JSONObject().put("sketchId","hand_style_ui").put("width",512).put("height",512).put("frameCount",120).put("fps",30));}
    private static Object open(Activity activity,JSONObject doc,SketchConstructionSubmit submit)throws Exception {Constructor<SketchHandDialog> constructor=SketchHandDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);constructor.setAccessible(true);Object owner=constructor.newInstance(activity,doc,submit);Method show=SketchHandDialog.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);ShadowLooper.idleMainLooper();return owner;}
    private static Object field(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View root(){return dialog().getWindow().getDecorView();}
    private static Spinner spinner(View form,String name){View view=SketchSolidUiTest.description(form,name);assertNotNull("Missing manual chooser: "+name,view);return (Spinner)view;}
}
