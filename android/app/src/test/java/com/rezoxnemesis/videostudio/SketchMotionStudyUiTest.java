package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.*;
import android.widget.EditText;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchMotionStudyUiTest {
    @Test public void manualControlPublishesTheSameSceneAsNativeAgentDispatch()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=SketchMotionStudiesTest.create(c,120,30);
        try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).setup()){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);
            openPicker(studio);
            AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(d);
            assertEquals("add_motion_study",SketchCapabilities.get().getJSONObject("motionStudies").getString("operation"));
            EditText travel=(EditText)SketchSolidUiTest.description(d.getWindow().getDecorView(),"Travel (% width)");assertNotNull(travel);travel.setText("12");
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
            try(SketchProjectStore store=new SketchProjectStore(c)){
                JSONObject saved=store.get(id);assertEquals(2,saved.getInt("revision"));assertEquals(2,saved.getJSONArray("layers").length());
                assertEquals(120,saved.getInt("frameCount"));assertEquals(30,saved.getInt("fps"));
                JSONArray actual=saved.getJSONArray("layers").getJSONObject(1).getJSONArray("objects");
                String prefix=actual.getJSONObject(0).getString("id").replace("_floor","");
                JSONObject expected=SketchMotionStudies.build(saved,"cartoon_jump",prefix,new JSONObject().put("travel",.12));
                assertEquals(SketchDocument.canonical(new JSONArray(expected.getJSONObject("document").getJSONArray("layers").getJSONObject(0).getJSONArray("objects").toString())),
                        SketchDocument.canonical(actual));
                capture(studio,"cartoon_jump",50);
            }
        }
    }
    @Test public void characterStudyCanBeCreatedFromTheActualManualPicker()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=SketchMotionStudiesTest.create(c,120,30);
        try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).setup()){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);
            openPicker(studio);
            AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();
            ((android.widget.Spinner)SketchSolidUiTest.description(d.getWindow().getDecorView(),"Motion study")).setSelection(1);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
            try(SketchProjectStore store=new SketchProjectStore(c)){
                JSONObject saved=store.get(id);assertEquals(2,saved.getInt("revision"));
                JSONArray actual=saved.getJSONArray("layers").getJSONObject(1).getJSONArray("objects");
                String prefix=actual.getJSONObject(0).getString("id").replace("_floor","");
                JSONObject expected=SketchMotionStudies.build(saved,"walk_to_run",prefix,new JSONObject());
                assertEquals(SketchDocument.canonical(new JSONArray(expected.getJSONObject("document").getJSONArray("layers").getJSONObject(0).getJSONArray("objects").toString())),SketchDocument.canonical(actual));
            }
            capture(studio,"walk_to_run",75);
        }
    }
    private static void openPicker(SketchStudioActivity studio)throws Exception {
        java.lang.reflect.Method method=SketchStudioActivity.class.getDeclaredMethod("brushMenu");method.setAccessible(true);method.invoke(studio);
        AlertDialog menu=ShadowAlertDialog.getLatestAlertDialog();android.widget.ListView list=menu.getListView();int selected=-1;
        for(int i=0;i<list.getAdapter().getCount();i++)if("Motion studies".equals(list.getAdapter().getItem(i).toString()))selected=i;
        assertTrue("The actual Brush / Text menu must expose Motion studies",selected>=0);list.performItemClick(null,selected,selected);
    }
    static void capture(SketchStudioActivity studio,String study,int frame)throws Exception {
        java.lang.reflect.Method set=SketchStudioActivity.class.getDeclaredMethod("setFrame",int.class);set.setAccessible(true);set.invoke(studio,frame);SketchSolidUiTest.flush(studio);
        android.view.View decor=studio.getWindow().getDecorView();decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY));decor.layout(0,0,1080,1920);
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(1080,1920,android.graphics.Bitmap.Config.ARGB_8888);
        try{
            decor.draw(new android.graphics.Canvas(image));
            // The real large-scene viewport publishes asynchronously after its first draw.
            java.lang.reflect.Field cf=SketchStudioActivity.class.getDeclaredField("canvas");cf.setAccessible(true);Object canvas=cf.get(studio);
            java.lang.reflect.Field worker=SketchCanvasView.class.getDeclaredField("rasterWorker");worker.setAccessible(true);
            ((java.util.concurrent.ExecutorService)worker.get(canvas)).submit(()->{}).get(30,java.util.concurrent.TimeUnit.SECONDS);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();decor.draw(new android.graphics.Canvas(image));
            int orange=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int color=image.getPixel(x,y);if(android.graphics.Color.red(color)>180&&android.graphics.Color.green(color)>70&&android.graphics.Color.green(color)<190&&android.graphics.Color.blue(color)<120)orange++;}
            assertTrue("Actual editor screenshot must display the authored artwork",orange>500);
            SketchActionFilmTest.png(new java.io.File(SketchSolidShadowClearanceTest.evidence(study),"editor-frame-"+frame+".png"),image);
        }finally{image.recycle();}
    }
}
