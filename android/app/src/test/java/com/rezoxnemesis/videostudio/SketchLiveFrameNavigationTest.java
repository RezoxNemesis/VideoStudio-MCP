package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchLiveFrameNavigationTest {
    @Test public void recentStrokeAndAnimationKeySelectTheirAuthoredFrame()throws Exception{
        JSONObject document=SketchDocument.create(new JSONObject()
                .put("sketchId","live_frame_navigation")
                .put("frameCount",48).put("width",320).put("height",400));
        ExecutorService thread=Executors.newSingleThreadExecutor();
        try{
            int[] actual=thread.submit(()->new int[]{
                    SketchLivePreparation.operationFrame(document,new JSONObject()
                            .put("type","add_object")
                            .put("object",new JSONObject().put("id","new_frame")
                                    .put("frame",29))),
                    SketchLivePreparation.operationFrame(document,new JSONObject()
                            .put("type","set_keyframe").put("layerId","layer_1")
                            .put("frame",43)),
                    SketchLivePreparation.operationFrame(document,new JSONObject()
                            .put("type","set_timing").put("fps",24))
            }).get(12,TimeUnit.SECONDS);
            assertArrayEquals(new int[]{29,43,0},actual);
        }finally{thread.shutdownNow();}
    }
    @Test public void impossibleFrameNeverSilentlyDisplaysAnUnrelatedOne()throws Exception{
        JSONObject document=SketchDocument.create(new JSONObject()
                .put("sketchId","invalid_frame_navigation")
                .put("frameCount",24).put("width",320).put("height",400));
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try{
            String detail=reader.submit(()->{
                try{
                    SketchLivePreparation.operationFrame(document,new JSONObject()
                            .put("type","set_keyframe").put("layerId","layer_1").put("frame",48));
                    return "not_rejected";
                }catch(Exception expected){return expected.getMessage();}
            }).get(12,TimeUnit.SECONDS);
            assertFalse("Out-of-range frame must be rejected","not_rejected".equals(detail));
        }finally{reader.shutdownNow();}
    }
}
