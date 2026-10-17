package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStudioTimelineTest {
    @Test public void featureFilmNavigatorAndVirtualStripReachTheLastFrameAt60Fps() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id="film_"+UUID.randomUUID();
        SketchBookStore.execute(context,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","create").put("pageCount",216000).put("fps",60));
        try(ActivityController<SketchStudioActivity> controller=open(context,id)){
            SketchStudioActivity studio=controller.get();invoke(studio,"scrubDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            button(dialog.getWindow().getDecorView(),"Last").performClick();assertEquals("216000",edit(dialog,"Frame or page number").getText().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(studio);
            assertEquals(215999,field(studio,"bookPage"));
            assertTrue(((SketchFilmstripView)field(studio,"filmstrip")).getContentDescription().toString().contains("216000 of 216000"));
            assertEquals(2,SketchBookStore.execute(context,"sketch_book_get",new JSONObject().put("bookId",id)).getJSONObject("book").getInt("createdPages"));
        }
    }
    @Test public void exactNavigatorOpensPage10000WithoutAllocatingIntermediatePages() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=createBook(context);
        try(ActivityController<SketchStudioActivity> controller=open(context,id)){
            SketchStudioActivity studio=controller.get();invoke(studio,"scrubDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            button(dialog.getWindow().getDecorView(),"Last").performClick();
            assertEquals("Quick jumps preview before Go",0,field(studio,"bookPage"));
            assertEquals("10000",edit(dialog,"Frame or page number").getText().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(studio);
            assertEquals(9999,field(studio,"bookPage"));assertEquals(id+"_p9999",field(studio,"sketchId"));
            JSONObject persisted=SketchBookStore.execute(context,"sketch_book_get",new JSONObject().put("bookId",id)).getJSONObject("book");
            assertEquals("Only the two visited pages should exist",2,persisted.getInt("createdPages"));
        }
    }

    @Test public void invalidAndStaleNavigatorCannotSelectAnotherBook() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String first=createBook(context),second=createBook(context);
        try(ActivityController<SketchStudioActivity> controller=open(context,first)){
            SketchStudioActivity studio=controller.get();invoke(studio,"scrubDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            edit(dialog,"Frame or page number").setText("10001");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());assertEquals(0,field(studio,"bookPage"));
            openBook(studio,second,0);settle(studio);edit(dialog,"Frame or page number").setText("5000");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(studio);
            assertEquals(second,field(studio,"bookId"));assertEquals(0,field(studio,"bookPage"));
            assertTrue(dialog.isShowing());
        }
    }

    @Test public void exposureSheetPersistsA128PageRangeWithoutCreatingDrawings() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=createBook(context);
        try(ActivityController<SketchStudioActivity> controller=open(context,id)){
            SketchStudioActivity studio=controller.get();invoke(studio,"bookTimingDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            edit(dialog,"First exposure page").setText("9001");edit(dialog,"Last exposure page").setText("9128");
            button(dialog.getWindow().getDecorView(),"On twos").performClick();
            assertEquals("Presets must preview without committing",1,((JSONObject)field(studio,"book")).getLong("revision"));
            edit(dialog,"Book frames per second").setText("30");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(studio);
            JSONObject persisted=SketchBookStore.execute(context,"sketch_book_get",new JSONObject().put("bookId",id)).getJSONObject("book");
            assertEquals(2,persisted.getLong("revision"));assertEquals(30,persisted.getInt("fps"));assertEquals(1,persisted.getInt("createdPages"));
            assertEquals(128,persisted.getJSONObject("exposures").length());assertEquals(10128,SketchTimelineTools.totalFrames(persisted.getJSONObject("exposures"),10000));
            assertEquals(2,persisted.getJSONObject("exposures").getInt("9000"));assertEquals(2,persisted.getJSONObject("exposures").getInt("9127"));
            openBook(studio,id,9000);settle(studio);assertTrue(((SketchFilmstripView)field(studio,"filmstrip")).getContentDescription().toString().contains("exposure 2"));
        }
    }

    @Test public void exposureSheetRejectsOversizedRangeBeforeNativeMutation() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=createBook(context);
        try(ActivityController<SketchStudioActivity> controller=open(context,id)){
            SketchStudioActivity studio=controller.get();invoke(studio,"bookTimingDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            edit(dialog,"First exposure page").setText("1");edit(dialog,"Last exposure page").setText("129");edit(dialog,"Page exposure frames").setText("3");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(studio);
            assertTrue(dialog.isShowing());JSONObject persisted=SketchBookStore.execute(context,"sketch_book_get",new JSONObject().put("bookId",id)).getJSONObject("book");
            assertEquals(1,persisted.getLong("revision"));assertNull(persisted.optJSONObject("exposures"));
        }
    }

    @Test public void playbackHoldsTheStartingPageBeforeShowingTheNextDrawing() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=createBook(context);
        SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",id).put("operationId","hold").put("expectedRevision",1).put("fps",30).put("exposures",SketchTimelineTools.exposureEdits("1","1","3",10000)));
        try(ActivityController<SketchStudioActivity> controller=open(context,id)){
            SketchStudioActivity studio=controller.get();invoke(studio,"togglePlayback");settle(studio);assertEquals(0,field(studio,"bookPage"));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(99));settle(studio);assertEquals("Three frames at 30 FPS hold the first page for 100 ms",0,field(studio,"bookPage"));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1));settle(studio);assertEquals(1,field(studio,"bookPage"));
        }
    }

    private static String createBook(Context context)throws Exception{String id="book_"+UUID.randomUUID().toString();SketchBookStore.execute(context,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","create").put("pageCount",10000));return id;}
    private static ActivityController<SketchStudioActivity> open(Context context,String book)throws Exception{
        String sketch=SketchBookStore.execute(context,"sketch_book_open_page",new JSONObject().put("bookId",book).put("page",0)).getString("sketchId");
        ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",sketch));
        controller.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).remove("lastBookId").commit();
        controller.create().start().resume().visible();settle(controller.get());openBook(controller.get(),book,0);settle(controller.get());return controller;
    }
    private static void openBook(SketchStudioActivity studio,String id,int page)throws Exception{Method method=SketchStudioActivity.class.getDeclaredMethod("openBookPage",String.class,int.class);method.setAccessible(true);method.invoke(studio,id,page);}
    private static void settle(SketchStudioActivity studio)throws Exception{for(int pass=0;pass<4;pass++){((ExecutorService)field(studio,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    private static Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void invoke(Object owner,String name)throws Exception{Method method=owner.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(owner);}
    private static EditText edit(AlertDialog dialog,String description){View found=find(dialog.getWindow().getDecorView(),description,false);assertNotNull(description,found);return (EditText)found;}
    private static Button button(View root,String text){View found=find(root,text,true);assertNotNull(text,found);return (Button)found;}
    private static View find(View view,String text,boolean button){if(button&&view instanceof Button&&text.contentEquals(((Button)view).getText()))return view;if(!button&&text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){View found=find(group.getChildAt(n),text,button);if(found!=null)return found;}}return null;}
}
