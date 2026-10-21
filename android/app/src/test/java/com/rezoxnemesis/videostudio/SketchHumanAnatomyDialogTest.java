package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Manual style controls must share native settings and preserve existing async/pose state. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchHumanAnatomyDialogTest {
    @Test public void neutralStanceMergesPresetOverridesOnlyWhenClicked()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),(request,done)->fail("Editing must not save"));
            try{
                View form=root(owner);EditText overrides=(EditText)control(form,"Hold / gaze overrides (editable JSON)");
                overrides.setText("{\"gazeX\":0.3,\"mouth\":0.4,\"nearElbowSwivel\":12}");select(form,"Figure style","anatomy_sketch");
                JSONObject before=new JSONObject(overrides.getText().toString());assertEquals(12,before.getInt("nearElbowSwivel"));assertFalse(before.has("farElbowSwivel"));
                control(form,"Neutral anatomy stance").performClick();ShadowLooper.idleMainLooper();JSONObject merged=new JSONObject(overrides.getText().toString());
                assertEquals(.3,merged.getDouble("gazeX"),0);assertEquals(.4,merged.getDouble("mouth"),0);assertStance(merged);
                JSONObject request=(JSONObject)invoke(owner,"request");assertStance(request.getJSONArray("operations").getJSONObject(1).getJSONObject("poseOverrides"));
                String status=((TextView)control(form,"Human animation status")).getText().toString();assertTrue(status.contains("90"));assertTrue(status.contains("-90"));assertTrue(status.contains("-6"));assertTrue(status.contains("6"));
            }finally{dialog(owner).cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void neutralStanceChangesOnlyTheSelectedCustomPoseAndPreservesOtherChannels()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),(request,done)->fail("Editing must not save"));
            try{
                View form=root(owner);check(form,"Edit custom poses").setChecked(true);select(form,"Figure style","anatomy_sketch");
                EditText overrides=(EditText)control(form,"Hold / gaze overrides (editable JSON)");overrides.setText("{\"gazeY\":0.2}");
                ((EditText)control(form,"gazeX")).setText("0.7");((EditText)control(form,"handOpen")).setText("0.6");JSONArray before=new JSONArray(field(owner,"poses").toString());
                FutureTask<Void> task=new FutureTask<>(()->null);set(owner,"task",task);control(form,"Neutral anatomy stance").performClick();ShadowLooper.idleMainLooper();assertTrue(task.isCancelled());
                JSONArray after=(JSONArray)field(owner,"poses");assertEquals(before.length(),after.length());assertStance(after.getJSONObject(0));assertEquals(.7,after.getJSONObject(0).getDouble("gazeX"),0);assertEquals(.6,after.getJSONObject(0).getDouble("handOpen"),0);
                for(int i=1;i<before.length();i++)assertEquals(SketchDocument.canonical(before.getJSONObject(i)),SketchDocument.canonical(after.getJSONObject(i)));
                assertEquals("{\"gazeY\":0.2}",overrides.getText().toString());
            }finally{dialog(owner).cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void legacyOmissionAndDynamicStyleViewsUseTheSharedSettings()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),(request,done)->fail("Reading controls must not save"));
            try{
                View form=root(owner);assertEquals(6,spinner(form,"Human view").getCount());
                assertLegacyAppearanceEnabled(form,true);
                assertEquals(SketchDocument.canonical(SketchHumanFigure.defaults()),SketchDocument.canonical(new JSONObject(settings(owner).toString())));
                select(form,"Clothing","tailored");select(form,"Figure style","anatomy_sketch");
                assertEquals(8,spinner(form,"Human view").getCount());assertFalse(spinner(form,"Clothing").isEnabled());
                assertLegacyAppearanceEnabled(form,false);assertTrue(control(form,"skinColor").isEnabled());assertTrue(control(form,"hairColor").isEnabled());assertTrue(control(form,"trouserColor").isEnabled());
                CheckBox guides=check(form,"Construction guides");assertTrue(guides.isEnabled());
                select(form,"Human view","three_quarter_right");JSONObject anatomy=settings(owner);
                assertEquals("anatomy_sketch",anatomy.getString("figureStyle"));assertFalse(anatomy.getBoolean("constructionGuides"));assertFalse(anatomy.has("clothingStyle"));
                guides.setChecked(true);assertTrue(settings(owner).getBoolean("constructionGuides"));
                select(form,"Figure style","legacy");assertEquals(6,spinner(form,"Human view").getCount());assertEquals("front",spinner(form,"Human view").getSelectedItem());
                assertTrue(spinner(form,"Clothing").isEnabled());assertFalse(guides.isEnabled());
                assertLegacyAppearanceEnabled(form,true);
                JSONObject restored=settings(owner);assertFalse(restored.has("figureStyle"));assertFalse(restored.has("constructionGuides"));assertEquals("tailored",restored.getString("clothingStyle"));
            }finally{dialog(owner).cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void styleGuidesAndViewChangesPreserveUnappliedCustomPoseEdits()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),(request,done)->fail("Editing must not save"));
            try{
                View form=root(owner);check(form,"Edit custom poses").setChecked(true);
                EditText wrist=(EditText)control(form,"nearWrist");wrist.setText("37.5");
                ((EditText)control(form,"skinColor")).setText("#FFF0EBE2");((EditText)control(form,"hairColor")).setText("#FF514B45");((EditText)control(form,"trouserColor")).setText("#FFBCB4A8");
                String before=((JSONArray)field(owner,"poses")).toString();
                select(form,"Figure style","anatomy_sketch");select(form,"Human view","three_quarter_left");check(form,"Construction guides").setChecked(true);
                assertEquals("37.5",wrist.getText().toString());assertEquals(before,field(owner,"poses").toString());
                assertEquals("#FFF0EBE2",settings(owner).getString("skinColor"));assertEquals("#FF514B45",settings(owner).getString("hairColor"));assertEquals("#FFBCB4A8",settings(owner).getString("trouserColor"));
                select(form,"Figure style","legacy");assertEquals("37.5",wrist.getText().toString());assertEquals(before,field(owner,"poses").toString());
                assertEquals("#FFF0EBE2",settings(owner).getString("skinColor"));assertEquals("#FF514B45",settings(owner).getString("hairColor"));assertEquals("#FFBCB4A8",settings(owner).getString("trouserColor"));
                JSONObject request=(JSONObject)invoke(owner,"request");JSONArray ops=request.getJSONArray("operations");JSONObject human=ops.getJSONObject(1);
                assertEquals(37.5,human.getJSONArray("poses").getJSONObject(0).getDouble("nearWrist"),0);
                assertFalse(human.has("motion"));assertFalse(human.getJSONObject("settings").has("figureStyle"));
            }finally{dialog(owner).cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void newControlsInvalidateBothPreviewAndSpeechTasksAndCancelStillCloses()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(a.get(),(request,done)->fail("Invalidation must not save"));View form=root(owner);
            try{
                int generation=(int)field(owner,"generation");
                FutureTask<Void> preview=new FutureTask<>(()->null),speech=new FutureTask<>(()->null);
                set(owner,"task",preview);set(owner,"speechTask",speech);select(form,"Figure style","anatomy_sketch");
                assertTrue(preview.isCancelled());assertTrue(speech.isCancelled());assertTrue((int)field(owner,"generation")>generation);
                preview=new FutureTask<>(()->null);speech=new FutureTask<>(()->null);set(owner,"task",preview);set(owner,"speechTask",speech);
                check(form,"Construction guides").setChecked(true);assertTrue(preview.isCancelled());assertTrue(speech.isCancelled());
                dialog(owner).cancel();ShadowLooper.idleMainLooper();assertTrue((boolean)field(owner,"closed"));assertTrue(((ExecutorService)field(owner,"worker")).isShutdown());
            }finally{dialog(owner).cancel();ShadowLooper.idleMainLooper();}
        }
    }

    @Test public void asyncSaveConflictRestoresStyleConstraintsAndRetainsCustomControls()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            @SuppressWarnings("unchecked") Consumer<Exception>[] callback=new Consumer[1];
            Object owner=open(a.get(),(request,done)->callback[0]=done);
            try{
                View form=root(owner);check(form,"Edit custom poses").setChecked(true);((EditText)control(form,"nearWrist")).setText("37.5");
                select(form,"Figure style","anatomy_sketch");check(form,"Construction guides").setChecked(true);select(form,"Human view","three_quarter_right");
                AlertDialog dialog=dialog(owner);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(callback[0]);assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
                callback[0].accept(new IllegalStateException("Revision conflict"));ShadowLooper.idleMainLooper();
                assertTrue(dialog.isShowing());assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertFalse(spinner(form,"Clothing").isEnabled());
                assertLegacyAppearanceEnabled(form,false);
                assertTrue(check(form,"Construction guides").isEnabled());assertTrue(check(form,"Construction guides").isChecked());assertEquals("three_quarter_right",spinner(form,"Human view").getSelectedItem());assertEquals("37.5",((EditText)control(form,"nearWrist")).getText().toString());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();callback[0].accept(null);ShadowLooper.idleMainLooper();assertFalse(dialog.isShowing());
            }finally{dialog(owner).cancel();ShadowLooper.idleMainLooper();}
        }
    }

    private static Object open(Activity activity,SketchConstructionSubmit submit)throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","anatomy_ui").put("width",512).put("height",512).put("frameCount",120).put("fps",30));
        Constructor<SketchHumanDialog> constructor=SketchHumanDialog.class.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);constructor.setAccessible(true);Object owner=constructor.newInstance(activity,doc,submit);invoke(owner,"show");ShadowLooper.idleMainLooper();return owner;
    }
    private static JSONObject settings(Object owner)throws Exception{return (JSONObject)invoke(owner,"readSettings");}
    private static Object invoke(Object owner,String name)throws Exception{Method m=owner.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(owner);}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static AlertDialog dialog(Object owner)throws Exception{return (AlertDialog)field(owner,"dialog");}
    private static View root(Object owner)throws Exception{return dialog(owner).getWindow().getDecorView();}
    private static View find(View root,String name){if(name.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())||root instanceof Button&&name.contentEquals(((Button)root).getText()))return root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),name);if(found!=null)return found;}}return null;}
    private static void assertStance(JSONObject values)throws Exception{assertEquals(90,values.getDouble("nearElbowSwivel"),0);assertEquals(-90,values.getDouble("farElbowSwivel"),0);assertEquals(-6,values.getDouble("nearWrist"),0);assertEquals(6,values.getDouble("farWrist"),0);}
    private static void assertLegacyAppearanceEnabled(View form,boolean enabled){assertEquals(enabled,spinner(form,"Face structure").isEnabled());assertEquals(enabled,spinner(form,"Surface shading").isEnabled());assertEquals(enabled,control(form,"lightAngle").isEnabled());}
    private static View control(View root,String name){View found=find(root,name);assertNotNull("Missing control "+name,found);return found;}
    private static Spinner spinner(View root,String name){return (Spinner)control(root,name);}
    private static CheckBox check(View root,String name){return (CheckBox)control(root,name);}
    private static void select(View root,String name,String value){Spinner spinner=spinner(root,name);for(int i=0;i<spinner.getCount();i++)if(value.equals(spinner.getItemAtPosition(i))){spinner.setSelection(i);ShadowLooper.idleMainLooper();return;}throw new AssertionError("Missing option "+name+" = "+value);}
}
