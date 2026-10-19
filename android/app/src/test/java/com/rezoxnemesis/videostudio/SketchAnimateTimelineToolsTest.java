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

/** Animate-style authored edits must remain native, persisted, undoable and artist-safe. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public final class SketchAnimateTimelineToolsTest {
    private static JSONObject sketch() throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","animate_timeline")
                .put("frameCount",40).put("width",256).put("height",256));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects")
                .put(SketchEditOps.completeObject(new JSONObject()
                        .put("id","ball").put("kind","rect")
                        .put("frame",0).put("exposure",40)
                        .put("color","#FF3495B0").put("fill",true)
                        .put("bounds",new JSONObject().put("x",.10).put("y",.10)
                                .put("width",.10).put("height",.10))));
        SketchDocument.validate(doc);
        return doc;
    }
    private static JSONObject tween(String target,int first,int last,double x,String easing)throws Exception {
        return new JSONObject().put("type","motion_tween")
                .put(target.equals("layer_1")?"layerId":"objectId",target)
                .put("startFrame",first).put("endFrame",last)
                .put("destination",new JSONObject().put("x",x))
                .put("interpolation",easing);
    }
    private static void rejects(RunnableThrows action)throws Exception {
        try {action.run();fail("Expected the edit to be rejected");}
        catch(IllegalArgumentException | IllegalStateException expected){} 
    }
    private interface RunnableThrows {void run()throws Exception;}

    @Test public void objectTweenSamplesRealEasingWithoutMutatingSource()throws Exception {
        JSONObject source=sketch();
        String immutable=SketchDocument.canonical(source);
        JSONObject changed=SketchDocument.copy(source);
        SketchEditOps.apply(changed,tween("ball",0,20,.6,"ease_in_out"));
        assertEquals(immutable,SketchDocument.canonical(source));
        JSONObject o=SketchDocument.object(changed,"ball");
        assertEquals(2,o.getJSONArray("keys").length());
        assertEquals(0,SketchDocument.transformAt(o,0).getDouble("x"),.000001);
        assertEquals(.075,SketchDocument.transformAt(o,5).getDouble("x"),.000001);
        assertEquals(.3,SketchDocument.transformAt(o,10).getDouble("x"),.000001);
        assertEquals(.6,SketchDocument.transformAt(o,20).getDouble("x"),.000001);
        assertEquals(.6,SketchDocument.transformAt(o,30).getDouble("x"),.000001);
        JSONObject projection=SketchXsheetProjection.project(changed,0,25);
        JSONArray cells=projection.getJSONArray("columns").getJSONObject(0).getJSONArray("cells");
        assertTrue(cells.getJSONObject(0).getBoolean("transformKey"));
        assertFalse(cells.getJSONObject(10).optBoolean("transformKey"));
        assertTrue(cells.getJSONObject(20).getBoolean("transformKey"));
        assertEquals("drawing",cells.getJSONObject(0).getString("state"));
        assertEquals("hold",cells.getJSONObject(10).getString("state"));
    }

    @Test public void layerTweenMovesPreparedArtworkBySharedLayerTransform()throws Exception {
        JSONObject drawing=sketch();
        SketchEditOps.apply(drawing,tween("layer_1",0,16,.25,"linear"));
        JSONObject layer=SketchDocument.layer(drawing,"layer_1");
        assertEquals(.125,SketchDocument.transformAt(layer,8).getDouble("x"),.000001);
        assertEquals(.25,SketchDocument.transformAt(layer,16).getDouble("x"),.000001);
        assertEquals(0,SketchDocument.transformAt(SketchDocument.object(drawing,"ball"),8).getDouble("x"),.000001);
    }

    @Test public void existingIntermediateKeysAreNeverSilentlyDestroyed()throws Exception {
        JSONObject source=sketch();
        SketchEditOps.apply(source,SketchAnimateTimelineTools.insertKey(source,null,"ball",10,"hold"));
        String baseline=SketchDocument.canonical(source);
        rejects(()->SketchEditOps.apply(source,tween("ball",0,20,.5,"smooth")));
        assertEquals(baseline,SketchDocument.canonical(source));
    }

    @Test public void holdExtensionIsExplicitAndReusesExactlyOneDrawing()throws Exception {
        JSONObject source=sketch();
        SketchDocument.object(source,"ball").put("exposure",5);
        SketchDocument.validate(source);
        JSONObject request=tween("ball",0,20,.5,"smooth");
        String baseline=SketchDocument.canonical(source);
        rejects(()->SketchEditOps.apply(source,request));
        assertEquals(baseline,SketchDocument.canonical(source));
        request.put("extendExposure",true);
        SketchEditOps.apply(source,request);
        JSONObject object=SketchDocument.object(source,"ball");
        assertEquals(21,object.getInt("exposure"));
        assertEquals(2,object.getJSONArray("keys").length());
        assertTrue(SketchFrameRenderer.exposed(object,20));
        assertFalse(SketchFrameRenderer.exposed(object,21));
    }

    @Test public void cannotEditLockedLayersOrInventNonexistentFrames()throws Exception {
        JSONObject source=sketch();
        String before=SketchDocument.canonical(source);
        rejects(()->SketchEditOps.apply(source,tween("ball",4,43,.5,"smooth")));
        rejects(()->SketchEditOps.apply(source,tween("ball",20,8,.5,"smooth")));
        rejects(()->SketchEditOps.apply(source,tween("ball",0,20,10,"smooth")));
        rejects(()->SketchEditOps.apply(source,tween("ball",0,20,.5,"malicious")));
        assertEquals(before,SketchDocument.canonical(source));
        SketchDocument.layer(source,"layer_1").put("locked",true);
        rejects(()->SketchEditOps.apply(source,tween("ball",0,20,.5,"smooth")));
    }

    @Test public void keyInsertionAndRemovalUseOriginalNativeSchema()throws Exception {
        JSONObject drawing=sketch();
        SketchEditOps.apply(drawing,SketchAnimateTimelineTools.insertKey(drawing,null,"ball",6,"bezier"));
        JSONObject keyed=SketchAnimateTimelineTools.inspect(drawing,null,"ball",6);
        assertTrue(keyed.getBoolean("motionKey"));
        assertEquals("bezier",keyed.getString("interpolation"));
        SketchEditOps.apply(drawing,SketchAnimateTimelineTools.removeKey(drawing,null,"ball",6));
        assertFalse(SketchAnimateTimelineTools.inspect(drawing,null,"ball",6).getBoolean("motionKey"));
        rejects(()->SketchAnimateTimelineTools.removeKey(drawing,null,"ball",6));
    }

    @Test public void revisionBoundPublicEditIsIdempotentAndSupportsUndo()throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        JSONObject source=sketch().put("id","animate_"+UUID.randomUUID().toString().replace("-",""));
        String id=source.getString("id");
        try(SketchProjectStore store=new SketchProjectStore(context)){
            store.importOwnedDocument(source,"create");
            JSONObject args=new JSONObject().put("sketchId",id).put("expectedRevision",1)
                    .put("operationId","motion_1").put("operations",new JSONArray()
                            .put(tween("ball",0,20,.50,"smooth")));
            JSONObject first=SketchEditOps.execute(context,"sketch_edit",args);
            JSONObject replay=SketchEditOps.execute(context,"sketch_edit",args);
            assertEquals(first.toString(),replay.toString());
            assertEquals(2,store.get(id).getLong("revision"));
            assertEquals(.5,SketchDocument.transformAt(
                    SketchDocument.object(store.get(id),"ball"),20).getDouble("x"),.00001);
            JSONObject undo=new JSONObject().put("sketchId",id).put("expectedRevision",2)
                    .put("operationId","undo_1");
            SketchEditOps.execute(context,"sketch_undo",undo);
            assertEquals(0,SketchDocument.object(store.get(id),"ball")
                    .getJSONArray("keys").length());
        }
    }
}
