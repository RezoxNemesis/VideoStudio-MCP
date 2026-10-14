package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAnimationOpsTest {
    final Context context=RuntimeEnvironment.getApplication();
    private String id(){return UUID.randomUUID().toString();}
    private JSONObject source()throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId",id()).put("frameCount",1).put("width",320).put("height",480));
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","arm").put("kind","line").put("points",new JSONArray().put(new JSONArray().put(.4).put(.4).put(1)).put(new JSONArray().put(.6).put(.4).put(1)))));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.importOwnedDocument(d,"source");}return d;
    }
    private JSONObject program()throws Exception {
        return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",100).put("fps",24).put("rigs",new JSONArray().put(new JSONObject().put("id","hero").put("joints",new JSONArray().put(new JSONObject().put("id","root").put("x",.4).put("y",.4))).put("bindings",new JSONArray().put(new JSONObject().put("objectId","arm").put("jointId","root"))).put("poses",new JSONArray().put(pose(0,0)).put(pose(99,75))).put("ik",new JSONArray())));
    }
    private JSONObject pose(int frame,int angle)throws Exception{return new JSONObject().put("frame",frame).put("interpolation","linear").put("rotations",new JSONObject().put("root",angle));}
    private JSONObject save(String animation,JSONObject source)throws Exception{return new JSONObject().put("animationId",animation).put("sketchId",source.getString("id")).put("sourceRevision",source.getLong("revision")).put("expectedRevision",0).put("operationId","save").put("program",program());}
    private JSONObject call(String action,JSONObject args)throws Exception{return SketchAnimationOps.execute(context,"sketch_animation_"+action,args);}
    private JSONObject bake(String animation,String output,String operation)throws Exception{return new JSONObject().put("animationId",animation).put("expectedRevision",1).put("operationId",operation).put("outputSketchId",output);}
    private void rename(String sketch,long revision,String name)throws Exception{SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",sketch).put("expectedRevision",revision).put("operationId",id()).put("operations",new JSONArray().put(new JSONObject().put("type","set_canvas").put("name",name))));}

    @Test public void saveFreezesPreparedArtworkAndCanonicalRetriesHaveStableBoundedReceipts()throws Exception {
        JSONObject source=source();String animation=id();JSONObject request=save(animation,source),first=call("save",request);
        assertEquals(1,first.getLong("revision"));assertTrue(first.getBoolean("persisted"));assertFalse(first.has("sourceDocument"));assertFalse(first.has("document"));
        assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(call("save",new JSONObject(request.toString()))));
        rename(source.getString("id"),1,"Human changed the prepared artwork");
        JSONObject wire=call("get",new JSONObject().put("animationId",animation));assertFalse(wire.has("sourceDocument"));assertEquals(1,wire.getLong("sourceRevision"));
        JSONObject nativeCopy=SketchAnimationOps.load(context,animation);assertEquals(SketchDocument.canonical(source),SketchDocument.canonical(nativeCopy.getJSONObject("sourceDocument")));
        nativeCopy.getJSONObject("program").put("frameCount",5);assertEquals(100,SketchAnimationOps.load(context,animation).getJSONObject("program").getInt("frameCount"));
        assertEquals(1,call("list",new JSONObject()).getJSONArray("animations").length());
        JSONObject changed=new JSONObject(request.toString());changed.getJSONObject("program").put("fps",30);assertConflict(()->call("save",changed),"OPERATION_ID_CONFLICT");
    }

    @Test public void bakeCreatesEditableFramesAndRetryNeverOverwritesLaterHumanEdits()throws Exception {
        JSONObject source=source();String animation=id(),output=id();call("save",save(animation,source));JSONObject request=bake(animation,output,"bake");JSONObject first=call("bake",request);
        assertEquals(output,first.getString("outputSketchId"));assertEquals(1,first.getLong("outputRevision"));assertFalse(first.has("document"));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(SketchDocument.canonical(source),SketchDocument.canonical(sketches.get(source.getString("id"))));JSONObject baked=sketches.get(output);assertEquals(100,baked.getInt("frameCount"));assertEquals(75,SketchDocument.transformAt(SketchDocument.object(baked,"arm"),99).getDouble("rotation"),1e-8);}
        rename(output,1,"Human changed the animation");assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(call("bake",request)));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(2,sketches.revision(output));assertEquals("Human changed the animation",sketches.get(output).getString("name"));}
        assertConflict(()->call("bake",bake(animation,id(),"bake")),"OPERATION_ID_CONFLICT");
    }

    @Test public void staleSavesAndBakesAndExistingOutputIdsFailWithoutReplacingAnything()throws Exception {
        JSONObject source=source();String animation=id();JSONObject request=save(animation,source);call("save",request);
        assertConflict(()->call("save",new JSONObject(request.toString()).put("operationId","stale")),"REVISION_CONFLICT");
        assertConflict(()->call("bake",bake(animation,source.getString("id"),"source-overwrite")),"source");
        JSONObject existing=source();assertConflict(()->call("bake",bake(animation,existing.getString("id"),"collision")),"ALREADY_EXISTS");
        assertConflict(()->call("bake",bake(animation,id(),"stale-bake").put("expectedRevision",2)),"REVISION_CONFLICT");
        rename(source.getString("id"),1,"changed");assertConflict(()->call("save",save(id(),source)),"REVISION_CONFLICT");
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(1,sketches.revision(existing.getString("id")));}
    }

    @Test public void invalidProgramsAndUnsafeArgumentsDoNotPersistAnAnimation()throws Exception {
        JSONObject source=source();JSONObject bad=save(id(),source);bad.getJSONObject("program").put("unknown",true);assertThrows(IllegalArgumentException.class,()->call("save",bad));
        assertThrows(IllegalArgumentException.class,()->call("save",save("../escape",source)));
        assertThrows(IllegalArgumentException.class,()->call("save",save(id(),source).put("sourceRevision",1.5)));
        assertThrows(IllegalArgumentException.class,()->call("list",new JSONObject().put("extra",true)));
        JSONObject oversized=save(id(),source);oversized.getJSONObject("program").put("unknown","x".repeat(512*1024));assertThrows(IllegalArgumentException.class,()->call("save",oversized));
        assertEquals(0,call("list",new JSONObject()).getJSONArray("animations").length());
    }

    @Test public void updatedProgramsUseAnimationCASAndOldSaveRetriesStayAuthoritative()throws Exception {
        JSONObject source=source();String animation=id();JSONObject firstRequest=save(animation,source),first=call("save",firstRequest);
        JSONObject secondRequest=new JSONObject(firstRequest.toString()).put("expectedRevision",1).put("operationId","save-two");secondRequest.getJSONObject("program").put("fps",30);
        assertEquals(2,call("save",secondRequest).getLong("revision"));assertEquals(30,call("get",new JSONObject().put("animationId",animation)).getJSONObject("program").getInt("fps"));
        assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(call("save",firstRequest)));
        assertConflict(()->call("bake",bake(animation,id(),"old-program")),"REVISION_CONFLICT");
    }

    @Test public void savedAnimationRemainsAuthorableFromFrozenSourceAfterHumanEditsOriginal()throws Exception {
        JSONObject source=source();String animation=id();JSONObject firstRequest=save(animation,source),first=call("save",firstRequest);rename(source.getString("id"),1,"A later human drawing");
        JSONObject update=new JSONObject(firstRequest.toString()).put("expectedRevision",1).put("operationId","continue-frozen");update.getJSONObject("program").remove("fps");
        JSONObject second=call("save",update);assertEquals(2,second.getLong("revision"));assertEquals(24,second.getInt("fps"));assertEquals(first.getString("sourceSha256"),second.getString("sourceSha256"));
        String output=id();call("bake",bake(animation,output,"bake-frozen").put("expectedRevision",2));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals("A later human drawing",sketches.get(source.getString("id")).getString("name"));assertEquals(2,sketches.revision(source.getString("id")));}
        assertEquals(SketchDocument.canonical(source),SketchDocument.canonical(SketchAnimationOps.load(context,animation).getJSONObject("sourceDocument")));
    }

    @Test public void frozenImageDependenciesAreCopiedAndTamperingIsRejectedBeforePublication()throws Exception {
        JSONObject source=source();String sourceId=source.getString("id");byte[] bytes="owned synthetic reference bytes".getBytes(StandardCharsets.UTF_8);String sha=SketchDocument.sha256(bytes);
        File refs=new File(context.getFilesDir(),"sketch_studio/"+sourceId+"/references");assertTrue(refs.mkdirs());Files.write(new File(refs,sha+".png").toPath(),bytes);
        JSONObject image=SketchEditOps.completeObject(new JSONObject().put("id","background").put("kind","image").put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)).put("reference",new JSONObject().put("file",sha+".png").put("sha256",sha).put("width",32).put("height",32).put("sourceProjectId","owned_project").put("sourceAssetId","owned_asset")));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.mutate(sourceId,"reference",1,"a".repeat(64),d->{d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(image);return d;});source=sketches.get(sourceId);}
        String animation=id(),output=id();call("save",save(animation,source));call("bake",bake(animation,output,"bake"));File copy=new File(context.getFilesDir(),"sketch_studio/"+output+"/references/"+sha+".png");assertArrayEquals(bytes,Files.readAllBytes(copy.toPath()));
        Files.write(new File(refs,sha+".png").toPath(),new byte[]{1});String rejectedOutput=id();assertConflict(()->call("bake",bake(animation,rejectedOutput,"tampered")),"REFERENCE_CHANGED");
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(rejectedOutput));sketches.verifyReferences(sketches.get(output));}
    }

    @Test public void publicationCrashRecoveryReusesOwnReceiptAndPreservesHumanChanges()throws Exception {
        JSONObject source=source();String animation=id(),output=id();call("save",save(animation,source));JSONObject request=bake(animation,output,"bake");JSONObject first=call("bake",request);rename(output,1,"Human edited after publication");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("sketch_animation_v1.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("UPDATE animation_operations SET result=NULL WHERE animation_id=? AND operation_id=?",new Object[]{animation,"bake"});}
        assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(call("bake",request)));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(2,sketches.revision(output));assertEquals("Human edited after publication",sketches.get(output).getString("name"));}
    }

    @Test public void ownedPaintPixelsAndJournalAreCopiedAndRemainEditableUnderTheNewIdentity()throws Exception {
        JSONObject source=source();String sourceId=source.getString("id");
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",sourceId).put("expectedRevision",1).put("operationId","paint").put("operations",new JSONArray()
                .put(new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","paint_background"))
                .put(new JSONObject().put("type","paint_stroke").put("objectId","paint_background").put("strokeId","authored_stroke").put("points",new JSONArray().put(new JSONArray().put(.1).put(.5).put(1)).put(new JSONArray().put(.9).put(.5).put(1))).put("brush",new JSONObject().put("mode","paint").put("color","#FFAA2233").put("sizePx",10)))));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){source=sketches.get(sourceId);}
        String animation=id(),output=id();call("save",save(animation,source));call("bake",bake(animation,output,"bake"));
        try(SketchProjectStore sketches=new SketchProjectStore(context);SketchPaintStore copied=new SketchPaintStore(context,output)){
            JSONObject paint=SketchDocument.object(sketches.get(output),"paint_background").getJSONObject("paint");copied.verify(paint);assertEquals("#FFAA2233",copied.sample(paint,.5,.5));assertEquals(1,copied.journal(paint,0,10).length());
        }
        // Imported stroke intent is seeded in the target. Reusing its stroke ID cannot draw twice.
        assertConflict(()->SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",output).put("expectedRevision",1).put("operationId","duplicate-stroke").put("operations",new JSONArray().put(new JSONObject().put("type","paint_stroke").put("objectId","paint_background").put("strokeId","authored_stroke").put("points",new JSONArray().put(new JSONArray().put(.1).put(.5).put(1))).put("brush",new JSONObject().put("mode","paint").put("color","#FFAA2233").put("sizePx",10))))),"STROKE_ID_CONFLICT");
    }

    @Test public void frozenSourceCorruptionFailsInsteadOfExecutingUnverifiedGeometry()throws Exception {
        JSONObject source=source();String animation=id();call("save",save(animation,source));
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("sketch_animation_v1.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            JSONObject record=new JSONObject(SQLiteLargeText.read(db,"SELECT json FROM animations WHERE id=?",animation));record.getJSONObject("sourceDocument").put("name","corrupted snapshot");db.execSQL("UPDATE animations SET json=? WHERE id=?",new Object[]{record.toString(),animation});
        }
        assertConflict(()->call("get",new JSONObject().put("animationId",animation)),"SNAPSHOT_CHANGED");String output=id();assertConflict(()->call("bake",bake(animation,output,"bake")),"SNAPSHOT_CHANGED");
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(output));}
    }

    @Test public void largeFrozenSourceSurvivesDatabaseReopenWithoutEnteringCommandReceipts()throws Exception {
        JSONObject source=source();String sourceId=source.getString("id");JSONArray points=new JSONArray();for(int i=0;i<72000;i++)points.put(new JSONArray().put(.123456789).put(.234567891).put(.876543219));
        JSONObject detail=SketchEditOps.completeObject(new JSONObject().put("id","authored_background_detail").put("kind","stroke").put("points",points));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.mutate(sourceId,"large-detail",1,"b".repeat(64),d->{d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(detail);return d;});source=sketches.get(sourceId);}
        assertTrue(source.toString().getBytes(StandardCharsets.UTF_8).length>2*1024*1024);String animation=id();JSONObject receipt=call("save",save(animation,source));assertTrue(receipt.toString().length()<64*1024);
        JSONObject nativeCopy=SketchAnimationOps.load(context,animation);assertEquals(72000,SketchDocument.object(nativeCopy.getJSONObject("sourceDocument"),"authored_background_detail").getJSONArray("points").length());
        JSONObject wire=call("get",new JSONObject().put("animationId",animation));assertFalse(wire.has("sourceDocument"));assertEquals(receipt.getString("sourceSha256"),wire.getString("sourceSha256"));assertTrue(wire.toString().length()<64*1024);
    }

    private interface Checked {void run()throws Exception;}
    private void assertConflict(Checked action,String detail)throws Exception{try{action.run();fail("Expected rejection: "+detail);}catch(IllegalArgumentException|IllegalStateException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(detail));}}
}
