package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.*;
import android.view.View;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchEyeAnimationUiTest {
    @Test public void humanCanAddEditAndDeletePoseKeysWithoutWritingJson()throws Exception {
        try(ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
            JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","manual_keys").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));JSONObject[] result={null};
            SketchEyeDialog.show(a.get(),doc,r->result[0]=r);ShadowLooper.idleMainLooper();AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();View view=d.getWindow().getDecorView();
            EditText frame=(EditText)SketchSolidUiTest.description(view,"New key frame (1 based)");frame.setText("61");SketchSolidUiTest.button(view,"Add / select key").performClick();ShadowLooper.idleMainLooper();
            ((SeekBar)SketchSolidUiTest.description(view,"Left eyelid opening")).setProgress(35);
            frame.setText("81");SketchSolidUiTest.button(view,"Add / select key").performClick();ShadowLooper.idleMainLooper();SketchSolidUiTest.button(view,"Delete key").performClick();ShadowLooper.idleMainLooper();
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(result[0]);JSONArray keys=result[0].getJSONArray("operations").getJSONObject(1).getJSONArray("poses");
            boolean edited=false;for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i);assertNotEquals("Deleted key must not remain",80,key.getInt("frame"));if(key.getInt("frame")==60){edited=true;assertEquals(.35,key.getDouble("leftOpen"),0);}}
            assertTrue("Newly authored pose is retained",edited);assertEquals(0,keys.getJSONObject(0).getInt("frame"));
        }
    }
    @Test public void manualEyeControlEditsAPoseAndMatchesNativeAgentGeometry()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=SketchEyeAnimationTest.create(c,120,30);
        try(ActivityController<SketchStudioActivity> a=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).setup()){
            SketchStudioActivity studio=a.get();SketchSolidUiTest.flush(studio);open(studio);AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();
            View decor=d.getWindow().getDecorView();SeekBar gaze=(SeekBar)SketchSolidUiTest.description(decor,"Horizontal gaze");assertNotNull(gaze);gaze.setProgress(170);
            ((EditText)SketchSolidUiTest.description(decor,"Iris colour")).setText("#FF8C7744");
            Button preview=SketchSolidUiTest.button(decor,"Preview pose");assertNotNull("Manual users need a native preview before committing",preview);preview.performClick();
            ImageView image=(ImageView)SketchSolidUiTest.description(decor,"Eye pose preview");assertNotNull(image);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
            while(image.getDrawable()==null&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper();Thread.sleep(20);}
            assertNotNull("Native preview must finish",image.getDrawable());
            SketchActionFilmTest.png(new java.io.File(SketchEyeAnimationTest.evidence("manual"),"native-pose-preview.png"),((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap());
            try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals("Preview cannot mutate the user's drawing",1,store.get(id).getInt("revision"));}
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();SketchSolidUiTest.flush(studio);
            try(SketchProjectStore store=new SketchProjectStore(c)){
                JSONObject saved=store.get(id);assertEquals(2,saved.getInt("revision"));JSONArray actual=saved.getJSONArray("layers").getJSONObject(1).getJSONArray("objects");
                String eyeId=actual.getJSONObject(0).getString("id").split("_left_f")[0];
                JSONObject expected=SketchDocument.create(new JSONObject().put("sketchId","expected").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
                JSONArray poses=SketchEyeAnimation.preset(expected,"look_blink");poses.getJSONObject(0).put("gazeX",.7);
                SketchEyeAnimation.apply(expected,new JSONObject().put("type","add_eye_animation").put("layerId","layer_1").put("eyeId",eyeId)
                        .put("preset","look_blink").put("settings",new JSONObject().put("irisColor","#FF8C7744")).put("poses",poses));
                assertEquals(SketchDocument.canonical(new JSONArray(expected.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").toString())),SketchDocument.canonical(actual));
            }
            capture(studio,"manual",45);
        }
    }
    static void open(SketchStudioActivity studio)throws Exception {
        java.lang.reflect.Method m=SketchStudioActivity.class.getDeclaredMethod("brushMenu");m.setAccessible(true);m.invoke(studio);
        ListView list=ShadowAlertDialog.getLatestAlertDialog().getListView();int selected=-1;
        for(int i=0;i<list.getAdapter().getCount();i++)if("Eye animation".equals(list.getAdapter().getItem(i).toString()))selected=i;
        assertTrue("Eye animation must be in the real manual menu",selected>=0);list.performItemClick(null,selected,selected);
    }
    static void capture(SketchStudioActivity studio,String name,int frame)throws Exception {
        java.lang.reflect.Method set=SketchStudioActivity.class.getDeclaredMethod("setFrame",int.class);set.setAccessible(true);set.invoke(studio,frame);SketchSolidUiTest.flush(studio);
        View root=studio.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(1080,1920,android.graphics.Bitmap.Config.ARGB_8888);
        try{
            root.draw(new android.graphics.Canvas(image));java.lang.reflect.Field cf=SketchStudioActivity.class.getDeclaredField("canvas");cf.setAccessible(true);Object canvas=cf.get(studio);
            java.lang.reflect.Field worker=SketchCanvasView.class.getDeclaredField("rasterWorker");worker.setAccessible(true);((java.util.concurrent.ExecutorService)worker.get(canvas)).submit(()->{}).get(30,java.util.concurrent.TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();root.draw(new android.graphics.Canvas(image));int ink=0;
            for(int y=650;y<1240;y++)for(int x=20;x<1060;x++){int color=image.getPixel(x,y);if(android.graphics.Color.blue(color)<110&&android.graphics.Color.red(color)<110)ink++;}
            assertTrue("Actual Activity must display the eye drawing",ink>100);
            SketchActionFilmTest.png(new java.io.File(SketchEyeAnimationTest.evidence(name),"editor-frame-"+frame+".png"),image);
        }finally{image.recycle();}
    }
}
