package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import static org.junit.Assert.*;

/** Pure geometry, exact native keys and locked-source protection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAgentGestureOpsTest {
    static JSONObject drawing()throws Exception{
        JSONObject doc=SketchDocument.create(new JSONObject()
                .put("sketchId","gesture-"+UUID.randomUUID())
                .put("name","Human-like agent controls")
                .put("width",512).put("height",768)
                .put("frameCount",12).put("fps",24));
        JSONObject layer=doc.getJSONArray("layers").getJSONObject(0);
        JSONObject box=SketchEditOps.completeObject(new JSONObject()
                .put("id","ink_rect")
                .put("kind","rect").put("frame",0).put("exposure",12)
                .put("bounds",new JSONObject().put("x",.2).put("y",.3)
                        .put("width",.25).put("height",.2)));
        layer.getJSONArray("objects").put(box);
        JSONObject path=SketchEditOps.completeObject(new JSONObject()
                .put("id","edit_curve").put("kind","path")
                .put("frame",0).put("exposure",12)
                .put("commands",new JSONArray()
                        .put(new JSONArray().put("M").put(.1).put(.2))
                        .put(new JSONArray().put("Q").put(.5).put(.8).put(.8).put(.2))));
        layer.getJSONArray("objects").put(path);
        SketchDocument.validate(doc);
        return doc;
    }
    static JSONObject move(int frame)throws Exception {
        return new JSONObject().put("action","object_drag")
                .put("objectId","ink_rect").put("frameIndex",frame)
                .put("x",.20).put("y",-.1).put("rotation",12)
                .put("durationMs",360);
    }
    static JSONObject node(int frame)throws Exception {
        return new JSONObject().put("action","node_drag")
                .put("objectId","edit_curve").put("frameIndex",frame)
                .put("commandIndex",1).put("pairIndex",0)
                .put("x",.55).put("y",.60).put("durationMs",360);
    }

    @Test public void originalObjectTransformPreviewsRealIntermediateGeometryWithoutMutatingSource()throws Exception{
        JSONObject doc=drawing(),before=SketchDocument.copy(doc);
        SketchAgentGestureOps.Plan plan=SketchAgentGestureOps.plan(doc,move(4));
        assertFalse(plan.editingNode());
        assertEquals("set_transform",plan.editOperation.getString("type"));
        assertEquals(4,plan.frameIndex);
        assertEquals(0,plan.previewTransform(0).getDouble("x"),.000001);
        assertEquals(.2,plan.previewTransform(1).getDouble("x"),.000001);
        assertEquals(.1,plan.previewTransform(.5f).getDouble("x"),.001);
        assertEquals(-.05,plan.previewTransform(.5f).getDouble("y"),.001);
        assertEquals(6,plan.previewTransform(.5f).getDouble("rotation"),.001);
        assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(doc));
        double[] p0=plan.cursor(0),p1=plan.cursor(1);
        assertTrue(p1[0]>p0[0]);
        assertTrue(p1[1]<p0[1]);
        SketchAgentHand.State hand=SketchAgentHand.State.manipulation(p1[0],p1[1],
                plan.objectId,"Select / transform","Moving original vector");
        assertTrue(hand.visible);assertFalse(hand.drawing);
        assertEquals("ink_rect",hand.objectId);
    }

    @Test public void animatedObjectCreatesExactKeyAndRetainsExistingPoseGraph()throws Exception{
        JSONObject doc=drawing(),obj=SketchDocument.object(doc,"ink_rect");
        obj.getJSONArray("keys")
                .put(new JSONObject().put("frame",0)
                        .put("interpolation","ease_in_out").put("transform",SketchDocument.transform()))
                .put(new JSONObject().put("frame",8)
                        .put("interpolation","linear")
                        .put("transform",SketchDocument.transform().put("x",.08)));
        SketchDocument.validate(doc);
        JSONObject request=move(4).put("interpolation","bezier")
                .put("curve",new JSONArray().put(.28).put(.1).put(.7).put(.9));
        SketchAgentGestureOps.Plan plan=SketchAgentGestureOps.plan(doc,request);
        assertEquals("set_keyframe",plan.editOperation.getString("type"));
        assertEquals(4,plan.editOperation.getInt("frame"));
        assertEquals("bezier",plan.editOperation.getString("interpolation"));
        assertEquals(4,plan.editOperation.getJSONArray("curve").length());
        SketchEditOps.apply(doc,plan.editOperation);
        assertEquals(3,SketchDocument.object(doc,"ink_rect").getJSONArray("keys").length());
        assertEquals(.2,SketchDocument.transformAt(SketchDocument.object(doc,"ink_rect"),4)
                .getDouble("x"),.000001);
    }

    @Test public void nodeDragOnlyChangesSelectedRealBezierPair()throws Exception {
        JSONObject doc=drawing(),before=SketchDocument.copy(doc);
        SketchAgentGestureOps.Plan plan=SketchAgentGestureOps.plan(doc,node(3));
        assertTrue(plan.editingNode());
        assertEquals("move_path_node",plan.editOperation.getString("type"));
        JSONArray start=plan.previewNode(0).getJSONArray("commands"),
                middle=plan.previewNode(.5f).getJSONArray("commands"),
                end=plan.previewNode(1).getJSONArray("commands");
        assertEquals(.5,start.getJSONArray(1).getDouble(1),.000001);
        assertEquals(.8,start.getJSONArray(1).getDouble(2),.000001);
        assertEquals(.525,middle.getJSONArray(1).getDouble(1),.000001);
        assertEquals(.7,middle.getJSONArray(1).getDouble(2),.000001);
        assertEquals(.55,end.getJSONArray(1).getDouble(1),.000001);
        assertEquals(.60,end.getJSONArray(1).getDouble(2),.000001);
        assertEquals(.8,end.getJSONArray(1).getDouble(3),.000001);
        assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(doc));
        SketchEditOps.apply(doc,plan.editOperation);
        assertEquals(.55,SketchShapeKeys.commandsAt(SketchDocument.object(doc,"edit_curve"),3)
                .getJSONArray(1).getDouble(1),.000001);
    }

    @Test public void maliciousOrInvisibleGestureCannotEditOriginalArt()throws Exception{
        JSONObject source=drawing(),unchanged=SketchDocument.copy(source);
        JSONObject[] invalid={
            move(4).put("x",5),
            move(4).put("opacity",-1),
            move(4).put("url","content://gallery"),
            move(50),
            move(4).put("objectId","missing"),
            node(4).put("commandIndex",300),
            node(4).put("pairIndex",9),
            node(4).put("y",Double.NaN),
            move(4).put("x",0).put("y",0).put("rotation",0),
            move(4).put("durationMs",10000)
        };
        for(JSONObject op:invalid)assertThrows(Exception.class,()->SketchAgentGestureOps.plan(source,op));
        JSONObject owner=SketchDocument.owner(source,"ink_rect");
        owner.put("locked",true);
        assertThrows(Exception.class,()->SketchAgentGestureOps.plan(source,move(4)));
        owner.put("locked",false).put("visible",false);
        assertThrows(Exception.class,()->SketchAgentGestureOps.plan(source,move(4)));
        assertEquals(SketchDocument.canonical(unchanged.getJSONArray("layers").getJSONObject(0)
                        .getJSONArray("objects").getJSONObject(0)),
                SketchDocument.canonical(source.getJSONArray("layers").getJSONObject(0)
                        .getJSONArray("objects").getJSONObject(0)));
    }

    @Test public void stageOperationProducesStableSingleEditIdentity()throws Exception {
        JSONObject claim=new JSONObject().put("sketchId","safe-sketch")
                .put("operationId","gesture-intent-20261025")
                .put("stepIndex",5);
        String original=SketchAgentGestureOps.deterministicEditId(claim);
        assertEquals(original,SketchAgentGestureOps.deterministicEditId(claim));
        assertNotEquals(original,SketchAgentGestureOps.deterministicEditId(
                new JSONObject(claim.toString()).put("stepIndex",6)));
        JSONObject capabilities=SketchAgentGestureOps.capabilities();
        assertFalse(capabilities.getBoolean("galleryAccess"));
        assertTrue(capabilities.getBoolean("terminalStageStepOnly"));
    }
}
