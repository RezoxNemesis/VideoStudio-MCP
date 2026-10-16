package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Shared-model sampling must never change pose semantics or author data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAnimationSamplingSessionTest {
    private JSONObject source()throws Exception {
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId","sampling_source")
                .put("width",320).put("height",480).put("frameCount",1));
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects")
                .put(SketchEditOps.completeObject(new JSONObject().put("id","hand_ink")
                .put("kind","line").put("points",new JSONArray()
                .put(new JSONArray().put(.3).put(.5).put(1))
                .put(new JSONArray().put(.24).put(.6).put(1)))));
        return source;
    }
    @Test public void sharedModelSamplesMatchSinglesAndNativePreviewAcrossWholeInterval()throws Exception {
        JSONObject source=source(),program=SketchAnimationUiModel.createProgram(source).put("frameCount",45);
        SketchAnimationUiModel.rig(program,"character_1").getJSONArray("bindings")
                .put(new JSONObject().put("objectId","hand_ink").put("jointId","hand_left"));
        SketchAnimationUiModel.setRotation(program,"character_1",0,"arm_left",0,"linear");
        SketchAnimationUiModel.setRotation(program,"character_1",20,"arm_left",44,"smooth");
        SketchAnimationUiModel.setRotation(program,"character_1",44,"arm_left",-20,"linear");
        program.getJSONArray("camera").put(new JSONObject().put("frame",0).put("zoom",1.15).put("rotation",0));
        program.getJSONArray("camera").put(new JSONObject().put("frame",44).put("zoom",1.35).put("rotation",25));
        String beforeSource=SketchDocument.canonical(source),beforeProgram=SketchDocument.canonical(program);
        JSONArray batch=SketchAnimationCompiler.sampleFrames(source,program,0,44);
        assertEquals(45,batch.length());
        for(int frame:new int[]{0,1,4,12,20,31,44}){
            JSONObject expected=SketchAnimationCompiler.sample(source,program,frame),
                    actual=batch.getJSONObject(frame),
                    preview=SketchAnimationCompiler.preview(source,program,frame,"sampling_preview");
            assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(actual));
            assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(preview.getJSONObject("sample")));
            assertEquals(1,preview.getJSONObject("document").getInt("frameCount"));
        }
        assertEquals(beforeSource,SketchDocument.canonical(source));
        assertEquals(beforeProgram,SketchDocument.canonical(program));
    }
    @Test public void longOrOutOfRangeSharedModelSamplingFailsBeforeBuildingFrames()throws Exception {
        JSONObject s=source(),p=SketchAnimationUiModel.createProgram(s);
        for(int[] interval:new int[][]{{-1,2},{0,100},{4,3}}){
            try{SketchAnimationCompiler.sampleFrames(s,p,interval[0],interval[1]);fail();}
            catch(IllegalArgumentException expected){}
        }
    }
}
