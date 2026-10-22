package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** The existing manual Copy→next menu must use the same verified worker copy as MCP. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE,qualifiers="w720dp-h1440dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchAuthoredCelReuseUiTest {
    @Test public void framesMenuCopiesProtectedAuthoredMarksAndKeepsTheNativeEditorUsable()throws Exception {
        SketchAuthoredCelReuseTest core=new SketchAuthoredCelReuseTest();ExecutorService worker=Executors.newSingleThreadExecutor();
        SketchAuthoredCelReuseTest.Fixture fixture;
        try{fixture=worker.submit(()->core.new Fixture(false)).get(90,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
        JSONObject before=fixture.doc();String guide=SketchDocument.canonical(SketchDocument.layer(before,"layer_1"));
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(core.context,SketchStudioActivity.class).putExtra("sketchId",fixture.id)).create().start().resume().visible()){
            SketchStudioActivity studio=controller.get();waitIo(studio);SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");
            @SuppressWarnings("unchecked") List<String> layers=(List<String>)field(studio,"layerIds");
            ((Spinner)field(studio,"layers")).setSelection(layers.indexOf("actor_construction"));ShadowLooper.idleMainLooper();assertEquals("actor_construction",canvas.getLayerId());
            View root=studio.getWindow().getDecorView();Button more=button(root,"More");assertNotNull(more);more.performClick();choose("Frames / Cels");choose("Copy → next");
            SketchEditorSession session=(SketchEditorSession)field(studio,"session");session.awaitIdle(60000);waitIo(studio);
            assertNull("A source-free authored copy must not leave a failed local draft",session.error());JSONObject saved=fixture.doc();
            assertEquals(before.getLong("revision")+1,saved.getLong("revision"));assertEquals(guide,SketchDocument.canonical(SketchDocument.layer(saved,"layer_1")));assertEquals(1,canvas.getFrame());
            JSONArray objects=SketchDocument.layer(saved,"actor_construction").getJSONArray("objects");boolean frozen=false;for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);frozen|=object.getInt("frame")==1&&object.getInt("exposure")==1&&!"ink".equals(object.getString("id"));}assertTrue("Ordinary menu creates an independent next-cel drawing",frozen);
            assertArrayEquals(core.pixels(before,0),core.pixels(saved,1));
            String output=System.getenv("VIDEOSTUDIO_AUTHORED_CEL_REUSE_UI");
            if(output!=null){File directory=new File(output);assertTrue(directory.mkdirs()||directory.isDirectory());for(int attempt=0;attempt<8;attempt++){Bitmap sample=capture(root);sample.recycle();((ExecutorService)field(canvas,"rasterWorker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();assertFalse(canvas.previewFailed());if(((Integer)field(canvas,"lastRasterFrame"))==1)break;}assertEquals(1,field(canvas,"lastRasterFrame"));Bitmap screenshot=capture(root);try(FileOutputStream out=new FileOutputStream(new File(directory,"native-authored-copy-editor-frame-0001.png"))){assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,out));}finally{screenshot.recycle();}}
        }
    }
    static void choose(String text){AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);ListView list=dialog.getListView();for(int i=0;i<list.getAdapter().getCount();i++)if(text.equals(list.getAdapter().getItem(i).toString())){list.performItemClick(list.getChildAt(i),i,list.getAdapter().getItemId(i));return;}fail("Missing ordinary menu item: "+text);}
    static void waitIo(SketchStudioActivity studio)throws Exception{((ExecutorService)field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    static Button button(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button result=button(group.getChildAt(i),text);if(result!=null)return result;}}return null;}
    static Bitmap capture(View root){root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));root.layout(0,0,720,1440);Bitmap bitmap=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));return bitmap;}
}
