package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exact native admission: linked art really propagates; isolated overrides and history survive. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchMasterAssetsTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private String drawing()throws Exception {
        String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
            .put("sketchId",id).put("operationId","create").put("name","Linked art QA")
            .put("frameCount",180).put("fps",30).put("width",256).put("height",256));
        return id;
    }
    private JSONArray shape(double width)throws Exception {
        return new JSONArray().put(new JSONArray().put("M").put(.20).put(.24))
            .put(new JSONArray().put("C").put(.34).put(.10).put(.47).put(.10).put(.56).put(.26))
            .put(new JSONArray().put("Q").put(.72).put(.48).put(.52).put(.65))
            .put(new JSONArray().put("L").put(width).put(.76))
            .put(new JSONArray().put("Q").put(.27).put(.66).put(.20).put(.24))
            .put(new JSONArray().put("Z"));
    }
    private JSONObject run(String id,int revision,String op,JSONObject... changes)throws Exception{
        JSONArray a=new JSONArray();for(JSONObject change:changes)a.put(change);
        return SketchEditOps.execute(context,"sketch_edit",new JSONObject()
            .put("sketchId",id).put("expectedRevision",revision)
            .put("operationId",op).put("operations",a));
    }
    private static JSONObject op(String type)throws Exception{return new JSONObject().put("type",type);}
    private JSONObject begin(String id)throws Exception {
        run(id,1,"author",op("add_object").put("layerId","layer_1").put("object",
            new JSONObject().put("id","original_path").put("kind","path")
                .put("commands",shape(.49)).put("color","#FFAB5944")
                .put("width",.006).put("fill",true)
                .put("frame",0).put("exposure",180)));
        return run(id,2,"master",op("master_create")
            .put("masterId","coat_outline").put("sourceObjectId","original_path")
            .put("name","Coat silhouette"));
    }
    private JSONObject get(String id)throws Exception {
        try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}
    }
    private JSONObject cloneAt(int start,String name,double x,String color)throws Exception {
        JSONObject t=SketchDocument.transform().put("x",x);
        JSONObject change=op("master_instance").put("masterId","coat_outline")
            .put("objectId",name).put("layerId","layer_1")
            .put("frame",start).put("exposure",180-start).put("transform",t);
        if(color!=null)change.put("overrides",new JSONObject().put("color",color));
        return change;
    }
    private JSONArray modified()throws Exception {
        JSONArray a=shape(.64);
        a.getJSONArray(1).put(2,.13); // Curved, not a primitive bounding rectangle.
        return a;
    }

    @Test public void editOncePropagatesCurvesAndColorWhilePreservingLocalMotion()throws Exception{
        String id=drawing();begin(id);
        JSONObject copy=cloneAt(45,"another_angle",.17,null);
        copy.put("keys",new JSONArray().put(new JSONObject().put("frame",45)
            .put("transform",SketchDocument.transform().put("x",.17)).put("interpolation","smooth"))
            .put(new JSONObject().put("frame",142)
            .put("transform",SketchDocument.transform().put("x",.24)).put("interpolation","smooth")));
        run(id,3,"instance",copy);
        JSONObject preview=SketchEditOps.execute(context,"sketch_master_preview",
            new JSONObject().put("sketchId",id).put("expectedRevision",4)
                .put("masterId","coat_outline").put("patch",new JSONObject().put("color","#FF426997")));
        assertEquals(2,preview.getInt("instanceCount"));
        assertTrue(preview.getBoolean("ready"));assertEquals(0,preview.getJSONArray("localOverrideIds").length());
        assertEquals(4,get(id).getInt("revision")); // preview is read-only
        JSONObject request=new JSONObject().put("sketchId",id).put("expectedRevision",4)
            .put("operationId","recolor_and_reform")
            .put("operations",new JSONArray().put(op("master_patch").put("masterId","coat_outline")
                .put("expectedVersion",1).put("patch",new JSONObject()
                    .put("color","#FF426997").put("commands",modified()))));
        JSONObject done=SketchEditOps.execute(context,"sketch_edit",request);
        assertEquals(done.toString(),SketchEditOps.execute(context,"sketch_edit",request).toString());
        JSONObject doc=get(id),master=doc.getJSONArray("masterAssets").getJSONObject(0);
        assertEquals(2,master.getLong("version"));
        JSONObject original=SketchDocument.object(doc,"original_path"),instance=SketchDocument.object(doc,"another_angle");
        for(JSONObject o:new JSONObject[]{original,instance}) {
            assertEquals("#FF426997",o.getString("color"));
            assertEquals(2,o.getJSONObject("masterLink").getLong("version"));
            assertEquals(SketchDocument.canonical(modified()),SketchDocument.canonical(o.getJSONArray("commands")));
        }
        assertEquals(45,instance.getInt("frame"));assertEquals(135,instance.getInt("exposure"));
        assertEquals(2,instance.getJSONArray("keys").length());
        assertEquals(.24,instance.getJSONArray("keys").getJSONObject(1).getJSONObject("transform").getDouble("x"),1e-7);
        assertEquals(5,doc.getLong("revision"));
        SketchEditOps.execute(context,"sketch_undo",new JSONObject()
            .put("sketchId",id).put("expectedRevision",5).put("operationId","undo_master_change"));
        JSONObject after=get(id);
        assertEquals("#FFAB5944",SketchDocument.object(after,"original_path").getString("color"));
        assertEquals("#FFAB5944",SketchDocument.object(after,"another_angle").getString("color"));
    }

    @Test public void localOverridesSurviveMasterChangesAndDetachingEndsPropagation()throws Exception{
        String id=drawing();begin(id);
        run(id,3,"linked",cloneAt(30,"linked_one",.10,null),cloneAt(70,"custom_two",.20,"#FFD69A72"));
        assertEquals("#FFD69A72",SketchDocument.object(get(id),"custom_two").getString("color"));
        run(id,4,"change",op("master_patch").put("masterId","coat_outline").put("expectedVersion",1)
            .put("patch",new JSONObject().put("color","#FF3355AA").put("commands",modified())));
        JSONObject state=get(id);
        assertEquals("#FF3355AA",SketchDocument.object(state,"linked_one").getString("color"));
        assertEquals("#FFD69A72",SketchDocument.object(state,"custom_two").getString("color"));
        assertEquals(SketchDocument.canonical(modified()),SketchDocument.canonical(SketchDocument.object(state,"custom_two").getJSONArray("commands")));
        run(id,5,"detach",op("master_detach").put("objectId","linked_one"));
        run(id,6,"recolor",op("master_patch").put("masterId","coat_outline").put("expectedVersion",2)
            .put("patch",new JSONObject().put("color","#FF446611")));
        state=get(id);
        assertFalse(SketchDocument.object(state,"linked_one").has("masterLink"));
        assertEquals("#FF3355AA",SketchDocument.object(state,"linked_one").getString("color"));
        assertEquals("#FFD69A72",SketchDocument.object(state,"custom_two").getString("color"));
        run(id,7,"clear",op("master_clear_override").put("objectId","custom_two").put("field","color"));
        assertEquals("#FF446611",SketchDocument.object(get(id),"custom_two").getString("color"));
    }

    @Test public void directGeometryEditsAreRejectedAndLockedInstanceAbortsWholePatch()throws Exception{
        String id=drawing();begin(id);
        run(id,3,"linked",cloneAt(42,"copy",.13,null));
        try{
            run(id,4,"illegal",op("update_object").put("objectId","copy")
                .put("patch",new JSONObject().put("color","#FF000000")));fail();
        }catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("master_patch"));}
        assertEquals(4,get(id).getInt("revision"));
        run(id,4,"layer_lock",op("update_layer").put("layerId","layer_1")
            .put("patch",new JSONObject().put("locked",true)));
        try{
            run(id,5,"locked_master",op("master_patch").put("masterId","coat_outline")
                .put("expectedVersion",1).put("patch",new JSONObject().put("color","#FF000000")));fail();
        }catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("LOCKED"));}
        assertEquals(5,get(id).getInt("revision"));
        assertEquals(1,get(id).getJSONArray("masterAssets").getJSONObject(0).getInt("version"));
    }

    @Test public void malformedMasterCannotCorruptUnlinkedLegacyDocuments()throws Exception {
        String id=drawing();JSONObject legacy=get(id);
        assertFalse(legacy.has("masterAssets"));
        begin(id);
        JSONObject doc=get(id);
        JSONObject item=SketchDocument.object(doc,"original_path");
        item.put("commands",modified()); // unsanctioned source geometry mutation
        try{SketchDocument.validate(doc);fail();}catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("master"));
        }
        assertEquals(3,get(id).getLong("revision")); // persistent bytes are unchanged
        try{SketchEditOps.execute(context,"sketch_master_preview",new JSONObject()
            .put("sketchId",id).put("expectedRevision",2).put("masterId","coat_outline"));fail();}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("REVISION"));}
    }

    @Test public void componentSnapshotsDetachLinkedMastersWithoutBreakingArt()throws Exception {
        String source=drawing(),target=drawing();begin(source);
        JSONObject saved=SketchEditOps.execute(context,"sketch_component_save",new JSONObject()
            .put("componentId",UUID.randomUUID().toString()).put("sketchId",source).put("expectedRevision",3)
            .put("operationId","save_component").put("name","Frozen vector pose")
            .put("category","pose").put("frameIndex",0));
        assertTrue(saved.getBoolean("ok"));
        String componentId=saved.getString("componentId");
        SketchEditOps.execute(context,"sketch_component_apply",new JSONObject()
            .put("componentId",componentId).put("sketchId",target)
            .put("expectedRevision",1).put("operationId","paste").put("frameIndex",0));
        JSONObject doc=get(target);
        assertFalse(doc.has("masterAssets"));
        JSONObject pasted=doc.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0);
        assertFalse(pasted.has("masterLink"));
        assertEquals("path",pasted.getString("kind"));
    }
    @Test public void oneMasterPatchUpdatesOneHundredPlacedCopiesAcrossDifferentFrames()throws Exception {
        String id=drawing();begin(id);
        for(int batch=0;batch<2;batch++){
            JSONObject[] placements=new JSONObject[50];
            for(int j=0;j<50;j++){
                int i=batch*50+j,frame=(i*7)%150;
                JSONObject placement=cloneAt(frame,"linked_"+i,((i%11)-5)*.025,null);
                if(i%10==0) placement.put("keys",new JSONArray().put(
                    new JSONObject().put("frame",frame).put("interpolation","smooth")
                        .put("transform",SketchDocument.transform().put("x",((i%11)-5)*.025))));
                placements[j]=placement;
            }
            run(id,3+batch,"bulk_place_"+batch,placements);
        }
        JSONObject impact=SketchEditOps.execute(context,"sketch_master_preview",
            new JSONObject().put("sketchId",id).put("expectedRevision",5)
                .put("masterId","coat_outline"));
        assertEquals(101,impact.getInt("instanceCount"));
        assertEquals(0,impact.getJSONArray("lockedLayerIds").length());
        run(id,5,"one_mass_edit",op("master_patch").put("masterId","coat_outline")
            .put("expectedVersion",1).put("patch",new JSONObject().put("commands",modified())
                .put("color","#FF2E6399")));
        JSONObject state=get(id);
        assertEquals(6,state.getLong("revision"));
        for(int i=0;i<100;i++){
            JSONObject object=SketchDocument.object(state,"linked_"+i);
            assertEquals("#FF2E6399",object.getString("color"));
            assertEquals(SketchDocument.canonical(modified()),SketchDocument.canonical(object.getJSONArray("commands")));
            assertEquals((i*7)%150,object.getInt("frame"));
            assertEquals(180-(i*7)%150,object.getInt("exposure"));
            assertEquals(i%10==0?1:0,object.getJSONArray("keys").length());
        }
        assertEquals(2,state.getJSONArray("masterAssets").getJSONObject(0).getLong("version"));
    }

    @Test public void oneInvalidInstanceRollsBackAnEntireOptimizedPlacementBatch()throws Exception {
        String id=drawing();begin(id);
        JSONObject valid=cloneAt(18,"good_if_committed",.02,null);
        JSONObject invalid=cloneAt(18,"invalid_scale",.03,null);
        invalid.put("transform",SketchDocument.transform().put("scaleX",0));
        try {
            run(id,3,"atomic_bad",valid,invalid);fail();
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("scaleX"));
        }
        JSONObject after=get(id);
        assertEquals(3,after.getLong("revision"));
        assertEquals(1,after.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
        assertEquals(1,after.getJSONArray("masterAssets").getJSONObject(0).getLong("version"));
    }

}
