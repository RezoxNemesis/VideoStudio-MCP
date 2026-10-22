package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.InterruptedIOException;
import java.util.UUID;
import static org.junit.Assert.*;

/** Additional guards supplement, rather than change, the six frozen native RED assertions. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAuthoredCelReuseBoundaryTest {
    final SketchAuthoredCelReuseTest core=new SketchAuthoredCelReuseTest();
    @Test public void exactReceiptSurvivesAdvancingTheOriginalDrawingStage()throws Exception {core.worker(()->{
        SketchAuthoredCelReuseTest.Fixture f=core.new Fixture(false);
        SketchEditOps.execute(core.context,"sketch_live_begin",new JSONObject().put("sketchId",f.id).put("expectedRevision",f.doc().getLong("revision")).put("operationId","original").put("drawingMethod","original_strokes"));
        JSONObject request=f.args(SketchAuthoredCelReuseTest.copy("actor_construction",0,1,"owned")),receipt=SketchEditOps.execute(core.context,"sketch_edit",request);
        f.edit(new JSONObject().put("type","layered_drawing_review").put("stage","construction").put("reviewedRevision",f.doc().getLong("revision")).put("status","ACCEPTABLE").put("note","Reviewed the retained native test outline."));
        f.edit(new JSONObject().put("type","layered_drawing_stage").put("stage","refinement"));JSONObject advanced=f.doc();
        assertEquals(SketchDocument.canonical(receipt),SketchDocument.canonical(SketchEditOps.execute(core.context,"sketch_edit",request)));f.sameDocument(advanced);
        assertThrows(IllegalStateException.class,()->f.edit(SketchAuthoredCelReuseTest.copy("actor_construction",0,2,"inactive")));f.sameDocument(advanced);
    });}
    @Test public void layeredTailReplayIsDeterministicAndIdCollisionIsAtomic()throws Exception {core.worker(()->{
        SketchAuthoredCelReuseTest.Fixture f=core.new Fixture(false);JSONObject source=f.doc(),left=SketchDocument.copy(source),right=SketchDocument.copy(source),copy=SketchAuthoredCelReuseTest.copy("actor_construction",0,1,"stable");
        SketchEditOps.apply(left,copy);SketchEditOps.apply(right,copy);assertEquals(SketchDocument.canonical(left),SketchDocument.canonical(right));assertEquals(2,SketchDocument.object(left,"stable_tail_0").getInt("frame"));
        f.edit(new JSONObject().put("type","add_object").put("layerId","actor_construction").put("object",new JSONObject().put("id","collision_tail_0").put("kind","stroke").put("points",new JSONArray("[[.1,.1,1],[.2,.1,1]]"))));
        JSONObject before=f.doc();assertThrows(IllegalArgumentException.class,()->f.edit(SketchAuthoredCelReuseTest.copy("actor_construction",0,1,"collision")));f.sameDocument(before);
    });}
    @Test public void differentLayerTransformsInvisibleLayersAndMixedEditsCannotCopy()throws Exception {core.worker(()->{
        SketchAuthoredCelReuseTest.Fixture f=core.new Fixture(false);JSONObject before=f.doc(),operation=SketchAuthoredCelReuseTest.copy("actor_construction",0,1,"blocked");
        for(String mode:new String[]{"opacity","keyOpacity","rotation"}){
            JSONObject candidate=SketchDocument.copy(before),layer=SketchDocument.layer(candidate,"actor_construction");
            if("opacity".equals(mode))layer.put("opacity",0);
            else{JSONObject transform=SketchDocument.copy(layer.getJSONObject("transform")).put("keyOpacity".equals(mode)?"opacity":"rotation","keyOpacity".equals(mode)?0:13);layer.put("keys",new JSONArray().put(new JSONObject().put("frame",1).put("transform",transform).put("interpolation","hold")));}
            SketchDocument.validate(candidate);assertThrows(IllegalStateException.class,()->SketchEditOps.apply(candidate,operation));
        }
        JSONObject added=new JSONObject().put("type","add_object").put("layerId","actor_construction").put("object",new JSONObject().put("id","uncommitted").put("kind","stroke").put("points",new JSONArray("[[.1,.1,1],[.2,.1,1]]")));
        JSONObject mixed=f.args(operation).put("operations",new JSONArray().put(added).put(operation));assertThrows(IllegalStateException.class,()->SketchEditOps.execute(core.context,"sketch_edit",mixed));f.sameDocument(before);
        JSONObject unknown=SketchDocument.copy(operation).put("sourceReference","guide");assertThrows(IllegalArgumentException.class,()->f.edit(unknown));f.sameDocument(before);
    });}
    @Test public void copiedMeshPoseFreezesNativePixelsWithoutReplacingThePaintSource()throws Exception {core.worker(()->{
        SketchAuthoredCelReuseTest.Fixture f=core.new Fixture(true);JSONObject preparation=SketchPaintMeshPrepare.prepare(core.context,new JSONObject().put("sketchId",f.id).put("expectedRevision",f.doc().getLong("revision")).put("objectId","paint").put("columns",2).put("rows",2));
        JSONObject op=preparation.getJSONObject("operation"),mesh=op.getJSONObject("patch").getJSONObject("paintMesh");JSONArray first=new JSONArray(mesh.getJSONArray("points").toString()),posed=new JSONArray(first.toString());posed.getJSONArray(4).put(0,.57).put(1,.45);mesh.put("keys",new JSONArray().put(new JSONObject().put("frame",0).put("points",first).put("interpolation","hold")).put(new JSONObject().put("frame",1).put("points",posed).put("interpolation","hold")));f.edit(op);
        JSONObject before=f.doc();String source=SketchDocument.canonical(SketchDocument.object(before,"paint").getJSONObject("paint"));f.edit(SketchAuthoredCelReuseTest.copy("actor_construction",1,2,"posed_copy"));JSONObject after=f.doc(),copied=SketchDocument.object(after,"posed_copy_0");
        assertEquals(source,SketchDocument.canonical(copied.getJSONObject("paint")));assertEquals(0,copied.getJSONObject("paintMesh").getJSONArray("keys").length());assertEquals(SketchDocument.canonical(new JSONObject().put("points",posed)),SketchDocument.canonical(new JSONObject().put("points",copied.getJSONObject("paintMesh").getJSONArray("points"))));
        assertArrayEquals(core.pixels(before,1),core.pixels(after,2));assertArrayEquals(core.pixels(before,0),core.pixels(after,0));
        SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(core.context,new JSONObject().put("sketchId",f.id).put("expectedRevision",after.getLong("revision")).put("operationId","archive_mesh_copy"));JSONObject restored=SketchPaintArchive.restoreFile(core.context,archive.file,SketchProjectStore.fileHash(archive.file),"restored_"+UUID.randomUUID(),"Reused mesh cel");try(SketchProjectStore store=new SketchProjectStore(core.context)){assertArrayEquals(core.pixels(after,2),core.pixels(store.get(restored.getString("sketchId")),2));}
    });}
    @Test public void cancellationRetainsTheSourceAndTheIdenticalRequestCanRetry()throws Exception {core.worker(()->{
        SketchAuthoredCelReuseTest.Fixture f=core.new Fixture(true);JSONObject before=f.doc(),request=f.args(SketchAuthoredCelReuseTest.copy("actor_construction",0,1,"retry"));
        Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->SketchEditOps.execute(core.context,"sketch_edit",request));}finally{Thread.interrupted();}f.sameDocument(before);
        JSONObject receipt=SketchEditOps.execute(core.context,"sketch_edit",request);assertEquals(SketchDocument.canonical(receipt),SketchDocument.canonical(SketchEditOps.execute(core.context,"sketch_edit",request)));assertArrayEquals(core.pixels(before,0),core.pixels(f.doc(),1));
    });}
    @Test public void aLaterSourceFreeStrokeCannotHideEarlierCloneAncestry()throws Exception {core.worker(()->{
        SketchAuthoredCelReuseTest.Fixture f=core.new Fixture(true);JSONObject forged=f.doc(),detached=SketchDocument.copy(forged);detached.remove("layeredDrawing");
        JSONObject clone=SketchAuthoredCelReuseTest.paint("paint","cloned_ancestor",.5,.5,"#FFFFFFFF");clone.getJSONObject("brush").put("mode","clone");clone.put("cloneSource",new JSONObject().put("objectId","guide").put("sha256",SketchDocument.object(detached,"guide").getJSONObject("reference").getString("sha256")));
        try(SketchPaintStore assets=new SketchPaintStore(core.context,f.id)){
            SketchPaintStore.PreparedChange borrowed=assets.prepare(detached,clone);SketchDocument.object(detached,"paint").put("paint",borrowed.descriptor);
            SketchPaintStore.PreparedChange repainted=assets.prepare(detached,SketchAuthoredCelReuseTest.paint("paint","later_authored",.5,.5,"#FF445566"));SketchDocument.object(forged,"paint").put("paint",repainted.descriptor);
            assertFalse(repainted.visualOperation.getJSONObject("stroke").has("source"));assertThrows(IllegalArgumentException.class,()->assets.requireAuthoredStageSource(repainted.descriptor));
        }
        String target="old_clone_"+UUID.randomUUID();try(SketchProjectStore store=new SketchProjectStore(core.context)){store.copyOwnedDependencies(forged,target);forged.put("id",target).put("revision",1);store.importOwnedDocument(forged,"restore_old_clone");}
        JSONObject request=new JSONObject().put("sketchId",target).put("expectedRevision",1).put("operationId","deny_old_clone").put("operations",new JSONArray().put(SketchAuthoredCelReuseTest.copy("actor_construction",0,1,"copy")));
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(core.context,"sketch_edit",request));try(SketchProjectStore store=new SketchProjectStore(core.context)){assertEquals(SketchDocument.canonical(forged),SketchDocument.canonical(store.get(target)));}
    });}
}
