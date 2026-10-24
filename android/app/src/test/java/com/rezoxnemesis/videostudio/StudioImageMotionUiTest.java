package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Reachable manual editor, detached draft, ordinary project CAS and one Undo. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioImageMotionUiTest {
    @Test public void imageEntryLoadsSavedLocalKeysAndSamplesWithoutChangingArtworkOrProject()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);
        MainActivity activity=activity(store,p);String before=p.toJson().toString();
        AlertDialog manager=open(activity,p.studioLayers.get(0));View form=manager.getWindow().getDecorView();
        assertNotNull(text(form,"Key at 0 ms"));assertNotNull(text(form,"Key at 1000 ms"));
        input(form,"Overlay key time (ms)").setText("500");button(form,"Load time").performClick();
        assertEquals(.2,Double.parseDouble(input(form,"Overlay motion x").getText().toString()),.000001);
        assertNotNull(text(form,"Sampled motion at 500 ms · new key"));
        input(form,"Overlay motion x").setText("0.6");button(form,"Add / update key").performClick();
        assertNotNull(text(form,"Key at 500 ms"));assertEquals(before,p.toJson().toString());
        assertEquals(before,store.get(p.id).toJson().toString());
        manager.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(before,store.get(p.id).toJson().toString());close(store);
    }

    @Test public void manualDraftCrudAppliesOneSharedLayerSaveAndOrdinaryUndoRedo()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);
        MainActivity activity=activity(store,p);long revision=p.updatedAt;String original=p.studioLayers.get(0).toJson().toString();
        AlertDialog manager=open(activity,p.studioLayers.get(0));View form=manager.getWindow().getDecorView();
        input(form,"Overlay key time (ms)").setText("400");button(form,"Load time").performClick();
        input(form,"Overlay motion x").setText("0.25");button(form,"Add / update key").performClick();
        input(form,"Overlay motion x").setText("0.3");button(form,"Add / update key").performClick();
        assertEquals(revision,store.get(p.id).updatedAt);assertNotNull(text(form,"Key at 400 ms"));
        button(form,"Remove key at entered time").performClick();assertNull(text(form,"Key at 400 ms"));
        input(form,"Overlay motion x").setText("0.25");button(form,"Add / update key").performClick();
        manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse("A successful one-transaction application closes the draft",manager.isShowing());
        ProjectStore.Project saved=store.get(p.id);JSONArray keys=saved.studioLayers.get(0).toJson().getJSONArray("motionKeyframes");
        assertEquals(3,keys.length());assertEquals(400,keys.getJSONObject(1).getLong("timeMs"));assertEquals(.25,keys.getJSONObject(1).getDouble("x"),0);
        assertEquals(p.clips.get(0).assetId,saved.clips.get(0).assetId);assertEquals(4000,saved.outputDurationMs());
        assertEquals(p.studioLayers.get(1).toJson().toString(),saved.studioLayers.get(1).toJson().toString());
        assertEquals(p.studioLayers.get(0).x,saved.studioLayers.get(0).x,0);assertEquals(p.studioLayers.get(0).opacity,saved.studioLayers.get(0).opacity,0);
        invoke(activity,"restoreStudioHistory",new Class<?>[]{boolean.class},false);
        assertEquals(original,store.get(p.id).studioLayers.get(0).toJson().toString());
        invoke(activity,"restoreStudioHistory",new Class<?>[]{boolean.class},true);
        assertEquals(3,store.get(p.id).studioLayers.get(0).toJson().getJSONArray("motionKeyframes").length());close(store);
    }

    @Test public void switchedProjectCannotReceiveAnOldImageMotionDraftEvenAtSameRevision()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);
        MainActivity activity=activity(store,p);AlertDialog manager=open(activity,p.studioLayers.get(0));
        ProjectStore.Project other=project(store);other.updatedAt=p.updatedAt;field(activity,"activeProject",other);
        String first=store.get(p.id).toJson().toString(),second=store.get(other.id).toJson().toString();
        input(manager.getWindow().getDecorView(),"Overlay motion x").setText("0.75");
        manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue("Stale draft must remain reviewable",manager.isShowing());
        assertEquals(first,store.get(p.id).toJson().toString());assertEquals(second,store.get(other.id).toJson().toString());
        manager.dismiss();close(store);
    }

    @Test public void externallyChangedProjectRejectsDraftAndRetainsEnteredValues()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);
        MainActivity activity=activity(store,p);AlertDialog manager=open(activity,p.studioLayers.get(0));
        ProjectStore.Project external=store.get(p.id);external.name="New agent edit";store.save(external);
        View form=manager.getWindow().getDecorView();input(form,"Overlay motion x").setText("0.65");
        manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(manager.isShowing());assertEquals("0.65",input(form,"Overlay motion x").getText().toString());
        assertEquals("New agent edit",store.get(p.id).name);assertEquals(2,store.get(p.id).studioLayers.get(0).toJson().getJSONArray("motionKeyframes").length());
        manager.dismiss();close(store);
    }

    private static AlertDialog open(MainActivity activity,StudioLayer lane)throws Exception{
        invoke(activity,"editStudioImage",new Class<?>[]{StudioLayer.class,String.class},lane,lane.assetId);
        AlertDialog image=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(image);
        Button animate=button(image.getWindow().getDecorView(),"Animate overlay");
        assertNotNull("Image overlay editing must expose native Animate overlay controls",animate);animate.performClick();
        AlertDialog manager=ShadowAlertDialog.getLatestAlertDialog();ShadowLooper.idleMainLooper();assertNotSame(image,manager);assertTrue(manager.isShowing());return manager;
    }
    private static MainActivity activity(ProjectStore store,ProjectStore.Project p)throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).get();field(a,"store",store);field(a,"activeProject",p);field(a,"selectedClip",p.clips.get(0));
        field(a,"prefs",RuntimeEnvironment.getApplication().getSharedPreferences("image-motion-test",0));field(a,"content",new FrameLayout(a));field(a,"currentScreen","editor");return a;
    }
    private static ProjectStore.Project project(ProjectStore store)throws Exception{
        ProjectStore.Project p=store.create("Overlay motion UI");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-image";asset.mime="image/png";asset.name="Own panel";asset.uri="file:///unused.png";p.assets.add(asset);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="base";c.assetId=asset.id;c.outMs=4000;p.clips.add(c);
        StudioLayer lane=StudioLayer.fromJson(new JSONObject().put("id","panel").put("type","image").put("assetId",asset.id)
                .put("startMs",500).put("durationMs",1000).put("x",.4).put("y",.6).put("width",.3).put("height",.5).put("rotation",12).put("opacity",.8)
                .put("motionKeyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("x",0).put("easing","ease_in"))
                        .put(new JSONObject().put("timeMs",1000).put("x",.8).put("easing","hold"))));
        p.studioLayers.add(lane);StudioLayer title=new StudioLayer();title.id="title";title.text="Keep caption";title.durationMs=1000;p.studioLayers.add(title);store.save(p);return p;
    }
    private static void field(Object object,String name,Object value)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static void invoke(Object object,String name,Class<?>[] types,Object... values)throws Exception{java.lang.reflect.Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(object,values);}
    private static void close(ProjectStore store)throws Exception{java.lang.reflect.Field f=ProjectStore.class.getDeclaredField("db");f.setAccessible(true);((android.database.sqlite.SQLiteDatabase)f.get(store)).close();}
    private static EditText input(View v,String name){if(v instanceof EditText&&name.contentEquals(v.getContentDescription()))return (EditText)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText e=input(g.getChildAt(i),name);if(e!=null)return e;}}return null;}
    private static TextView text(View v,String value){if(v instanceof TextView&&value.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView t=text(g.getChildAt(i),value);if(t!=null)return t;}}return null;}
    private static Button button(View v,String value){TextView t=text(v,value);return t instanceof Button?(Button)t:null;}
}
