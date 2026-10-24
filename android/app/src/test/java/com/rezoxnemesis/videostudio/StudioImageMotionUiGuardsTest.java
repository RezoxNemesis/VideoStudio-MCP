package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Additional lifecycle/clear/legacy controls; original four RED tests remain frozen. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE,qualifiers="w720dp-h1440dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioImageMotionUiGuardsTest {
    @Test public void openingAndApplyingImageMotionRetainsThePreciseSavedStaticBase()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);StudioLayer image=p.studioLayers.get(0);
        image.x=.314159f;image.y=.726235f;image.width=.333333f;image.height=.456789f;image.rotation=-27.125f;image.opacity=.734567f;store.save(p);
        float[] base={image.x,image.y,image.width,image.height,image.rotation,image.opacity};String keys=image.toJson().getJSONArray("motionKeyframes").toString();MainActivity a=activity(store,p);
        AlertDialog manager=open(a,image);manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();StudioLayer saved=store.get(p.id).studioLayers.get(0);
        assertArrayEquals("Opening motion controls must not quantize the existing static image placement",base,
                new float[]{saved.x,saved.y,saved.width,saved.height,saved.rotation,saved.opacity},0);
        assertEquals(keys,saved.toJson().getJSONArray("motionKeyframes").toString());manager.dismiss();close(store);
    }

    @Test public void invalidEnteredTimeCannotClearTheDraftBeforeShowingAnError()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).get();int[] saves={0};
        AlertDialog manager=StudioImageMotionDialog.show(a,image(),0,candidate->{saves[0]++;return true;});ShadowLooper.idleMainLooper();View form=manager.getWindow().getDecorView();
        for(String invalid:new String[]{"-1","1001","invalid"}){
            input(form,"Overlay key time (ms)").setText(invalid);button(form,"Clear draft keys").performClick();
            assertNotNull("Failed clear must retain the first draft key",text(form,"Key at 0 ms"));
            assertNotNull("Failed clear must retain the last draft key",text(form,"Key at 1000 ms"));
            assertNotNull(input(form,"Overlay key time (ms)").getError());
        }
        assertEquals(0,saves[0]);manager.dismiss();
    }

    @Test public void sameProjectNavigationInvalidatesAnOldDraftEvenWhenRevisionIsUnchanged()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);MainActivity a=activity(store,p);
        AlertDialog manager=open(a,p.studioLayers.get(0));View form=manager.getWindow().getDecorView();
        input(form,"Overlay key time (ms)").setText("300");button(form,"Load time").performClick();input(form,"Overlay motion x").setText("0.4");button(form,"Add / update key").performClick();
        invoke(a,"setScreen",new Class<?>[]{View.class,String.class},new FrameLayout(a),"home");
        invoke(a,"setScreen",new Class<?>[]{View.class,String.class},new FrameLayout(a),"editor");
        manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(manager.isShowing());
        assertEquals(p.updatedAt,store.get(p.id).updatedAt);assertEquals(2,store.get(p.id).studioLayers.get(0).toJson().getJSONArray("motionKeyframes").length());manager.dismiss();close(store);
    }

    @Test public void closedFinishedAndAlreadyAppliedManagersCannotSubmitAgain()throws Exception{
        for(int state=0;state<3;state++){
            MainActivity a=Robolectric.buildActivity(MainActivity.class).get();int[] saves={0};
            AlertDialog manager=StudioImageMotionDialog.show(a,image(),0,candidate->{saves[0]++;return true;});ShadowLooper.idleMainLooper();
            Button apply=manager.getButton(AlertDialog.BUTTON_POSITIVE);View form=manager.getWindow().getDecorView();
            if(state==0)manager.dismiss();else if(state==1)a.finish();else apply.performClick();
            button(form,"Add / update key").performClick();apply.performClick();
            assertEquals("A closed/finished/already-applied manager must not submit a later click",state==2?1:0,saves[0]);manager.dismiss();
        }
    }

    @Test public void clearingKeysIsDraftOnlyUntilOneApplyAndUndoRestoresTheOriginalRecipe()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);MainActivity a=activity(store,p);
        String original=p.studioLayers.get(0).toJson().toString();AlertDialog manager=open(a,p.studioLayers.get(0));View form=manager.getWindow().getDecorView();
        input(form,"Overlay key time (ms)").setText("500");button(form,"Load time").performClick();capture(manager);
        button(form,"Clear draft keys").performClick();assertEquals(2,store.get(p.id).studioLayers.get(0).toJson().getJSONArray("motionKeyframes").length());
        assertEquals(0,Double.parseDouble(input(form,"Overlay motion x").getText().toString()),0);
        assertEquals(1,Double.parseDouble(input(form,"Overlay motion scaleX").getText().toString()),0);
        manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(manager.isShowing());
        JSONObject cleared=store.get(p.id).studioLayers.get(0).toJson();assertTrue(cleared.has("motionKeyframes"));assertEquals(0,cleared.getJSONArray("motionKeyframes").length());
        invoke(a,"restoreStudioHistory",new Class<?>[]{boolean.class},false);assertEquals(original,store.get(p.id).studioLayers.get(0).toJson().toString());close(store);
    }

    @Test public void ordinaryStaticImageEditsPreserveTheCompleteExistingMotionRecipe()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);MainActivity a=activity(store,p);
        String keys=p.studioLayers.get(0).toJson().getJSONArray("motionKeyframes").toString();
        invoke(a,"editStudioImage",new Class<?>[]{StudioLayer.class,String.class},p.studioLayers.get(0),"owned-image");
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ShadowLooper.idleMainLooper();ArrayList<EditText> numeric=new ArrayList<>();inputs(dialog.getWindow().getDecorView(),numeric);
        assertEquals("Start, duration and six static geometry fields",8,numeric.size());numeric.get(2).setText("0.7");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(dialog.isShowing());
        StudioLayer saved=store.get(p.id).studioLayers.get(0);assertEquals(.7,saved.x,.000001);assertEquals(keys,saved.toJson().getJSONArray("motionKeyframes").toString());assertEquals(3000,store.get(p.id).outputDurationMs());close(store);
    }

    private static StudioLayer image()throws Exception{
        return StudioLayer.fromJson(new JSONObject().put("id","panel").put("type","image").put("assetId","owned-image").put("startMs",400).put("durationMs",1000)
                .put("motionKeyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("x",0).put("easing","ease_in"))
                        .put(new JSONObject().put("timeMs",1000).put("x",.8).put("easing","hold"))));
    }
    private static ProjectStore.Project project(ProjectStore store)throws Exception{
        ProjectStore.Project p=store.create("Image motion guards");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-image";asset.name="Panel";asset.uri="file:///unused.png";asset.mime="image/png";p.assets.add(asset);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="clip";c.assetId=asset.id;c.outMs=3000;p.clips.add(c);p.studioLayers.add(image());store.save(p);return p;
    }
    private static MainActivity activity(ProjectStore store,ProjectStore.Project p)throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).get();field(a,"store",store);field(a,"activeProject",p);field(a,"selectedClip",p.clips.get(0));field(a,"prefs",RuntimeEnvironment.getApplication().getSharedPreferences("image-motion-guards",0));field(a,"content",new FrameLayout(a));field(a,"currentScreen","editor");return a;
    }
    private static AlertDialog open(MainActivity a,StudioLayer lane)throws Exception{
        invoke(a,"editStudioImage",new Class<?>[]{StudioLayer.class,String.class},lane,lane.assetId);AlertDialog original=ShadowAlertDialog.getLatestAlertDialog();Button animate=button(original.getWindow().getDecorView(),"Animate overlay");assertNotNull(animate);animate.performClick();AlertDialog manager=ShadowAlertDialog.getLatestAlertDialog();ShadowLooper.idleMainLooper();assertNotSame(original,manager);return manager;
    }
    private static void capture(AlertDialog dialog)throws Exception{
        String output=System.getenv("VIDEOSTUDIO_NATIVE_IMAGE_MOTION_UI");if(output==null||!output.startsWith("/"))return;
        File directory=new File(output);if(!directory.mkdirs()&&!directory.isDirectory())throw new java.io.IOException("Cannot retain native image motion UI capture");
        View root=dialog.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));root.layout(0,0,720,1440);
        input(root,"Overlay key time (ms)").requestRectangleOnScreen(new android.graphics.Rect(0,0,300,300),true);
        Bitmap bitmap=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(new File(directory,"native-image-overlay-motion-manual-ui.png"))){root.draw(new Canvas(bitmap));if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("Native UI PNG encoding failed");}finally{bitmap.recycle();}
    }
    private static void field(Object o,String name,Object value)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void invoke(Object o,String name,Class<?>[] types,Object... values)throws Exception{java.lang.reflect.Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,values);}
    private static void close(ProjectStore s)throws Exception{java.lang.reflect.Field f=ProjectStore.class.getDeclaredField("db");f.setAccessible(true);((android.database.sqlite.SQLiteDatabase)f.get(s)).close();}
    private static void inputs(View v,ArrayList<EditText> found){if(v instanceof EditText)found.add((EditText)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)inputs(g.getChildAt(i),found);}}
    private static EditText input(View v,String name){if(v instanceof EditText&&name.contentEquals(v.getContentDescription()))return (EditText)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText e=input(g.getChildAt(i),name);if(e!=null)return e;}}return null;}
    private static TextView text(View v,String value){if(v instanceof TextView&&value.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView t=text(g.getChildAt(i),value);if(t!=null)return t;}}return null;}
    private static Button button(View v,String value){TextView t=text(v,value);return t instanceof Button?(Button)t:null;}
}
