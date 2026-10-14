package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioKeyframeManagerTest {
    @Test public void humanManagerListsKeysAndResamplesValuesWhenTimeChanges()throws Exception{
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();ProjectStore.Project p=project();
        p.clips.get(0).effects.put("keyframes",new JSONArray().put(key(0,0,"ease_in")).put(key(1000,1,"hold")));
        open(activity,p);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View form=dialog.getWindow().getDecorView();
        assertNotNull("Existing keys must be selectable",text(form,"Key at 0 ms"));
        assertNotNull(button(form,"Previous key"));assertNotNull(button(form,"Next key"));
        EditText time=input(form,"Keyframe time (ms)");assertNotNull(time);time.setText("500");button(form,"Load time").performClick();
        assertNotNull("Sampling state must be visible",text(form,"Sampled transform at 500 ms · new key"));
        assertEquals(.25,Double.parseDouble(input(form,"Keyframe x").getText().toString()),.000001);
        button(form,"Next key").performClick();assertEquals("1000",time.getText().toString());
        assertEquals("hold",spinner(form).getSelectedItem());
        button(form,"Previous key").performClick();assertEquals("0",time.getText().toString());
        dialog.dismiss();
    }

    @Test public void legacySplitKeysOpenWithoutRewritingAndOnlyListLocalKeys()throws Exception{
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();ProjectStore.Project p=project();ProjectStore.Clip c=p.clips.get(0);c.outMs=500;
        c.effects.put("studioKeyframeDurationMs",1000).put("studioKeyframeOffsetMs",500).put("keyframes",new JSONArray()
                .put(new JSONObject().put("t",0).put("rotation",0)).put(new JSONObject().put("t",.5).put("rotation",20))
                .put(new JSONObject().put("t",1).put("rotation",40)));
        String before=c.effects.toString();open(activity,p);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("Legacy keys must open in the manager",dialog);View form=dialog.getWindow().getDecorView();
        assertNotNull(text(form,"Key at 0 ms"));assertNotNull(text(form,"Key at 500 ms"));assertNull(text(form,"Key at 1000 ms"));
        assertEquals(before,c.effects.toString());dialog.dismiss();
    }

    @Test public void managerCrudUsesSharedIncrementalTransactionsAndResetDoesNotSave()throws Exception{
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project p=storedProject(store);long[] revision={p.updatedAt};
        java.util.ArrayList<JSONObject> sent=new java.util.ArrayList<>();
        AlertDialog dialog=StudioKeyframeEditor.show(activity,p.clips.get(0).effects,1000,250,fragment->{
            try{JSONObject operation=new JSONObject(fragment.toString()).put("clipId","key-clip");sent.add(operation);
                JSONObject saved=StudioPrecisionEdits.apply(activity,store,new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",revision[0]).put("operations",new JSONArray().put(operation)));
                revision[0]=saved.getLong("updatedAt");return true;
            }catch(Exception error){throw new AssertionError(error);}
        },ignored->{});
        View form=dialog.getWindow().getDecorView();input(form,"Keyframe x").setText("0.3");button(form,"Add / update key").performClick();
        assertTrue("Successful edits retain the manager for another key",dialog.isShowing());
        assertNotNull(text(form,"Key at 250 ms"));assertTrue(sent.get(0).has("keyframeEdits"));assertFalse(sent.get(0).has("keyframes"));
        assertEquals(.3,store.get(p.id).clips.get(0).effects.getJSONArray("keyframes").getJSONObject(0).getDouble("x"),0);
        button(form,"Reset entered values").performClick();assertEquals(1,sent.size());assertEquals("0.0",input(form,"Keyframe x").getText().toString());
        button(form,"Add / update key").performClick();assertEquals(1,store.get(p.id).clips.get(0).effects.getJSONArray("keyframes").length());
        button(form,"Delete key at entered time").performClick();assertEquals(0,store.get(p.id).clips.get(0).effects.getJSONArray("keyframes").length());
        dialog.dismiss();close(store);
    }

    @Test public void finishedActivityCannotSaveSeekOrConfirmQueuedClear()throws Exception{
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();int[] saves={0},seeks={0};
        AlertDialog dialog=StudioKeyframeEditor.show(activity,new JSONObject(),1000,0,fragment->{saves[0]++;return true;},at->seeks[0]++);
        View form=dialog.getWindow().getDecorView();button(form,"Clear all keys").performClick();AlertDialog confirmation=ShadowAlertDialog.getLatestAlertDialog();
        activity.finish();button(form,"Add / update key").performClick();button(form,"Preview loaded time").performClick();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("A finished authoring surface cannot save",0,saves[0]);assertEquals("A finished surface cannot seek native playback",0,seeks[0]);
        confirmation.dismiss();dialog.dismiss();
    }

    @Test public void rejectedStaleSaveKeepsEnteredValuesAndDoesNotAdvanceManagerState()throws Exception{
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project p=storedProject(store);long revision=p.updatedAt;java.util.concurrent.atomic.AtomicReference<Exception> rejection=new java.util.concurrent.atomic.AtomicReference<>();
        AlertDialog dialog=StudioKeyframeEditor.show(activity,p.clips.get(0).effects,1000,300,fragment->{
            try{StudioPrecisionEdits.apply(activity,store,new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",revision)
                    .put("operations",new JSONArray().put(new JSONObject(fragment.toString()).put("clipId","key-clip"))));return true;}
            catch(Exception error){rejection.set(error);return false;}
        },ignored->{});
        ProjectStore.Project external=store.get(p.id);external.clips.get(0).title="Saved by agent";store.save(external);
        View form=dialog.getWindow().getDecorView();input(form,"Keyframe x").setText("0.8");button(form,"Add / update key").performClick();
        assertTrue(rejection.get() instanceof IllegalStateException);assertTrue(dialog.isShowing());
        assertEquals("0.8",input(form,"Keyframe x").getText().toString());assertNull(text(form,"Key at 300 ms"));
        ProjectStore.Project saved=store.get(p.id);assertEquals("Saved by agent",saved.clips.get(0).title);assertFalse(saved.clips.get(0).effects.has("keyframes"));
        dialog.dismiss();close(store);
    }

    private static void open(MainActivity a,ProjectStore.Project p)throws Exception{field(a,"activeProject",p);field(a,"selectedClip",p.clips.get(0));java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("studioNumericDialog",boolean.class,boolean.class);m.setAccessible(true);m.invoke(a,true,true);}
    private static void field(Object object,String name,Object value)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static JSONObject key(long time,double x,String ease)throws Exception{return new JSONObject().put("timeMs",time).put("x",x).put("easing",ease);}
    private static ProjectStore.Project project(){ProjectStore.Project p=new ProjectStore.Project();p.id="key-project";ProjectStore.Clip c=new ProjectStore.Clip();c.id="key-clip";c.outMs=1000;p.clips.add(c);return p;}
    private static ProjectStore.Project storedProject(ProjectStore store){ProjectStore.Project p=store.create("Manager");ProjectStore.Asset a=new ProjectStore.Asset();a.id="image";a.mime="image/png";a.uri="file:///unused.png";p.assets.add(a);ProjectStore.Clip c=new ProjectStore.Clip();c.id="key-clip";c.assetId=a.id;c.outMs=1000;p.clips.add(c);store.save(p);return p;}
    private static void close(ProjectStore store)throws Exception{java.lang.reflect.Field db=ProjectStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(store)).close();}
    private static EditText input(View v,String description){if(v instanceof EditText&&description.contentEquals(v.getContentDescription()))return (EditText)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText e=input(g.getChildAt(i),description);if(e!=null)return e;}}return null;}
    private static TextView text(View v,String title){if(v instanceof TextView&&title.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView t=text(g.getChildAt(i),title);if(t!=null)return t;}}return null;}
    private static Button button(View v,String title){TextView t=text(v,title);return t instanceof Button?(Button)t:null;}
    private static Spinner spinner(View v){if(v instanceof Spinner)return (Spinner)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Spinner s=spinner(g.getChildAt(i));if(s!=null)return s;}}return null;}
}
