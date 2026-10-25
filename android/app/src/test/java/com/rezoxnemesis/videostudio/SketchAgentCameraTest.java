package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Sketch agent navigation manipulates only the foreground viewport, not the document. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAgentCameraTest {
    @Test public void zeroDurationMoveSetsTheRealDrawingViewportWithoutEditingGeometry() throws Exception{
        SketchCanvasView canvas=new SketchCanvasView(RuntimeEnvironment.getApplication());
        try{
            canvas.setPreviewActive(false);
            canvas.layout(0,0,400,600);
            JSONObject drawing=SketchDocument.create(new JSONObject()
                    .put("sketchId","camera-"+UUID.randomUUID())
                    .put("operationId","test-"+UUID.randomUUID())
                    .put("width",720).put("height",1280).put("frameCount",48));
            String before=SketchDocument.canonical(drawing);
            canvas.setDocument(drawing);
            AtomicBoolean completed=new AtomicBoolean();
            canvas.animateAgentCamera(3,.24,.72,0,completed::set);
            JSONObject position=canvas.cameraSnapshot();
            assertTrue(completed.get());
            assertEquals(3,position.getDouble("zoom"),.0001);
            assertEquals(.24,position.getDouble("centerX"),.0001);
            assertEquals(.72,position.getDouble("centerY"),.0001);
            assertEquals(before,SketchDocument.canonical(drawing));
            canvas.resetView();
            JSONObject reset=canvas.cameraSnapshot();
            assertEquals(1,reset.getDouble("zoom"),.0001);
            assertEquals(.5,reset.getDouble("centerX"),.0001);
            assertEquals(.5,reset.getDouble("centerY"),.0001);
        }finally{canvas.dispose();}
    }
    @Test public void invalidCameraTargetCannotMisreportCompletedUIAction()throws Exception{
        SketchCanvasView canvas=new SketchCanvasView(RuntimeEnvironment.getApplication());
        try{
            canvas.setPreviewActive(false);
            canvas.layout(0,0,400,600);
            canvas.setDocument(SketchDocument.create(new JSONObject()
                    .put("sketchId","camera-"+UUID.randomUUID())));
            assertThrows(IllegalArgumentException.class,()->
                    canvas.animateAgentCamera(99,.5,.5,0,ok->{}));
            assertThrows(IllegalArgumentException.class,()->
                    canvas.animateAgentCamera(2,-.7,.5,100,ok->{}));
        }finally{canvas.dispose();}
    }
}
