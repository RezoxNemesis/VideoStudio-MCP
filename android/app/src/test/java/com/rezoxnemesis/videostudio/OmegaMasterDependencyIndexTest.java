package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import org.json.JSONArray;
import android.content.Context;
import android.graphics.Bitmap;
import org.robolectric.RuntimeEnvironment;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33, manifest=Config.NONE)
public class OmegaMasterDependencyIndexTest {
    private static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static OmegaMasterDependencyIndex.Link link(String sketch, String object, boolean locked, boolean override, long version) {
        return new OmegaMasterDependencyIndex.Link("rose", "film", sketch, object, HASH, version, 7, locked, override);
    }
    @Test public void crossSketchImpactIsDeterministicAndPreservesLocalOverrideMetadata() {
        OmegaMasterDependencyIndex.Preview p=OmegaMasterDependencyIndex.preview(
            Arrays.asList(link("shotB","arm",false,true,2),link("shotA","arm",false,false,2)),
            "rose",HASH,2);
        assertTrue(p.ready());
        assertEquals(2,p.affected.size());
        assertEquals("shotA",p.affected.get(0).sketchId);
        assertEquals(1,p.overrideCount);
        assertEquals(7,p.affected.get(0).sketchRevision);
    }
    @Test public void locksAndStaleVersionsFailClosedWithoutMutation() {
        OmegaMasterDependencyIndex.Preview p=OmegaMasterDependencyIndex.preview(
            Arrays.asList(link("shotA","rose",true,false,2),link("shotB","rose",false,false,1)),
            "rose",HASH,2);
        assertFalse(p.ready());
        assertEquals(2,p.blockers.size());
        try {p.requireReady();fail("Locked and stale must block edits");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("MASTER_IMPACT_BLOCKED"));}
    }
    @Test public void emptyIndexAndDuplicatedIdentitiesAreRejected() {
        assertFalse(OmegaMasterDependencyIndex.preview(Collections.emptyList(),"rose",HASH,1).ready());
        OmegaMasterDependencyIndex.Link a=link("shotA","rose",false,false,1);
        try {OmegaMasterDependencyIndex.preview(Arrays.asList(a,a),"rose",HASH,1);fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Duplicate"));}
    }
    @Test public void sourceHashMustMatchExpected() {
        OmegaMasterDependencyIndex.Link a=link("shotA","rose",false,false,1);
        String other="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        assertFalse(OmegaMasterDependencyIndex.preview(Arrays.asList(a),"rose",other,1).ready());
    }

    /*
     * Native SketchDocument + SketchMasterAssets are exercised in memory.
     * No production SQLite multi-document writes or on-phone film claims here.
     */
    private static JSONArray contour(boolean stem) throws Exception {
        if (stem) return new JSONArray()
            .put(new JSONArray().put("M").put(.48).put(.29))
            .put(new JSONArray().put("C").put(.45).put(.46).put(.55).put(.67).put(.50).put(.83))
            .put(new JSONArray().put("L").put(.51).put(.84));
        return new JSONArray()
            .put(new JSONArray().put("M").put(.48).put(.31))
            .put(new JSONArray().put("C").put(.31).put(.17).put(.44).put(.10).put(.50).put(.24))
            .put(new JSONArray().put("C").put(.62).put(.10).put(.70).put(.23).put(.48).put(.31))
            .put(new JSONArray().put("Z"));
    }

    private static JSONObject source(String id, boolean stem) throws Exception {
        return new JSONObject()
            .put("id",id).put("kind","path")
            .put("commands",contour(stem))
            .put("color",stem?"#FF468765":"#FFCB6585")
            .put("fill",!stem).put("width",stem?.008:.006)
            .put("frame",0).put("exposure",180)
            .put("transform",SketchDocument.transform()).put("keys",new JSONArray());
    }

    private static JSONObject sampleShot(String sketchId, int copies) throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject()
            .put("sketchId",sketchId).put("name","Rose closeup")
            .put("frameCount",180).put("fps",30).put("width",256).put("height",256));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects")
            .put(source("petal_source",false)).put(source("stem_source",true));
        SketchMasterAssets.apply(doc,new JSONObject().put("type","master_create")
            .put("masterId","rose_petal").put("name","Rose petals")
            .put("sourceObjectId","petal_source"));
        SketchMasterAssets.apply(doc,new JSONObject().put("type","master_create")
            .put("masterId","rose_stem").put("name","Rose stem")
            .put("sourceObjectId","stem_source"));
        for(int i=0;i<copies;i++){
            int frame=1+(i*7)%115;
            for(String part:new String[]{"petal","stem"}){
                JSONObject transform=SketchDocument.transform().put("x",.001*(i+1));
                JSONArray keys=new JSONArray().put(new JSONObject()
                    .put("frame",frame).put("transform",SketchDocument.copy(transform))
                    .put("interpolation","smooth"))
                    .put(new JSONObject().put("frame",160)
                    .put("transform",SketchDocument.copy(transform).put("x",.10+.001*i))
                    .put("interpolation","smooth"));
                JSONObject operation=new JSONObject().put("type","master_instance")
                    .put("masterId","rose_"+part)
                    .put("layerId","layer_1").put("objectId",part+"_copy_"+i)
                    .put("frame",frame).put("exposure",180-frame)
                    .put("transform",transform).put("keys",keys);
                if("shotB".equals(sketchId)&&"petal".equals(part)&&i==6)
                    operation.put("overrides",new JSONObject().put("color","#FFEEBD78"));
                SketchMasterAssets.apply(doc,operation);
            }
        }
        SketchDocument.validate(doc);
        return doc;
    }

    private static Map<String,JSONObject> threeShots() throws Exception {
        Map<String,JSONObject> docs=new LinkedHashMap<>();
        docs.put("shotA",sampleShot("shotA",17));
        docs.put("shotB",sampleShot("shotB",17));
        docs.put("shotC",sampleShot("shotC",16));
        return docs;
    }

    private static String masterArtworkHash(JSONObject doc,String masterId)throws Exception {
        JSONObject master=SketchMasterAssets.indexed(doc).get(masterId);
        return SketchDocument.sha256(SketchDocument.canonical(master.getJSONObject("artwork"))
            .getBytes(StandardCharsets.UTF_8));
    }

    private static OmegaMasterDependencyIndex.CompoundRequest request(Map<String,JSONObject> docs)
            throws Exception {
        JSONObject sample=docs.get("shotA");
        List<OmegaMasterDependencyIndex.PartChange> parts=Arrays.asList(
            new OmegaMasterDependencyIndex.PartChange("rose_petal",1,
                masterArtworkHash(sample,"rose_petal"),new JSONObject()
                .put("commands",new JSONArray()
                    .put(new JSONArray().put("M").put(.45).put(.32))
                    .put(new JSONArray().put("C").put(.22).put(.12).put(.48).put(.08).put(.52).put(.20))
                    .put(new JSONArray().put("C").put(.74).put(.13).put(.72).put(.28).put(.45).put(.32))
                    .put(new JSONArray().put("Z")))
                .put("color","#FF9C588C")),
            new OmegaMasterDependencyIndex.PartChange("rose_stem",1,
                masterArtworkHash(sample,"rose_stem"),new JSONObject()
                .put("color","#FF438868").put("width",.009)));
        return new OmegaMasterDependencyIndex.CompoundRequest(
            "rose_character","film_alpha","operation_01",parts,new HashSet<>(docs.keySet()));
    }

    @Test public void compoundAcrossThreeShotsPreservesKeyframesOverridesAndMasterIdentity()
            throws Exception {
        Map<String,JSONObject> docs=threeShots();
        String exactBefore=SketchDocument.canonical(docs.get("shotB"));
        OmegaMasterDependencyIndex.CompoundRequest req=request(docs);
        OmegaMasterDependencyIndex.CompoundPreview preview=
            OmegaMasterDependencyIndex.previewCompound(req,docs);
        assertTrue(preview.blockers.toString(),preview.ready());
        // 2 original source paths per sketch + 100 linked copies = 106 links.
        assertEquals(106,preview.affected.size());
        assertEquals(1,preview.localOverrideCount);
        assertTrue(preview.totalExposureFrames>100);
        assertTrue(preview.receipt().getBoolean("readOnly"));
        assertFalse(preview.receipt().getBoolean("installed"));
        OmegaMasterDependencyIndex.PreparedBatch batch=
            OmegaMasterDependencyIndex.stageCompound(req,docs);
        assertEquals(3,batch.changedSketches());
        assertEquals(exactBefore,SketchDocument.canonical(docs.get("shotB")));
        Map<String,JSONObject> after=batch.nextDocuments();
        for(String name:docs.keySet()){
            JSONObject current=after.get(name);
            SketchDocument.validate(current);
            assertEquals(2,current.getLong("revision"));
            assertEquals(2,SketchMasterAssets.indexed(current).get("rose_petal").getInt("version"));
            assertEquals(2,SketchMasterAssets.indexed(current).get("rose_stem").getInt("version"));
            assertEquals("#FF9C588C",SketchDocument.object(current,"petal_copy_1").getString("color"));
            assertEquals("#FF438868",SketchDocument.object(current,"stem_copy_1").getString("color"));
            JSONObject earlier=SketchDocument.object(docs.get(name),"petal_copy_1");
            JSONObject later=SketchDocument.object(current,"petal_copy_1");
            assertEquals(SketchDocument.canonical(earlier.getJSONArray("keys")),
                SketchDocument.canonical(later.getJSONArray("keys")));
            assertEquals(SketchDocument.canonical(earlier.getJSONObject("transform")),
                SketchDocument.canonical(later.getJSONObject("transform")));
            assertEquals(earlier.getInt("frame"),later.getInt("frame"));
            assertEquals(earlier.getInt("exposure"),later.getInt("exposure"));
        }
        JSONObject local=SketchDocument.object(after.get("shotB"),"petal_copy_6");
        assertEquals("#FFEEBD78",local.getString("color"));
        assertEquals("#FFEEBD78",local.getJSONObject("masterLink")
            .getJSONObject("overrides").getString("color"));
        assertEquals(SketchDocument.canonical(
            SketchDocument.object(after.get("shotA"),"petal_copy_6").getJSONArray("commands")),
            SketchDocument.canonical(local.getJSONArray("commands")));
        // Defensive copies cannot mutate an already staged batch.
        after.get("shotA").put("name","MALICIOUS_EDIT");
        assertNotEquals("MALICIOUS_EDIT",batch.nextDocuments().get("shotA").getString("name"));
    }

    @Test public void lockedCrossShotInstanceFailsEntireBatchBeforeAnyMutation()throws Exception{
        Map<String,JSONObject> docs=threeShots();
        OmegaMasterDependencyIndex.CompoundRequest req=request(docs);
        docs.get("shotC").getJSONArray("layers").getJSONObject(0).put("locked",true);
        String beforeA=SketchDocument.canonical(docs.get("shotA"));
        String beforeB=SketchDocument.canonical(docs.get("shotB"));
        OmegaMasterDependencyIndex.CompoundPreview preview=
            OmegaMasterDependencyIndex.previewCompound(req,docs);
        assertFalse(preview.ready());
        assertTrue(preview.blockers.toString(),preview.blockers.toString().contains("LOCKED_LAYER:shotC"));
        try{OmegaMasterDependencyIndex.stageCompound(req,docs);fail("Locked art must abort");}
        catch(IllegalStateException expected){
            assertTrue(expected.getMessage().contains("COMPOUND_MASTER_BLOCKED"));
        }
        assertEquals(beforeA,SketchDocument.canonical(docs.get("shotA")));
        assertEquals(beforeB,SketchDocument.canonical(docs.get("shotB")));
    }

    @Test public void missingSketchAndSourceArtworkDriftRejectUnreliableIndex() throws Exception{
        Map<String,JSONObject> docs=threeShots();
        OmegaMasterDependencyIndex.CompoundRequest req=request(docs);
        Map<String,JSONObject> missing=new LinkedHashMap<>(docs);
        missing.remove("shotC");
        OmegaMasterDependencyIndex.CompoundPreview gap=
            OmegaMasterDependencyIndex.previewCompound(req,missing);
        assertFalse(gap.ready());
        assertTrue(gap.blockers.contains("INCOMPLETE_DEPENDENCY_MEMBERSHIP"));

        JSONObject changed=docs.get("shotB");
        SketchMasterAssets.apply(changed,new JSONObject().put("type","master_patch")
            .put("masterId","rose_petal").put("expectedVersion",1)
            .put("patch",new JSONObject().put("color","#FF996688")));
        SketchDocument.validate(changed);
        OmegaMasterDependencyIndex.CompoundPreview drift=
            OmegaMasterDependencyIndex.previewCompound(req,docs);
        assertFalse(drift.ready());
        assertTrue(drift.blockers.toString().contains("MASTER_ARTWORK_DRIFT"));
        assertTrue(drift.blockers.toString().contains("STALE_PART_VERSION"));
    }

    @Test public void stagedWorkRequiresExplicitAtomicNativeStore() throws Exception{
        Map<String,JSONObject> docs=threeShots();
        OmegaMasterDependencyIndex.PreparedBatch batch=
            OmegaMasterDependencyIndex.stageCompound(request(docs),docs);
        try{batch.commitAtomically(null);fail("No per-sketch fallback allowed");}
        catch(IllegalStateException expected){
            assertEquals("OMEGA_ATOMIC_NATIVE_ADAPTER_REQUIRED",expected.getMessage());
        }
        // Simulated authoritative CAS boundary; the real SQLite implementation
        // must be reviewed by the integration owner before phone deployment.
        class MemoryNativeStore implements OmegaMasterDependencyIndex.AtomicBatchCommitter {
            private Map<String,JSONObject> snapshots=new LinkedHashMap<>(docs);
            private String lastReceipt;
            @Override public void commit(OmegaMasterDependencyIndex.PreparedBatch edit)
                    throws Exception {
                if(lastReceipt!=null){
                    if(lastReceipt.equals(edit.fingerprint))return;
                    throw new IllegalStateException("COMPOUND_OPERATION_ID_CONFLICT");
                }
                Map<String,JSONObject> originals=edit.originalDocuments();
                for(String key:originals.keySet()){
                    JSONObject current=snapshots.get(key);
                    if(current==null || !SketchDocument.canonical(current)
                        .equals(SketchDocument.canonical(originals.get(key))))
                        throw new IllegalStateException("COMPOUND_CAS_CONFLICT");
                }
                Map<String,JSONObject> replacement=new LinkedHashMap<>(snapshots);
                replacement.putAll(edit.nextDocuments()); // atomic memory swap
                snapshots=replacement;
                lastReceipt=edit.fingerprint;
            }
        }
        MemoryNativeStore store=new MemoryNativeStore();
        batch.commitAtomically(store);
        batch.commitAtomically(store); // same exact operation ID + fingerprint
        assertEquals(2,store.snapshots.get("shotC").getLong("revision"));
    }

    @Test public void duplicateCompoundPartAndImproperSourceAreRejected() throws Exception {
        Map<String,JSONObject> docs=threeShots();
        OmegaMasterDependencyIndex.CompoundRequest normal=request(docs);
        try{new OmegaMasterDependencyIndex.CompoundRequest("rose","film_alpha",
            "operation_01",Arrays.asList(normal.parts.get(0),normal.parts.get(0)),
            new HashSet<>(docs.keySet()));fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Duplicate"));}
        try{new OmegaMasterDependencyIndex.PartChange("rose_petal",0,HASH,new JSONObject().put("color","#FFFFFFFF"));fail();}
        catch(IllegalArgumentException expected){}
        try{new OmegaMasterDependencyIndex.PartChange("rose_petal",1,HASH,new JSONObject().put("frame",10));fail();}
        catch(Exception expected){}
    }


    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test public void nativeVectorRendererSamplesEveryQuarterBeforeAndAfter() throws Exception {
        // A pixel-level native renderer smoke test is NOT a phone/device visual acceptance.
        Map<String,JSONObject> docs=threeShots();
        OmegaMasterDependencyIndex.PreparedBatch staged=
            OmegaMasterDependencyIndex.stageCompound(request(docs),docs);
        Map<String,JSONObject> updated=staged.nextDocuments();
        Context context=RuntimeEnvironment.getApplication();
        int[] frames={0,45,90,135,179};
        int changedPairs=0;
        for(String sketchId:new String[]{"shotA","shotB","shotC"}){
            try(SketchFrameRenderer original=new SketchFrameRenderer(context,docs.get(sketchId));
                SketchFrameRenderer modified=new SketchFrameRenderer(context,updated.get(sketchId))){
                for(int frame:frames){
                    Bitmap before=original.render(frame),after=modified.render(frame);
                    try{
                        assertEquals(256,before.getWidth());
                        assertEquals(256,after.getWidth());
                        int[] oldPixels=new int[256*256],newPixels=new int[256*256];
                        before.getPixels(oldPixels,0,256,0,0,256,256);
                        after.getPixels(newPixels,0,256,0,0,256,256);
                        int changes=0;
                        for(int i=0;i<oldPixels.length;i++)
                            if(oldPixels[i]!=newPixels[i])changes++;
                        assertTrue("No authored geometry/color changed at "+sketchId+"/"+frame,
                            changes>0);
                        changedPairs++;
                    }finally{
                        before.recycle();after.recycle();
                    }
                }
            }
        }
        assertEquals(15,changedPairs);
    }


    @Test public void placingCompoundInNewShotCreatesNativeEditableLinks() throws Exception {
        Map<String,JSONObject> docs=new LinkedHashMap<>();
        docs.put("shotA",sampleShot("shotA",5));
        docs.put("shotD",SketchDocument.create(new JSONObject()
            .put("sketchId","shotD").put("name","New viewpoint")
            .put("width",256).put("height",256).put("fps",30).put("frameCount",180)));
        String untouched=SketchDocument.canonical(docs.get("shotA"));
        OmegaMasterDependencyIndex.CompoundRequest req=request(docs);
        Map<String,String> newIds=new LinkedHashMap<>();
        newIds.put("rose_petal","new_petal");
        newIds.put("rose_stem","new_stem");
        JSONObject placement=SketchDocument.transform().put("x",.25)
            .put("rotation",12);
        JSONArray keys=new JSONArray()
            .put(new JSONObject().put("frame",20)
                .put("transform",SketchDocument.copy(placement))
                .put("interpolation","smooth"))
            .put(new JSONObject().put("frame",110)
                .put("transform",SketchDocument.copy(placement).put("x",.32))
                .put("interpolation","smooth"));
        OmegaMasterDependencyIndex.PreparedBatch placementBatch=
            OmegaMasterDependencyIndex.stageCompoundPlacement(req,docs,
                "shotA","shotD","layer_1",newIds,20,100,placement,keys);
        assertEquals(1,placementBatch.changedSketches());
        assertEquals(untouched,SketchDocument.canonical(docs.get("shotA")));
        assertEquals(0,docs.get("shotD").getJSONArray("layers")
            .getJSONObject(0).getJSONArray("objects").length());
        JSONObject placed=placementBatch.nextDocuments().get("shotD");
        assertEquals(2,placed.getLong("revision"));
        assertEquals(2,placed.getJSONArray("masterAssets").length());
        assertEquals(2,placed.getJSONArray("layers")
            .getJSONObject(0).getJSONArray("objects").length());
        JSONObject petal=SketchDocument.object(placed,"new_petal");
        assertEquals(20,petal.getInt("frame"));
        assertEquals(100,petal.getInt("exposure"));
        assertEquals(12,petal.getJSONObject("transform").getInt("rotation"));
        assertEquals(2,petal.getJSONArray("keys").length());
        assertEquals("rose_petal",petal.getJSONObject("masterLink").getString("id"));
        assertEquals(SketchDocument.canonical(
            SketchDocument.object(docs.get("shotA"),"petal_source").getJSONArray("commands")),
            SketchDocument.canonical(petal.getJSONArray("commands")));
        assertTrue(placementBatch.impact.ready());
        assertTrue(placementBatch.impact.affected.size()>10);

        // Having registered the new shot in the trusted transaction,
        // changing its master later propagates without destroying its local keys.
        OmegaMasterDependencyIndex.CompoundRequest revise=
            new OmegaMasterDependencyIndex.CompoundRequest("rose_character",
                "film_alpha","operation_02",req.parts,req.registeredSketchIds);
        OmegaMasterDependencyIndex.PreparedBatch changed=
            OmegaMasterDependencyIndex.stageCompound(revise,placementBatch.nextDocuments());
        JSONObject after=SketchDocument.object(changed.nextDocuments().get("shotD"),"new_petal");
        assertEquals("#FF9C588C",after.getString("color"));
        assertEquals(12,after.getJSONObject("transform").getInt("rotation"));
        assertEquals(2,after.getJSONArray("keys").length());
    }

    @Test public void newShotPlacementRejectsLockedLayersAndInconsistentGeometry()
            throws Exception {
        Map<String,JSONObject> docs=new LinkedHashMap<>();
        docs.put("shotA",sampleShot("shotA",1));
        docs.put("shotD",SketchDocument.create(new JSONObject()
            .put("sketchId","shotD").put("name","New viewpoint")
            .put("width",256).put("height",256).put("fps",30).put("frameCount",180)));
        OmegaMasterDependencyIndex.CompoundRequest req=request(docs);
        Map<String,String> newIds=new LinkedHashMap<>();
        newIds.put("rose_petal","new_petal");
        newIds.put("rose_stem","new_stem");
        docs.get("shotD").getJSONArray("layers").getJSONObject(0).put("locked",true);
        String pinned=SketchDocument.canonical(docs.get("shotD"));
        try{OmegaMasterDependencyIndex.stageCompoundPlacement(req,docs,
            "shotA","shotD","layer_1",newIds,15,60,
            SketchDocument.transform(),new JSONArray());fail("Locked target");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("LOCKED"));}
        assertEquals(pinned,SketchDocument.canonical(docs.get("shotD")));
        docs.get("shotD").getJSONArray("layers").getJSONObject(0).put("locked",false);
        docs.get("shotD").put("width",512);
        try{OmegaMasterDependencyIndex.stageCompoundPlacement(req,docs,
            "shotA","shotD","layer_1",newIds,15,60,
            SketchDocument.transform(),new JSONArray());fail("Geometry mismatch");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("geometry"));}
    }


    @Test public void groupUndoRevertsAllShotsAtomicallyInStaging()throws Exception {
        Map<String,JSONObject> docs=threeShots();
        OmegaMasterDependencyIndex.PreparedBatch forward=
            OmegaMasterDependencyIndex.stageCompound(request(docs),docs);
        OmegaMasterDependencyIndex.PreparedBatch undo=
            OmegaMasterDependencyIndex.stageCompoundUndo(forward,forward.nextDocuments(),"undo_group_01");
        assertEquals(3,undo.changedSketches());
        assertTrue(undo.impact.ready());
        for(String sketchId:docs.keySet()){
            JSONObject recovered=SketchDocument.copy(undo.nextDocuments().get(sketchId));
            assertEquals(3,recovered.getLong("revision"));
            recovered.put("revision",1L);
            assertEquals(SketchDocument.canonical(docs.get(sketchId)),
                SketchDocument.canonical(recovered));
            assertEquals(1,SketchMasterAssets.indexed(recovered).get("rose_petal")
                .getLong("version"));
        }
        try {
            OmegaMasterDependencyIndex.stageCompoundUndo(forward,forward.nextDocuments(),
                forward.operationId);
            fail("Undo cannot reuse original operation identity");
        }catch(IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("fresh"));
        }
    }

    @Test public void globalUndoRejectsInterveningShotChangeWithoutTouchingOthers()
            throws Exception {
        Map<String,JSONObject> original=threeShots();
        OmegaMasterDependencyIndex.PreparedBatch forward=
            OmegaMasterDependencyIndex.stageCompound(request(original),original);
        Map<String,JSONObject> current=new LinkedHashMap<>(forward.nextDocuments());
        current.get("shotB").put("name","Human changed the second shot");
        String unmodified=SketchDocument.canonical(current.get("shotA"));
        try{
            OmegaMasterDependencyIndex.stageCompoundUndo(forward,current,"undo_group_02");
            fail("Must reject group-wide stale document");
        }catch(IllegalStateException expected){
            assertTrue(expected.getMessage().contains("COMPOUND_UNDO_STALE_DOCUMENT:shotB"));
        }
        assertEquals(unmodified,SketchDocument.canonical(current.get("shotA")));
    }

}
