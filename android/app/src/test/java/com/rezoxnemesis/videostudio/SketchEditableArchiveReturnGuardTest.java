package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** A lifecycle repair must not preempt newer human artwork or another book page. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchEditableArchiveReturnGuardTest {
    final SketchEditableArchiveReturnLifecycleTest core=new SketchEditableArchiveReturnLifecycleTest();
    @Test public void completedNewArtworkKeepsItsEditorOpenAfterTheBackgroundImport()throws Exception {
        newerArtwork(false);
    }
    @Test public void pendingNewArtworkKeepsItsOriginalIntentAndEditor()throws Exception {
        newerArtwork(true);
    }
    void newerArtwork(boolean pending)throws Exception {
        SketchEditableArchiveReturnLifecycleTest.Fixture f=core.fixture();CountDownLatch transferEntered=new CountDownLatch(1),transferRelease=new CountDownLatch(1),writerEntered=new CountDownLatch(1),writerRelease=new CountDownLatch(1);
        try(ActivityController<SketchStudioActivity> controller=core.open(f)){
            SketchStudioActivity studio=controller.get();core.chooseImport(studio);block((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"transfers"),transferEntered,transferRelease);
            controller.pause().stop();controller.restart().start();core.result(studio,f);controller.resume().visible();
            ((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            SketchEditorSession session=(SketchEditorSession)SketchEditableArchiveReturnLifecycleTest.field(studio,"session");if(pending)block((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(session,"writer"),writerEntered,writerRelease);
            studio.onOperation(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","newer_ink").put("kind","path").put("commands",new JSONArray("[['M',.1,.2],['L',.7,.8]]"))));
            if(pending)assertTrue(session.pending()>0);else{session.awaitIdle(30000);ShadowLooper.idleMainLooper();assertEquals(0,session.pending());assertEquals(2,session.document().getLong("revision"));}
            transferRelease.countDown();((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"transfers")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            assertEquals("A later human drawing must not be hidden by a completed import",f.current,SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));assertNotNull(SketchDocument.object(session.document(),"newer_ink"));if(pending)assertTrue(session.pending()>0);
            writerRelease.countDown();session.awaitIdle(30000);SketchEditableArchiveReturnLifecycleTest.settle(studio);
            String imported=imported(f);assertNotEquals(imported,SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));
            try(SketchProjectStore store=new SketchProjectStore(core.context)){assertNotNull(SketchDocument.object(store.get(f.current),"newer_ink"));assertEquals(2,store.get(f.current).getLong("revision"));assertEquals(f.otherBefore,SketchDocument.canonical(store.get(f.other)));}
        }finally{transferRelease.countDown();writerRelease.countDown();}
    }
    @Test public void changingBookPagesWhileTheArchiveCopiesMustKeepTheNewPageOpen()throws Exception {
        SketchEditableArchiveReturnLifecycleTest.Fixture f=core.fixture();String book="book_"+UUID.randomUUID(),name="Human book "+UUID.randomUUID();
        SketchEditOps.execute(core.context,"sketch_book_create",new JSONObject().put("bookId",book).put("operationId","create_book").put("name",name).put("pageCount",2).put("width",256).put("height",256));
        for(int page=0;page<2;page++){JSONObject opened=SketchEditOps.execute(core.context,"sketch_book_open_page",new JSONObject().put("bookId",book).put("page",page));f.beforeIds.add(opened.getString("sketchId"));}
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityController<SketchStudioActivity> controller=core.open(f)){
            SketchStudioActivity studio=controller.get();SketchEditableArchiveReturnLifecycleTest.click(studio,"File");SketchEditableArchiveReturnLifecycleTest.choose("Sketch books");SketchEditableArchiveReturnLifecycleTest.settle(studio);SketchEditableArchiveReturnLifecycleTest.choose(name+" · 2 pages");SketchEditableArchiveReturnLifecycleTest.settle(studio);assertEquals(book+"_p0",SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));
            core.chooseImport(studio);block((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"transfers"),entered,release);
            controller.pause().stop();controller.restart().start();core.result(studio,f);controller.resume().visible();((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            SketchEditableArchiveReturnLifecycleTest.click(studio,"▶");for(int i=0;i<3;i++){((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}assertEquals(book+"_p1",SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));
            release.countDown();SketchEditableArchiveReturnLifecycleTest.settle(studio);assertNotEquals(imported(f),SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));assertEquals(book,SketchEditableArchiveReturnLifecycleTest.field(studio,"bookId"));assertEquals(1,SketchEditableArchiveReturnLifecycleTest.field(studio,"bookPage"));assertEquals(book+"_p1",SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));
        }finally{release.countDown();}
    }
    @Test public void failedArchiveAfterTheFollowPauseRestoresThePriorUsableEditor()throws Exception {
        SketchEditableArchiveReturnLifecycleTest.Fixture f=core.fixture();Uri bad=Uri.parse("content://owned.documents/bad_"+UUID.randomUUID()+".vssketch");Shadows.shadowOf(core.context.getContentResolver()).registerInputStream(bad,new ByteArrayInputStream(new byte[]{1,2,3}));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityController<SketchStudioActivity> controller=core.open(f)){
            SketchStudioActivity studio=controller.get();core.chooseImport(studio);block((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"io"),entered,release);ShadowLooper.idleMainLooper(250,TimeUnit.MILLISECONDS);assertEquals(Boolean.TRUE,SketchEditableArchiveReturnLifecycleTest.field(studio,"watchInFlight"));
            controller.pause().stop();controller.restart().start();studio.onActivityResult(731,Activity.RESULT_OK,new Intent().setData(bad));controller.resume().visible();release.countDown();SketchEditableArchiveReturnLifecycleTest.settle(studio);
            assertEquals(f.current,SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));assertEquals(Boolean.FALSE,SketchEditableArchiveReturnLifecycleTest.field(studio,"loadingDrawing"));assertTrue(((SketchCanvasView)SketchEditableArchiveReturnLifecycleTest.field(studio,"canvas")).isEnabled());assertTrue(studio.getPreferences(0).getBoolean("watchLiveDrawing",false));
            try(SketchProjectStore store=new SketchProjectStore(core.context)){assertEquals(f.beforeIds.size(),store.list().length());assertEquals(f.currentBefore,SketchDocument.canonical(store.get(f.current)));}
        }finally{release.countDown();}
    }
    @Test public void aSuccessfulManualImportStaysSelectedAfterOrdinaryFollowTicks()throws Exception {
        SketchEditableArchiveReturnLifecycleTest.Fixture f=core.fixture();
        try(ActivityController<SketchStudioActivity> controller=core.open(f)){
            SketchStudioActivity studio=controller.get();core.chooseImport(studio);controller.pause().stop();controller.restart().start();core.result(studio,f);controller.resume().visible();SketchEditableArchiveReturnLifecycleTest.settle(studio);String imported=f.imported();assertEquals(imported,SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));
            ShadowLooper.idleMainLooper(1000,TimeUnit.MILLISECONDS);SketchEditableArchiveReturnLifecycleTest.settle(studio);
            assertEquals("A successful manual selection must not immediately jump to an old workspace signal",imported,SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));assertEquals(Boolean.FALSE,SketchEditableArchiveReturnLifecycleTest.field(studio,"watching"));assertFalse(studio.getPreferences(0).getBoolean("watchLiveDrawing",true));assertNotNull(SketchEditableArchiveReturnLifecycleTest.button(studio.getWindow().getDecorView(),"Watch ChatGPT"));
        }
    }
    @Test public void ordinaryWatchChatgptControlCanResumeFollowingAfterManualImport()throws Exception {
        SketchEditableArchiveReturnLifecycleTest.Fixture f=core.fixture();
        try(ActivityController<SketchStudioActivity> controller=core.open(f)){
            SketchStudioActivity studio=controller.get();core.chooseImport(studio);controller.pause().stop();controller.restart().start();core.result(studio,f);controller.resume().visible();SketchEditableArchiveReturnLifecycleTest.settle(studio);String imported=f.imported();assertEquals(imported,SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));
            SketchEditableArchiveReturnLifecycleTest.click(studio,"Watch ChatGPT");assertTrue(studio.getPreferences(0).getBoolean("watchLiveDrawing",false));ShadowLooper.idleMainLooper(250,TimeUnit.MILLISECONDS);SketchEditableArchiveReturnLifecycleTest.settle(studio);
            assertEquals("Explicit Watch ChatGPT resumes the existing newest-work selection",f.other,SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));assertEquals(Boolean.TRUE,SketchEditableArchiveReturnLifecycleTest.field(studio,"watching"));
        }
    }
    @Test public void aFreshExplicitFollowChoiceDuringTransferIsNotOverriddenByImportCompletion()throws Exception {
        SketchEditableArchiveReturnLifecycleTest.Fixture f=core.fixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityController<SketchStudioActivity> controller=core.open(f)){
            SketchStudioActivity studio=controller.get();core.chooseImport(studio);block((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"transfers"),entered,release);
            controller.pause().stop();controller.restart().start();core.result(studio,f);controller.resume().visible();((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            SketchEditableArchiveReturnLifecycleTest.click(studio,"File");SketchEditableArchiveReturnLifecycleTest.choose("Follow current work");release.countDown();SketchEditableArchiveReturnLifecycleTest.settle(studio);
            assertNotEquals(f.imported(),SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));assertEquals(Boolean.TRUE,SketchEditableArchiveReturnLifecycleTest.field(studio,"watching"));assertTrue(studio.getPreferences(0).getBoolean("watchLiveDrawing",false));
        }finally{release.countDown();}
    }
    static void block(ExecutorService executor,CountDownLatch entered,CountDownLatch release)throws Exception {executor.execute(()->{entered.countDown();try{assertTrue(release.await(60,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));}
    String imported(SketchEditableArchiveReturnLifecycleTest.Fixture f)throws Exception {try(SketchProjectStore store=new SketchProjectStore(core.context)){JSONArray rows=store.list();String found=null;for(int i=0;i<rows.length();i++){String id=rows.getJSONObject(i).getString("sketchId");if(!f.beforeIds.contains(id)){assertNull("Only the selected archive may create a new sketch",found);found=id;}}assertNotNull(found);assertEquals(325,store.get(found).getInt("frameCount"));return found;}}
}
