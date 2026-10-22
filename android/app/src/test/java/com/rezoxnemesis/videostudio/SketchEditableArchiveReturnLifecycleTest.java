package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real archive import and activity result/resume; deterministic worker ordering, no private state writes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchEditableArchiveReturnLifecycleTest {
    final Context context=RuntimeEnvironment.getApplication();
    final class Fixture {
        final String current="current_"+UUID.randomUUID(),other="other_"+UUID.randomUUID();
        final String currentName="Current drawing "+UUID.randomUUID(),otherName="Other drawing "+UUID.randomUUID();
        final Uri uri=Uri.parse("content://owned.documents/"+UUID.randomUUID()+".vssketch");
        final Set<String> beforeIds=new HashSet<>();final String currentBefore,otherBefore;
        Fixture()throws Exception {
            SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",current).put("operationId","create_current").put("name",currentName));
            SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",other).put("operationId","create_other").put("name",otherName));
            JSONObject source=SketchLocalArchiveTest.source(context);SketchPaintArchive.Prepared archive=SketchLocalArchiveTest.pack(context,source);
            Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri,new FileInputStream(archive.file));
            try(SketchProjectStore store=new SketchProjectStore(context)){JSONArray rows=store.list();for(int i=0;i<rows.length();i++)beforeIds.add(rows.getJSONObject(i).getString("sketchId"));currentBefore=SketchDocument.canonical(store.get(current));otherBefore=SketchDocument.canonical(store.get(other));}
        }
        String imported()throws Exception {
            try(SketchProjectStore store=new SketchProjectStore(context)){JSONArray rows=store.list();String found=null;for(int i=0;i<rows.length();i++){String id=rows.getJSONObject(i).getString("sketchId");if(!beforeIds.contains(id)){assertNull("Only one new editable sketch is restored",found);found=id;}}assertNotNull("Import must have persisted a new editable drawing",found);assertEquals(325,store.get(found).getInt("frameCount"));assertEquals(30,store.get(found).getInt("fps"));assertEquals(currentBefore,SketchDocument.canonical(store.get(current)));assertEquals(otherBefore,SketchDocument.canonical(store.get(other)));return found;}
        }
    }
    Fixture fixture()throws Exception {ExecutorService worker=Executors.newSingleThreadExecutor();try{return worker.submit(()->new Fixture()).get(60,TimeUnit.SECONDS);}finally{worker.shutdownNow();}}
    ActivityController<SketchStudioActivity> open(Fixture f)throws Exception {
        ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",f.current));
        controller.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",true).putBoolean("canvasFocus",false).commit();
        controller.create().start().resume().visible();settle(controller.get());assertEquals(f.current,field(controller.get(),"sketchId"));assertEquals(Boolean.FALSE,field(controller.get(),"loadingDrawing"));return controller;
    }
    void chooseImport(SketchStudioActivity studio)throws Exception {
        assertEquals(Boolean.FALSE,field(studio,"watchInFlight"));click(studio,"File");choose("Import editable animation");
        Intent picker=Shadows.shadowOf(studio).getNextStartedActivity();assertNotNull("The ordinary File control launches Android Files",picker);assertEquals(Intent.ACTION_OPEN_DOCUMENT,picker.getAction());assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE));
    }
    void result(SketchStudioActivity studio,Fixture f)throws Exception {
        Field code=SketchStudioActivity.class.getDeclaredField("LOCAL_EDITABLE_ARCHIVE");code.setAccessible(true);
        studio.onActivityResult(code.getInt(null),Activity.RESULT_OK,new Intent().setData(f.uri));
    }
    @Test public void normalFilesReturnOpensItsPersistedArchiveWithoutChangingThePriorDrawing()throws Exception {
        Fixture f=fixture();try(ActivityController<SketchStudioActivity> controller=open(f)){SketchStudioActivity studio=controller.get();chooseImport(studio);controller.pause().stop();controller.restart().start();result(studio,f);controller.resume().visible();settle(studio);assertEquals("A successful import should open its own new drawing",f.imported(),field(studio,"sketchId"));}
    }
    @Test public void aFollowPollBetweenPickerLaunchAndPauseMustNotInvalidateTheSameDrawingImport()throws Exception {
        Fixture f=fixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityController<SketchStudioActivity> controller=open(f)){
            SketchStudioActivity studio=controller.get();chooseImport(studio);
            // Android delivers onPause as a later main message. A queued native
            // follow poll may start in that interval while its database worker is busy.
            ((ExecutorService)field(studio,"io")).execute(()->{entered.countDown();try{assertTrue(release.await(60,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));ShadowLooper.idleMainLooper(250,TimeUnit.MILLISECONDS);
            assertEquals("Actual native watch tick is queued, without forcing a private flag",Boolean.TRUE,field(studio,"watchInFlight"));
            controller.pause().stop();controller.restart().start();result(studio,f);controller.resume().visible();release.countDown();settle(studio);
            assertEquals("Resuming the same unedited drawing must not discard the successful Files selection",f.imported(),field(studio,"sketchId"));
        }finally{release.countDown();}
    }
    @Test public void deliberateNavigationWhileTheArchiveCopiesMustKeepTheChosenDrawingOpen()throws Exception {
        Fixture f=fixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityController<SketchStudioActivity> controller=open(f)){
            SketchStudioActivity studio=controller.get();chooseImport(studio);
            ((ExecutorService)field(studio,"transfers")).execute(()->{entered.countDown();try{assertTrue(release.await(60,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            controller.pause().stop();controller.restart().start();result(studio,f);controller.resume().visible();
            ((ExecutorService)field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            click(studio,"File");choose("Open");choose(f.otherName);for(int i=0;i<3;i++){((ExecutorService)field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}assertEquals(f.other,field(studio,"sketchId"));
            release.countDown();settle(studio);assertNotEquals(f.imported(),field(studio,"sketchId"));assertEquals("Import must not preempt intentional navigation",f.other,field(studio,"sketchId"));
        }finally{release.countDown();}
    }
    static void settle(SketchStudioActivity studio)throws Exception {for(int i=0;i<5;i++){((ExecutorService)field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);((ExecutorService)field(studio,"transfers")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    static Object field(Object owner,String name)throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    static void click(SketchStudioActivity studio,String label){Button button=button(studio.getWindow().getDecorView(),label);assertNotNull("Ordinary control missing: "+label,button);button.performClick();}
    static Button button(View view,String label){if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=button(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    static void choose(String label){AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);ListView list=dialog.getListView();assertNotNull(list);for(int i=0;i<list.getCount();i++)if(label.equals(String.valueOf(list.getAdapter().getItem(i)))){list.performItemClick(list.getAdapter().getView(i,null,list),i,i);return;}fail("Missing ordinary menu choice: "+label);}
}
