package com.rezoxnemesis.videostudio;

import android.content.Context;
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

/** A real semantic-plan -> native source/programme -> book path, with no private film author. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchStoryPlanOpsTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private JSONObject shot(String id,int frames,String color)throws Exception {
        JSONArray path=new JSONArray().put(new JSONArray().put("M").put(.4).put(.4)).put(new JSONArray().put("L").put(.6).put(.4))
                .put(new JSONArray().put("L").put(.6).put(.6)).put(new JSONArray().put("L").put(.4).put(.6)).put(new JSONArray().put("Z"));
        JSONArray ops=new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject()
                .put("id","subject").put("kind","path").put("commands",path).put("color",color).put("fill",true).put("exposure",frames)))
                .put(new JSONObject().put("type","set_keyframe").put("objectId","subject").put("frame",0).put("interpolation","linear").put("transform",new JSONObject().put("rotation",0)))
                .put(new JSONObject().put("type","set_keyframe").put("objectId","subject").put("frame",frames-1).put("interpolation","hold").put("transform",new JSONObject().put("rotation",12)));
        return new JSONObject().put("shotId",id).put("frameCount",frames).put("label","Beat "+id).put("operations",ops);
    }
    private JSONObject plan()throws Exception {
        JSONObject first=shot("intro",24,"#FF267C86");first.put("camera",new JSONArray().put(new JSONObject().put("frame",0).put("zoom",1)).put(new JSONObject().put("frame",23).put("zoom",1.05)));
        return new JSONObject().put("bookId","story_"+UUID.randomUUID().toString().replace("-","")).put("operationId","create")
                .put("name","An editable human story").put("width",320).put("height",480).put("fps",24)
                .put("shots",new JSONArray().put(first).put(shot("answer",72,"#FFB46A42")));
    }
    private JSONObject create(JSONObject args)throws Exception{return SketchStoryPlanOps.execute(context,"sketch_story_create",args);}
    private JSONObject book(JSONObject args)throws Exception{try(SketchBookStore books=new SketchBookStore(context)){return books.get(args.getString("bookId"));}}
    private String page(JSONObject args,int page)throws Exception{return args.getString("bookId")+"_p"+page;}
    private void rename(String id)throws Exception{try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.mutate(id,"human_edit",sketches.revision(id),"a".repeat(64),d->d.put("name","Human edits stay"));}}
    private void noBook(JSONObject args)throws Exception{try(SketchBookStore books=new SketchBookStore(context)){assertThrows(IllegalArgumentException.class,()->books.get(args.getString("bookId")));}try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(page(args,0)));}assertEquals(0,SketchAnimationOps.execute(context,"sketch_animation_list",new JSONObject()).getJSONArray("animations").length());}
    private interface Checked {void run()throws Exception;}
    private void rejects(Checked action,String expected)throws Exception{try{action.run();fail("Expected "+expected);}catch(Exception failure){assertTrue(failure.toString(),failure.toString().contains(expected));}}

    @Test public void sharedExampleReturnsExactAssetsFreshIdentitiesAndSerializablePlansWithoutDatabaseWrites()throws Exception {
        String[] databases=context.databaseList();java.util.Arrays.sort(databases);
        JSONObject first=SketchStoryPlanOps.example(context),second=SketchStoryPlanOps.execute(context,"sketch_story_example",new JSONObject().put("exampleId","last_lantern"));
        assertEquals("last_lantern",first.getString("exampleId"));assertTrue(first.getBoolean("readOnly"));assertFalse(first.getBoolean("audioEmbedded"));assertTrue(first.getBoolean("dialogueGenerationNeedsConfiguredRunner"));
        JSONObject a=first.getJSONObject("plan"),b=second.getJSONObject("plan");assertNotEquals(a.getString("bookId"),b.getString("bookId"));assertNotEquals(a.getString("operationId"),b.getString("operationId"));assertTrue(a.getString("bookId").length()<=48);
        JSONObject source=asset("the-last-lantern.json"),dialogue=asset("the-last-lantern-dialogue-controls.json");assertNotEquals(source.getString("bookId"),a.getString("bookId"));
        source.put("bookId",a.getString("bookId")).put("operationId",a.getString("operationId"));assertEquals(SketchDocument.canonical(SketchDocument.copy(source)),SketchDocument.canonical(a));
        assertEquals(SketchDocument.canonical(DialogueControls.validate(dialogue)),SketchDocument.canonical(first.getJSONObject("dialogueControls")));
        // Integral doubles and asset -0.0 must keep the same fingerprint after persistence.
        assertEquals(SketchDocument.canonical(a),SketchDocument.canonical(SketchDocument.copy(a)));
        String[] after=context.databaseList();java.util.Arrays.sort(after);assertArrayEquals(databases,after);
        assertFalse(new java.io.File(context.getFilesDir(),"sketch_story_plans").exists());assertFalse(new java.io.File(context.getFilesDir(),"sketch_story_manual_requests").exists());
    }

    @Test public void exampleDispatchRejectsUnknownIdMissingIdAndExtraArgumentsWithoutCreatingStores()throws Exception {
        String[] before=context.databaseList();java.util.Arrays.sort(before);
        for(JSONObject args:new JSONObject[]{new JSONObject(),new JSONObject().put("exampleId","unknown"),new JSONObject().put("exampleId","last_lantern").put("save",true)})
            assertThrows(Exception.class,()->SketchStoryPlanOps.execute(context,"sketch_story_example",args));
        String[] after=context.databaseList();java.util.Arrays.sort(after);assertArrayEquals(before,after);assertFalse(new java.io.File(context.getFilesDir(),"sketch_story_plans").exists());
    }
    private JSONObject asset(String name)throws Exception {
        try(java.io.InputStream in=context.getAssets().open("animation_stories/"+name);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)bytes.write(buffer,0,count);return new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test public void publicPreviewAndPreparedSnapshotsAreNativeReadOnlyAndMatchSharedCreate()throws Exception {
        JSONObject args=plan();String before=SketchDocument.canonical(args);
        JSONObject prepared=SketchStoryPlanOps.preparePreview(context,args,"intro");
        assertEquals(before,SketchDocument.canonical(args));assertFalse(new java.io.File(context.getFilesDir(),"sketch_story_plans").exists());noBook(args);
        String frozen=SketchDocument.canonical(prepared);
        JSONObject response=SketchStoryPlanOps.execute(context,"sketch_story_preview",new JSONObject().put("plan",args).put("shotId","intro").put("frame",17));
        JSONObject direct=SketchStoryPlanOps.preview(context,args,"intro",17);assertEquals(SketchDocument.canonical(response),SketchDocument.canonical(direct));
        JSONObject expected=SketchAnimationCompiler.preview(prepared.getJSONObject("sourceDocument"),prepared.getJSONObject("program"),17,"prepared_sample").getJSONObject("document");
        try(SketchFrameRenderer a=new SketchFrameRenderer(context,expected);SketchFrameRenderer b=new SketchFrameRenderer(context,response.getJSONObject("document"))){Bitmap x=a.render(0),y=b.render(0);try{assertTrue(x.sameAs(y));}finally{x.recycle();y.recycle();}}
        assertEquals(frozen,SketchDocument.canonical(prepared));noBook(args);
        JSONObject result=create(args),saved=SketchAnimationOps.load(context,result.getJSONArray("shots").getJSONObject(0).getString("animationId"));
        assertEquals(SketchDocument.canonical(prepared.getJSONObject("sourceDocument")),SketchDocument.canonical(saved.getJSONObject("sourceDocument")));
        assertEquals(SketchDocument.canonical(prepared.getJSONObject("program")),SketchDocument.canonical(saved.getJSONObject("program")));
        args.getJSONArray("shots").getJSONObject(0).getJSONArray("operations").getJSONObject(0).getJSONObject("object").put("color","#FF000000");assertEquals(frozen,SketchDocument.canonical(prepared));
    }

    @Test public void previewOnlyCompilesChosenShotWhileCreateStillPreflightsAllShots()throws Exception {
        JSONObject args=plan();args.getJSONArray("shots").getJSONObject(1).getJSONArray("operations").put(new JSONObject().put("type","not_a_native_operation"));
        assertNotNull(SketchStoryPlanOps.preparePreview(context,args,"intro").getJSONObject("sourceDocument"));
        assertNotNull(SketchStoryPlanOps.preview(context,args,"intro",0).getJSONObject("document"));noBook(args);
        assertThrows(IllegalArgumentException.class,()->SketchStoryPlanOps.preparePreview(context,args,"answer"));noBook(args);
        assertThrows(IllegalArgumentException.class,()->create(args));noBook(args);
    }

    @Test public void previewRejectsUnknownShotFramesWrappersAndPlanBoundsWithoutWriting()throws Exception {
        JSONObject args=plan();assertThrows(IllegalArgumentException.class,()->SketchStoryPlanOps.preparePreview(context,args,"absent"));
        for(int frame:new int[]{-1,24})assertThrows(IllegalArgumentException.class,()->SketchStoryPlanOps.preview(context,args,"intro",frame));
        assertThrows(IllegalArgumentException.class,()->SketchStoryPlanOps.execute(context,"sketch_story_preview",new JSONObject().put("plan",args).put("shotId","intro").put("frame",0).put("save",true)));
        JSONObject structural=SketchDocument.copy(args);structural.getJSONArray("shots").getJSONObject(1).put("frameCount",1201);assertThrows(IllegalArgumentException.class,()->SketchStoryPlanOps.preparePreview(context,structural,"intro"));noBook(args);
        assertFalse(new java.io.File(context.getFilesDir(),"sketch_story_plans").exists());
    }

    @Test public void onePlanCreatesSavedNativeShotsExactCelsCameraAndGlobalShotCues()throws Exception {
        JSONObject args=plan(),result=create(args);assertEquals(96,result.getInt("frameCount"));assertEquals(24,result.getInt("fps"));assertEquals(2,result.getJSONArray("shots").length());assertFalse(result.getBoolean("audioMixedIntoSketch"));assertFalse(result.getBoolean("filmQualityCertified"));
        assertEquals(96,book(args).getInt("createdPages"));assertEquals(5,book(args).getLong("revision"));
        JSONObject timeline=SketchBookStore.execute(context,"sketch_book_timeline",new JSONObject().put("bookId",args.get("bookId")).put("frameOffset",0).put("frameLimit",96));
        JSONArray cues=timeline.getJSONArray("cues");assertEquals(2,cues.length());assertEquals(0,cues.getJSONObject(0).getInt("startFrame"));assertEquals(23,cues.getJSONObject(0).getInt("endFrame"));assertEquals(24,cues.getJSONObject(1).getInt("startFrame"));assertEquals(95,cues.getJSONObject(1).getInt("endFrame"));
        JSONObject shot=result.getJSONArray("shots").getJSONObject(0),saved=SketchAnimationOps.load(context,shot.getString("animationId"));assertEquals(1,saved.getJSONObject("program").getJSONArray("cameraLayerIds").length());
    }

    @Test public void actualNativePixelsEqualTheSavedProgrammeSampleIncludingCamera()throws Exception {
        JSONObject args=plan(),result=create(args),saved=SketchAnimationOps.load(context,result.getJSONArray("shots").getJSONObject(0).getString("animationId"));
        assertEquals("layer_1",saved.getJSONObject("program").getJSONArray("cameraLayerIds").getString(0));
        JSONObject expected=SketchAnimationCompiler.preview(saved.getJSONObject("sourceDocument"),saved.getJSONObject("program"),17,page(args,17)).getJSONObject("document");
        try(SketchProjectStore sketches=new SketchProjectStore(context);SketchFrameRenderer a=new SketchFrameRenderer(context,expected);SketchFrameRenderer b=new SketchFrameRenderer(context,sketches.get(page(args,17)))){
            Bitmap x=a.render(0),y=b.render(0);try{assertTrue(x.sameAs(y));}finally{x.recycle();y.recycle();}
        }
    }

    @Test public void invalidLastShotOrCameraPublishesNoBookSourcesOrSavedProgrammes()throws Exception {
        JSONObject args=plan();args.getJSONArray("shots").getJSONObject(1).getJSONArray("operations").put(new JSONObject().put("type","nonexistent_native_operation"));
        assertThrows(IllegalArgumentException.class,()->create(args));noBook(args);
        JSONObject camera=plan();camera.getJSONArray("shots").getJSONObject(1).put("camera",new JSONArray().put(new JSONObject().put("frame",999).put("zoom",1)));
        assertThrows(IllegalArgumentException.class,()->create(camera));noBook(camera);
    }

    @Test public void changedClockResolutionAndMissingGeneratedAudioRejectBeforePublication()throws Exception {
        for(JSONObject change:new JSONObject[]{new JSONObject().put("type","set_timing").put("fps",30),new JSONObject().put("type","set_canvas").put("width",720)}){
            JSONObject args=plan();args.getJSONArray("shots").getJSONObject(1).getJSONArray("operations").put(change);rejects(()->create(args),"CLOCK_OR_CANVAS");noBook(args);
        }
        JSONObject speech=plan();speech.getJSONArray("shots").getJSONObject(1).getJSONArray("operations").put(new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","traveller").put("settings",new JSONObject())
                .put("speech",new JSONObject().put("generated",new JSONObject().put("requestId","not_imported").put("lineIndex",0))));
        assertThrows(Exception.class,()->create(speech));noBook(speech);
    }

    @Test public void boundsDuplicateIdsUnknownFieldsAndTwoMiBPlanBudgetRejectWithoutBook()throws Exception {
        for(int count:new int[]{23,1201}){JSONObject args=plan();args.getJSONArray("shots").getJSONObject(1).put("frameCount",count);assertThrows(IllegalArgumentException.class,()->create(args));noBook(args);}
        JSONObject duplicate=plan();duplicate.getJSONArray("shots").getJSONObject(1).put("shotId","intro");assertThrows(IllegalArgumentException.class,()->create(duplicate));noBook(duplicate);
        JSONObject extra=plan().put("externalRenderer","anything");assertThrows(IllegalArgumentException.class,()->create(extra));noBook(extra);
        JSONObject oversized=plan().put("name","x".repeat(2*1024*1024));rejects(()->create(oversized),"2 MiB");noBook(oversized);
    }

    @Test public void existingBookAndOrphanPageAreNeverAdoptedOrOverwritten()throws Exception {
        JSONObject args=plan();SketchBookStore.execute(context,"sketch_book_create",new JSONObject().put("bookId",args.get("bookId")).put("operationId","other").put("pageCount",96).put("width",320).put("height",480).put("fps",24));
        String before=SketchDocument.canonical(book(args));rejects(()->create(args),"BOOK_EXISTS");assertEquals(before,SketchDocument.canonical(book(args)));
        JSONObject orphan=plan();try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.importOwnedDocument(SketchDocument.create(new JSONObject().put("sketchId",page(orphan,80))),"other_owner");}
        rejects(()->create(orphan),"OCCUPIED");noBook(orphan);
    }

    @Test public void exactCompletedRetryPreservesLaterPageBookAndSourceEdits()throws Exception {
        JSONObject args=plan(),result=create(args);rename(page(args,0));rename(result.getJSONArray("shots").getJSONObject(0).getString("sourceSketchId"));
        SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",args.get("bookId")).put("expectedRevision",book(args).getLong("revision")).put("operationId","human").put("name","Later book name"));
        assertEquals(SketchDocument.canonical(result),SketchDocument.canonical(create(new JSONObject(args.toString()))));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals("Human edits stay",sketches.get(page(args,0)).getString("name"));}
        rejects(()->create(new JSONObject(args.toString()).put("name","Different plan")),"OPERATION_ID_CONFLICT");
    }

    @Test public void cancellationWithinBatchResumesExactOriginalRevisionsAndPreservesHumanCel()throws Exception {
        JSONObject args=plan();rejects(()->SketchStoryPlanOps.create(context,args,(done,total)->{if(done==1)throw new InterruptedException("pause plan");}),"pause plan");
        assertEquals(1,book(args).getInt("createdPages"));assertEquals(3,book(args).getLong("revision"));rename(page(args,0));
        JSONObject result=create(args);assertEquals(96,result.getInt("frameCount"));assertEquals(96,book(args).getInt("createdPages"));assertEquals(5,book(args).getLong("revision"));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals("Human edits stay",sketches.get(page(args,0)).getString("name"));}
    }

    @Test public void editedPublishedSourceAndUnexpectedBookRevisionStopResumeWithoutOverwrites()throws Exception {
        JSONObject args=plan();rejects(()->SketchStoryPlanOps.create(context,args,(done,total)->{throw new InterruptedException("pause plan");}),"pause plan");
        String source=SketchAnimationOps.execute(context,"sketch_animation_list",new JSONObject()).getJSONArray("animations").getJSONObject(0).getString("sourceSketchId");rename(source);
        rejects(()->create(args),"SOURCE_CHANGED");assertEquals(1,book(args).getInt("createdPages"));
        JSONObject second=plan();rejects(()->SketchStoryPlanOps.create(context,second,(done,total)->{throw new InterruptedException("pause plan");}),"pause plan");
        SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",second.get("bookId")).put("expectedRevision",3).put("operationId","other").put("name","Human book change"));
        rejects(()->create(second),"BOOK_REVISION_CONFLICT");assertEquals(1,book(second).getInt("createdPages"));
    }

    @Test public void foreignPageAddedDuringCancellationIsPreservedAndBlocksRestOfBatch()throws Exception {
        JSONObject args=plan();rejects(()->SketchStoryPlanOps.create(context,args,(done,total)->{throw new InterruptedException("pause plan");}),"pause plan");
        SketchBookStore.execute(context,"sketch_book_open_page",new JSONObject().put("bookId",args.get("bookId")).put("page",10));
        rejects(()->create(args),"OCCUPIED");try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(page(args,1)));assertEquals(1,sketches.revision(page(args,10)));}
    }

    @Test public void preCancelledPlanPublishesNothing()throws Exception {
        JSONObject args=plan();Thread.currentThread().interrupt();try{rejects(()->create(args),"cancel");}finally{Thread.interrupted();}noBook(args);
    }

    @Test public void optionalCameraTargetsEveryAuthoredLayerAndOperationBoundsAreStrict()throws Exception {
        JSONObject args=plan(),shot=args.getJSONArray("shots").getJSONObject(0);shot.getJSONArray("operations").put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","actor_layer")));
        JSONObject result=create(args),saved=SketchAnimationOps.load(context,result.getJSONArray("shots").getJSONObject(0).getString("animationId"));
        JSONArray layers=saved.getJSONObject("program").getJSONArray("cameraLayerIds");assertEquals(2,layers.length());assertEquals("layer_1",layers.getString(0));assertEquals("actor_layer",layers.getString(1));
        JSONObject tooMany=plan();JSONArray operations=tooMany.getJSONArray("shots").getJSONObject(0).getJSONArray("operations");for(int i=operations.length();i<129;i++)operations.put(new JSONObject().put("type","set_canvas").put("background","#FF000000"));
        assertThrows(IllegalArgumentException.class,()->create(tooMany));try(SketchBookStore books=new SketchBookStore(context)){assertThrows(IllegalArgumentException.class,()->books.get(tooMany.getString("bookId")));}
    }

    @Test public void semanticPlanUsesRealStageHumanAndGripPropOperationsWithoutPrivateAuthoring()throws Exception {
        JSONObject args=plan().put("width",1280).put("height",720);JSONArray ops=new JSONArray()
                .put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","actors")))
                .put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","foreground")))
                .put(new JSONObject().put("type","add_story_stage").put("layerId","layer_1").put("foregroundLayerId","foreground").put("sceneId","crossing").put("settings",new JSONObject().put("location","forest_path").put("time","dusk").put("weather","clear")))
                .put(new JSONObject().put("type","add_human_animation").put("layerId","actors").put("actorId","traveller").put("settings",new JSONObject().put("size",.8).put("floorY",620/720d))
                        .put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("handOpen",0))))
                .put(new JSONObject().put("type","add_actor_prop").put("layerId","actors").put("propId","light").put("actorId","traveller").put("settings",new JSONObject().put("kind","lantern").put("attachment","near").put("scale",.8)));
        args.put("shots",new JSONArray().put(new JSONObject().put("shotId","arrival").put("frameCount",96).put("operations",ops)));
        JSONObject result=create(args),saved=SketchAnimationOps.load(context,result.getJSONArray("shots").getJSONObject(0).getString("animationId"));
        assertEquals(96,book(args).getInt("createdPages"));assertEquals("human",SketchDocument.object(saved.getJSONObject("sourceDocument"),"traveller_rig").getString("kind"));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){
            JSONObject cel=sketches.get(page(args,40));assertNotNull(SketchDocument.object(cel,"light_handle_0"));assertEquals("foreground",SketchDocument.owner(cel,"crossing_foreground_roots_0").getString("id"));assertEquals(40,SketchDocument.object(cel,"traveller_rig").getJSONObject("human").getInt("renderFrame"));
            assertEquals("without_near_hand",SketchDocument.object(cel,"traveller_rig").getJSONObject("human").getString("parts"));
            JSONObject grip=SketchDocument.object(cel,"light_grip");assertEquals("near_hand",grip.getJSONObject("human").getString("parts"));assertEquals(40,grip.getJSONObject("human").getInt("renderFrame"));
        }
    }
}
