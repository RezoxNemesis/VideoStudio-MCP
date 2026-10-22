package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** A separately completed native/MCP edit must not be hidden by a late manual import. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchEditableArchiveExternalEditTest {
    @Test public void completedNativeEditOutsideTheUiSessionPreventsTheLateAutoOpen()throws Exception {
        SketchEditableArchiveReturnLifecycleTest core=new SketchEditableArchiveReturnLifecycleTest();SketchEditableArchiveReturnLifecycleTest.Fixture f=core.fixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService external=Executors.newSingleThreadExecutor();
        try(ActivityController<SketchStudioActivity> controller=core.open(f)){
            SketchStudioActivity studio=controller.get();core.chooseImport(studio);SketchEditableArchiveReturnGuardTest.block((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"transfers"),entered,release);
            controller.pause().stop();controller.restart().start();core.result(studio,f);controller.resume().visible();((ExecutorService)SketchEditableArchiveReturnLifecycleTest.field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            SketchEditorSession original=(SketchEditorSession)SketchEditableArchiveReturnLifecycleTest.field(studio,"session");assertEquals(1,original.document().getLong("revision"));
            JSONObject receipt=external.submit(()->SketchEditOps.execute(core.context,"sketch_edit",new JSONObject().put("sketchId",f.current).put("expectedRevision",1).put("operationId","external_native_ink").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","external_ink").put("kind","path").put("commands",new JSONArray("[['M',.1,.2],['L',.7,.8]]"))))))).get(30,TimeUnit.SECONDS);
            assertTrue(receipt.getBoolean("persisted"));assertEquals(2,receipt.getLong("revision"));assertEquals("The native writer is independent of the editor's old session snapshot",1,original.document().getLong("revision"));
            release.countDown();SketchEditableArchiveReturnLifecycleTest.settle(studio);
            try(SketchProjectStore store=new SketchProjectStore(core.context)){assertEquals(2,store.get(f.current).getLong("revision"));assertNotNull(SketchDocument.object(store.get(f.current),"external_ink"));assertEquals(f.otherBefore,SketchDocument.canonical(store.get(f.other)));}
            assertEquals("An import must not preempt the origin after a separately completed native edit",f.current,SketchEditableArchiveReturnLifecycleTest.field(studio,"sketchId"));
            ShadowLooper.idleMainLooper(250,TimeUnit.MILLISECONDS);SketchEditableArchiveReturnLifecycleTest.settle(studio);SketchEditorSession refreshed=(SketchEditorSession)SketchEditableArchiveReturnLifecycleTest.field(studio,"session");assertEquals(2,refreshed.document().getLong("revision"));assertNotNull(SketchDocument.object(refreshed.document(),"external_ink"));assertTrue(studio.getPreferences(0).getBoolean("watchLiveDrawing",false));
        }finally{release.countDown();external.shutdownNow();}
    }
}
