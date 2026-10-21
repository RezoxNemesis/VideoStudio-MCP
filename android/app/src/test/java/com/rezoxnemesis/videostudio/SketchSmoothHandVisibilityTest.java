package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import static org.junit.Assert.*;

/** Exact default-style failure discovered by the fresh4.10.3 movement audit. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSmoothHandVisibilityTest {
    private JSONObject author() throws Exception {
        JSONObject document=SketchDocument.create(new JSONObject().put("sketchId","smooth_left_profile")
                .put("width",1280).put("height",720).put("frameCount",96).put("fps",24).put("background","#FFF2DFCB"));
        SketchEditOps.apply(document,SketchHandAnimationTest.op("right").put("settings",
                new JSONObject().put("view","right").put("handedness","left").put("size",.95)));
        assertEquals("Retain the saved default smooth style", "smooth",SketchDocument.object(document,"hand_rig")
                .getJSONObject("hand").getJSONObject("settings").getString("renderStyle"));
        return document;
    }

    @Test public void leftProfileSmoothHandRendersThePreviouslyFailingFortiethFrame() throws Exception {
        JSONObject document=author();String before=SketchDocument.canonical(document);
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document)) {
            Bitmap frame=renderer.render(40);
            try {
                int[] pixels=new int[1280*720];frame.getPixels(pixels,0,1280,0,0,1280,720);
                int ink=0;for(int pixel:pixels)if(pixel!=0xFFF2DFCB)ink++;
                assertTrue("The repaired frame must contain actual hand artwork",ink>10000);
            } finally {frame.recycle();}
        }
        assertEquals("Rendering cannot rewrite the retained poses or artwork",before,SketchDocument.canonical(document));
    }

    @Test public void allNinetySixFramesRenderAndPreviouslySuccessfulFramesKeepTheirExactPixels() throws Exception {
        String baseline=System.getenv("VIDEOSTUDIO_SMOOTH_HAND_BASELINE");
        org.junit.Assume.assumeTrue("Explicit frozen4.10.3 native outputs for full retake",baseline!=null);
        JSONObject document=author();int compared=0;
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document)) {
            for(int f=0;f<96;f++) {
                Bitmap frame=renderer.render(f);
                try {
                    File original=new File(baseline,String.format(java.util.Locale.ROOT,"frames/frame-%04d.png",f));
                    if(original.isFile()) {
                        Bitmap old=BitmapFactory.decodeFile(original.getAbsolutePath());
                        assertNotNull(old);
                        try {assertTrue("Successful saved smooth frame changed at"+f,old.sameAs(frame));compared++;}
                        finally {old.recycle();}
                    }
                } finally {frame.recycle();}
            }
        }
        assertEquals("Preserve all40 actual successful frames from the failed generation",40,compared);
    }
}
