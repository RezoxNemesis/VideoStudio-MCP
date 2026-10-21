package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import static org.junit.Assert.*;

/** Cel edits preserve the actual native eased drawing and speech clock, not just key counts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchRetainedCelTimelineTest {
    @Test public void duplicateFreezesTheSelectedDrawingWithoutRestartingItsSurvivingTail()throws Exception {
        for(boolean human:new boolean[]{false,true}){
            JSONObject source=source(human),edited=SketchDocument.copy(source);String before=canonical(source);
            SketchEditOps.apply(edited,new JSONObject().put("type","duplicate_cel").put("layerId","layer_1")
                    .put("fromFrame",45).put("toFrame",83).put("idPrefix","duplicate"));
            JSONObject duplicate=SketchDocument.object(edited,"duplicate_0"),payload=payload(duplicate);
            assertEquals(1,payload.getJSONArray("poses").length());assertTrue(payload.has("renderFrame"));
            assertFalse("Frozen speech is already resolved into the editable pose",payload.has("speech"));
            assertPixels(source,edited,new int[][]{{45,83},{84,84},{109,109}});
            assertLiveProgrammesRetained(source,edited,2);assertEquals(before,canonical(source));
        }
    }

    @Test public void clearLeavesABlankCelAndAnEditableContinuousEasedSpeechTail()throws Exception {
        for(boolean human:new boolean[]{false,true}){
            JSONObject source=source(human),edited=SketchDocument.copy(source);String before=canonical(source);
            SketchEditOps.apply(edited,cel("clear_cel",31));
            assertPixels(source,edited,new int[][]{{30,30},{32,32},{60,60},{118,118}});
            assertBlank(edited,31);assertLiveProgrammesRetained(source,edited,2);
            JSONObject tail=exposed(edited,60);String prefix=canonical(exposed(edited,30));
            JSONObject changed=SketchDocument.copy(payload(tail));
            changed.getJSONArray("poses").getJSONObject(1).put(human?"headTilt":"indexCurl",human?-12:.05);
            JSONObject altered=SketchDocument.copy(edited);
            SketchEditOps.apply(altered,new JSONObject().put("type","update_object").put("objectId",tail.getString("id"))
                    .put("patch",new JSONObject().put(human?"human":"hand",changed)));
            assertDifferentPixels(edited,altered,60);
            assertEquals("Editing the tail cannot alias the prefix",prefix,canonical(exposed(altered,30)));
            assertEquals(before,canonical(source));
        }
    }

    @Test public void insertHoldsThePrecedingPoseAndShiftsEverySurvivingSourceClock()throws Exception {
        for(boolean human:new boolean[]{false,true}){
            JSONObject source=source(human),edited=SketchDocument.copy(source);String before=canonical(source);
            SketchEditOps.apply(edited,cel("insert_frame",31));assertEquals(121,edited.getInt("frameCount"));
            assertPixels(source,edited,new int[][]{{30,30},{30,31},{31,32},{60,61},{119,120}});
            assertLiveProgrammesRetained(source,edited,2);
            assertEquals(1,payload(exposed(edited,31)).getJSONArray("poses").length());
            assertEquals(before,canonical(source));
        }
    }

    @Test public void deleteRemovesOnlyTheSelectedPoseAndShiftsTheContinuousTail()throws Exception {
        for(boolean human:new boolean[]{false,true}){
            JSONObject source=source(human),edited=SketchDocument.copy(source);String before=canonical(source);
            SketchEditOps.apply(edited,cel("delete_frame",31));assertEquals(119,edited.getInt("frameCount"));
            assertPixels(source,edited,new int[][]{{30,30},{32,31},{61,60},{119,118}});
            assertLiveProgrammesRetained(source,edited,2);assertEquals(before,canonical(source));
        }
    }

    @Test public void repeatedEditsAtTheTailStartComposeOffsetsAndKeepFrozenCelsStill()throws Exception {
        for(boolean human:new boolean[]{false,true}){
            JSONObject source=source(human),edited=SketchDocument.copy(source);String before=canonical(source);
            SketchEditOps.apply(edited,cel("clear_cel",0));
            SketchEditOps.apply(edited,cel("delete_frame",1));
            assertPixels(source,edited,new int[][]{{2,1},{61,60},{119,118}});
            SketchEditOps.apply(edited,new JSONObject().put("type","duplicate_cel").put("layerId","layer_1")
                    .put("fromFrame",60).put("toFrame",118).put("idPrefix","frozen"));
            assertPixels(source,edited,new int[][]{{61,118}});
            SketchEditOps.apply(edited,cel("insert_frame",118));
            assertPixels(source,edited,new int[][]{{61,119}});
            assertEquals(before,canonical(source));

            JSONObject holding=SketchDocument.copy(source);payload(rig(holding)).put("sourceFrameOffset",119);
            String heldBefore=canonical(holding);JSONObject shrinking=SketchDocument.copy(holding);
            for(int i=0;i<4;i++){
                SketchEditOps.apply(shrinking,cel("delete_frame",0));
                assertEquals("Deleting an endpoint hold must not accumulate clock offsets",119,
                        payload(rig(shrinking)).getInt("sourceFrameOffset"));
            }
            assertPixels(holding,shrinking,new int[][]{{0,0},{119,115}});
            assertEquals(heldBefore,canonical(holding));
        }
    }

    @Test public void disjointCelRangesHaveIdenticalNativePreviewAndBakePixels()throws Exception {
        for(boolean human:new boolean[]{false,true}){
            JSONObject source=source(human);String before=canonical(source),id=rig(source).getString("id");
            JSONObject programme=programme(120).put("celRanges",new JSONArray()
                    .put(range(id,30,70)).put(range(id,90,119)));
            JSONObject baked=SketchAnimationCompiler.compile(source,programme,"retained_baked").getJSONObject("document");
            assertLiveProgrammesRetained(source,baked,2);
            for(int frame:new int[]{30,48,70,90,110,119}){
                JSONObject preview=SketchAnimationCompiler.preview(source,programme,frame,"retained_preview").getJSONObject("document");
                assertPixels(baked,preview,new int[][]{{frame,0}});
            }
            assertBlank(baked,71);assertEquals(before,canonical(source));
            assertExpandedRangeParity(source);
            assertNormalizedHoldRangeParity(source);
        }
    }

    private static void assertExpandedRangeParity(JSONObject original)throws Exception {
            JSONObject source=SketchDocument.copy(original);
            SketchEditOps.apply(source,new JSONObject().put("type","update_object").put("objectId",rig(source).getString("id"))
                    .put("patch",new JSONObject().put("frame",13).put("exposure",107)));
            String before=canonical(source);
            JSONObject programme=programme(150).put("celRanges",new JSONArray().put(range(rig(source).getString("id"),0,149)));
            JSONObject baked=SketchAnimationCompiler.compile(source,programme,"retained_extended").getJSONObject("document");
            for(int frame:new int[]{0,13,31,119,130,149}){
                JSONObject preview=SketchAnimationCompiler.preview(source,programme,frame,"retained_preview").getJSONObject("document");
                assertPixels(baked,preview,new int[][]{{frame,0}});
            }
            assertLiveProgrammesRetained(source,baked,1);assertEquals(before,canonical(source));
    }

    private static void assertNormalizedHoldRangeParity(JSONObject source)throws Exception {
            String id=rig(source).getString("id"),before=canonical(source);
            JSONObject held=SketchAnimationCompiler.compile(source,programme(180).put("celRanges",new JSONArray()
                    .put(range(id,150,179))),"retained_held").getJSONObject("document");
            assertEquals("A fully held range uses the final programme frame",119,
                    payload(rig(held)).getInt("sourceFrameOffset"));
            String heldBefore=canonical(held);
            JSONObject expanded=programme(180).put("celRanges",new JSONArray().put(range(id,0,179)));
            JSONObject revealed=SketchAnimationCompiler.compile(held,expanded,"retained_revealed").getJSONObject("document");
            for(int frame:new int[]{31,60}){
                // A normalized hold is the next edit's source; its hidden original origin is not retained.
                JSONObject expected=SketchAnimationCompiler.preview(held,expanded,frame,"retained_expected").getJSONObject("document");
                assertPixels(expected,revealed,new int[][]{{0,frame}});
            }
            assertEquals(before,canonical(source));assertEquals(heldBefore,canonical(held));

            JSONObject early=SketchDocument.copy(source).put("frameCount",1200);
            rig(early).put("frame",1199).put("exposure",1);payload(rig(early)).put("sourceFrameOffset",-1200);
            String earlyBefore=canonical(early);
            JSONObject earlyProgramme=programme(1200).put("celRanges",new JSONArray().put(range(id,0,1199)));
            JSONObject earlyBaked=SketchAnimationCompiler.compile(early,earlyProgramme,"retained_early").getJSONObject("document");
            assertEquals("Leading endpoint holds stay inside the signed offset budget",-1200,
                    payload(rig(earlyBaked)).getInt("sourceFrameOffset"));
            JSONObject earlyPreview=SketchAnimationCompiler.preview(early,earlyProgramme,1199,"retained_early_preview").getJSONObject("document");
            assertPixels(earlyBaked,earlyPreview,new int[][]{{1199,0}});
            assertEquals(earlyBefore,canonical(early));
    }

    @Test public void sixteenClearsAllowSeventeenNonoverlappingRetainedSegments()throws Exception {
        for(boolean human:new boolean[]{false,true}){
            JSONObject source=source(human),edited=SketchDocument.copy(source);String before=canonical(source);
            for(int frame=2;frame<=32;frame+=2)SketchEditOps.apply(edited,cel("clear_cel",frame));
            assertEquals("A single actor can have more than sixteen timeline segments",17,objects(edited).length());
            assertLiveProgrammesRetained(source,edited,17);
            for(int frame=0;frame<120;frame++){
                int active=0;for(int i=0;i<objects(edited).length();i++)
                    if(SketchFrameRenderer.exposed(objects(edited).getJSONObject(i),frame))active++;
                assertTrue("Clear segments never render together",active<=1);
            }
            assertPixels(source,edited,new int[][]{{1,1},{3,3},{31,31},{33,33},{60,60},{119,119}});
            assertBlank(edited,2);assertBlank(edited,32);assertEquals(before,canonical(source));

            // Half-open exposure intervals sharing an endpoint do not overlap either.
            JSONObject adjacent=SketchDocument.copy(source);JSONArray cels=new JSONArray();
            for(int frame=0;frame<17;frame++){
                JSONObject cel=human?SketchHumanAnimation.sampled(rig(source),frame):SketchHandAnimation.sampled(rig(source),frame);
                cels.put(cel.put("id","adjacent_"+frame).put("frame",frame).put("exposure",1));
            }
            adjacent.getJSONArray("layers").getJSONObject(0).put("objects",cels);SketchDocument.validate(adjacent);
            assertPixels(source,adjacent,new int[][]{{0,0},{8,8},{16,16}});
        }
    }

    @Test public void seventeenthOverlappingActorDoesNotPublishAPartlyEditedProject()throws Exception {
        JSONObject source=source(false),original=rig(source);
        for(int i=1;i<16;i++)objects(source).put(SketchHandAnimation.sampled(original,0)
                .put("id","still_"+i).put("exposure",1));
        SketchDocument.validate(source);
        try(SketchProjectStore store=new SketchProjectStore(RuntimeEnvironment.getApplication())){
            store.importOwnedDocument(source,"timeline_source");String before=canonical(store.get(source.getString("id")));
            JSONObject seventeenth=SketchHandAnimation.sampled(original,0).put("id","overlap_17").put("exposure",1);
            JSONObject request=new JSONObject().put("sketchId",source.getString("id")).put("expectedRevision",1)
                    .put("operationId","overlap_budget").put("operations",new JSONArray()
                            .put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","overlap_layer")))
                            .put(new JSONObject().put("type","add_object").put("layerId","overlap_layer").put("object",seventeenth)));
            IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,
                    ()->SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_edit",request));
            assertTrue("The simultaneous actor cap must reject the seventeenth actor",failure.getMessage().contains("at most16"));
            assertEquals(before,canonical(store.get(source.getString("id"))));
        }
    }

    private static JSONObject source(boolean human)throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","timeline_"+UUID.randomUUID())
                .put("width",1280).put("height",720).put("fps",30).put("frameCount",120).put("background","#FFF1E9DE"));
        JSONArray poses=new JSONArray();
        poses.put(new JSONObject().put("frame",0).put("interpolation","bezier")
                .put("curve",new JSONArray().put(.15).put(.75).put(.85).put(.2))
                .put(human?"headTilt":"indexCurl",human?-5:.65));
        poses.put(new JSONObject().put("frame",47).put("interpolation","smooth")
                .put(human?"headTilt":"indexCurl",human?12:.9));
        poses.put(new JSONObject().put("frame",119).put(human?"headTilt":"indexCurl",human?-8:.2));
        JSONObject op=new JSONObject().put("type",human?"add_human_animation":"add_hand_animation")
                .put("layerId","layer_1").put(human?"actorId":"handId","actor").put("settings",new JSONObject()).put("poses",poses);
        if(human)op.put("speech",new JSONObject().put("transitionMs",90).put("cues",new JSONArray()
                .put(cue(0,800,"rest")).put(cue(800,2000,"round"))
                .put(cue(2000,2700,"wide")).put(cue(2700,4000,"closed"))));
        SketchEditOps.apply(doc,op);return doc;
    }
    private static JSONObject cue(int first,int last,String shape)throws Exception {
        return new JSONObject().put("startMs",first).put("endMs",last).put("shape",shape);
    }
    private static JSONObject cel(String type,int frame)throws Exception {
        JSONObject op=new JSONObject().put("type",type).put("frame",frame);
        if(type.equals("clear_cel"))op.put("layerId","layer_1");return op;
    }
    private static JSONObject programme(int frames)throws Exception {
        return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",frames)
                .put("fps",30).put("rigs",new JSONArray());
    }
    private static JSONObject range(String id,int first,int last)throws Exception {
        return new JSONObject().put("objectId",id).put("start",first).put("end",last);
    }
    private static JSONArray objects(JSONObject doc)throws Exception {return doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");}
    private static JSONObject rig(JSONObject doc)throws Exception {return objects(doc).getJSONObject(0);}
    private static JSONObject payload(JSONObject object)throws Exception {return object.getJSONObject(object.getString("kind"));}
    private static String canonical(JSONObject value)throws Exception {return SketchDocument.canonical(value);}
    private static JSONObject exposed(JSONObject doc,int frame)throws Exception {
        JSONArray objects=objects(doc);
        for(int i=0;i<objects.length();i++)if(SketchFrameRenderer.exposed(objects.getJSONObject(i),frame))return objects.getJSONObject(i);
        throw new AssertionError("No retained cel at frame "+frame);
    }
    private static void assertLiveProgrammesRetained(JSONObject source,JSONObject edited,int expected)throws Exception {
        int live=0;JSONObject original=payload(rig(source));JSONArray objects=objects(edited);
        for(int i=0;i<objects.length();i++){
            JSONObject payload=payload(objects.getJSONObject(i));if(payload.has("renderFrame"))continue;live++;
            assertEquals("Eased keys remain sparse and independently editable",original.getJSONArray("poses").toString(),payload.getJSONArray("poses").toString());
            assertEquals(original.getInt("durationFrames"),payload.getInt("durationFrames"));
            assertEquals(original.getInt("fps"),payload.getInt("fps"));
            if(original.has("speech"))assertEquals(canonical(original.getJSONObject("speech")),canonical(payload.getJSONObject("speech")));
        }
        assertEquals(expected,live);SketchDocument.validate(edited);
    }
    private static void assertPixels(JSONObject source,JSONObject edited,int[][] frames)throws Exception {
        try(SketchFrameRenderer a=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),source);
            SketchFrameRenderer b=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),edited)){
            for(int[] pair:frames){Bitmap x=a.render(pair[0]),y=b.render(pair[1]);
                try{assertTrue("Native pixel parity: source "+pair[0]+" vs edited "+pair[1],x.sameAs(y));}
                finally{x.recycle();y.recycle();}
            }
        }
    }
    private static void assertDifferentPixels(JSONObject source,JSONObject edited,int frame)throws Exception {
        try(SketchFrameRenderer a=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),source);
            SketchFrameRenderer b=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),edited)){
            Bitmap x=a.render(frame),y=b.render(frame);try{assertFalse("Tail pose edits must reach native pixels",x.sameAs(y));}
            finally{x.recycle();y.recycle();}
        }
    }
    private static void assertBlank(JSONObject doc,int frame)throws Exception {
        JSONObject blank=SketchDocument.copy(doc);blank.getJSONArray("layers").getJSONObject(0).put("objects",new JSONArray());
        assertPixels(blank,doc,new int[][]{{frame,frame}});
    }
}
