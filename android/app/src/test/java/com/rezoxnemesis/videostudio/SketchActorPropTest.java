package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchActorPropTest {
    private JSONObject document(int fps,int frames)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","prop_test").put("width",1280).put("height",720).put("fps",fps).put("frameCount",frames));}
    private JSONObject layer(JSONObject doc)throws Exception{return doc.getJSONArray("layers").getJSONObject(0);}
    private JSONObject operation(String kind,String attachment)throws Exception{return new JSONObject().put("type","add_actor_prop").put("layerId","layer_1").put("propId","held").put("actorId","adult").put("settings",new JSONObject().put("kind",kind).put("attachment",attachment).put("scale",.8).put("x",.5).put("y",.75));}
    private JSONObject actor(JSONObject doc,String view)throws Exception{
        JSONArray poses=new JSONArray().put(new JSONObject().put("frame",0).put("bodyLean",-5).put("nearHandY",-170).put("farHandY",-175).put("nearWrist",-12).put("farWrist",8).put("handOpen",0).put("interpolation","linear"))
                .put(new JSONObject().put("frame",doc.getInt("frameCount")-1).put("bodyLean",8).put("nearHandY",-210).put("farHandY",-205).put("nearWrist",15).put("farWrist",-10).put("handOpen",0));
        SketchHumanAnimation.apply(doc,new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","adult").put("settings",new JSONObject().put("view",view).put("size",.8)).put("poses",poses));
        return SketchDocument.object(doc,"adult_rig");
    }
    private float[] hand(JSONObject doc,JSONObject actor,String attachment,int frame)throws Exception{
        JSONObject s=actor.getJSONObject("human").getJSONObject("settings");
        double[] p=SketchHumanFigure.grip(s,SketchHumanAnimation.pose(actor,frame),attachment.equals("near"));double fit=Math.min(doc.getInt("width")/1280d,doc.getInt("height")/720d)*s.getDouble("size");
        float[] xy={(float)(doc.getInt("width")*s.getDouble("x")+p[0]*fit),(float)(doc.getInt("height")*s.getDouble("floorY")+p[1]*fit)};
        SketchFrameRenderer.matrix(SketchDocument.transformAt(actor,frame),doc.getInt("width"),doc.getInt("height")).mapPoints(xy);return xy;
    }
    private float[] grip(JSONObject doc,JSONObject handle,int frame)throws Exception{
        JSONArray m=handle.getJSONArray("commands").getJSONArray(0);float[] p={(float)(m.getDouble(1)*doc.getInt("width")),(float)(m.getDouble(2)*doc.getInt("height"))};
        SketchFrameRenderer.matrix(SketchDocument.transformAt(handle,frame),doc.getInt("width"),doc.getInt("height")).mapPoints(p);return p;
    }
    private void actualFingersTouchHandle(JSONObject doc,JSONObject actor,JSONObject handle,String attachment,int frame)throws Exception {
        JSONObject drawn=SketchHumanAnimation.frameDocument(actor,doc.getInt("width"),doc.getInt("height"),frame);
        JSONArray objects=drawn.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");Path fingers=new Path();
        Matrix actorMatrix=SketchFrameRenderer.matrix(SketchDocument.transformAt(actor,frame),doc.getInt("width"),doc.getInt("height"));
        for(int i=0;i<objects.length();i++){
            JSONObject o=objects.getJSONObject(i);String name=o.getString("id");
            if(o.getBoolean("fill")&&(name.contains("_"+attachment+"_finger_")||name.contains("_"+attachment+"_thumb_"))){
                Path shape=SketchFrameRenderer.path(o.getJSONArray("commands"),doc.getInt("width"),doc.getInt("height"));shape.transform(actorMatrix);fingers.addPath(shape);
            }
        }
        assertFalse("Expected the actual native finger/thumb paths",fingers.isEmpty());
        float[] contact=grip(doc,handle,frame);Path probe=new Path(),intersection=new Path();probe.addCircle(contact[0],contact[1],.4f,Path.Direction.CW);
        assertTrue(intersection.op(fingers,probe,Path.Op.INTERSECT));assertFalse("Handle pinch must intersect actual drawn fingers/thumb, not the wrist: "+attachment+" frame "+frame,intersection.isEmpty());
    }

    @Test public void realHandleContactIsExactAcrossEveryFrameThirtySixtyFpsAndSixViews()throws Exception {
        for(int fps:new int[]{30,60})for(String view:new String[]{"front","left","right","back","high","low"})for(String attachment:new String[]{"near","far"}){
            JSONObject doc=document(fps,fps*4),actor=actor(doc,view);
            actor.getJSONObject("transform").put("rotation",4).put("scaleX",1.03).put("scaleY",.97);
            actor.put("keys",new JSONArray().put(new JSONObject().put("frame",0).put("interpolation","linear").put("transform",SketchDocument.copy(actor.getJSONObject("transform"))))
                    .put(new JSONObject().put("frame",fps*4-1).put("interpolation","hold").put("transform",SketchDocument.copy(actor.getJSONObject("transform")).put("rotation",-3).put("x",.01))));
            SketchActorProp.apply(doc,operation("lantern",attachment));JSONObject handle=SketchDocument.object(doc,"held_handle_0");
            assertEquals(fps*4,handle.getJSONArray("keys").length());
            for(int f=0;f<fps*4;f++){float[] target=hand(doc,actor,attachment,f),actual=grip(doc,handle,f);assertEquals(view+" X at "+f,target[0],actual[0],.001);assertEquals(view+" Y at "+f,target[1],actual[1],.001);assertEquals(0,SketchDocument.transformAt(handle,f).getDouble("rotation"),0);}
            for(int f:new int[]{0,fps,fps*2,fps*4-1})actualFingersTouchHandle(doc,actor,handle,attachment,f);
            SketchDocument.validate(doc);
        }
    }

    @Test public void propSharesLayerTransformOnceAndLanternHangsBelowActualGrip()throws Exception {
        JSONObject doc=document(30,120),actor=actor(doc,"right");layer(doc).getJSONObject("transform").put("rotation",3).put("scaleX",.9).put("scaleY",.9);
        actor.getJSONObject("transform").put("rotation",12);SketchActorProp.apply(doc,operation("lantern","near"));JSONObject handle=SketchDocument.object(doc,"held_handle_0"),glass=SketchDocument.object(doc,"held_glass_0");
        for(int f=0;f<120;f++){
            float[] a=hand(doc,actor,"near",f),b=grip(doc,handle,f);Matrix layerMatrix=SketchFrameRenderer.matrix(SketchDocument.transformAt(layer(doc),f),1280,720);layerMatrix.mapPoints(a);layerMatrix.mapPoints(b);assertArrayEquals(a,b,.001f);
            RectF bounds=new RectF();SketchFrameRenderer.path(glass.getJSONArray("commands"),1280,720).computeBounds(bounds,true);SketchFrameRenderer.matrix(SketchDocument.transformAt(glass,f),1280,720).mapRect(bounds);
            assertTrue("Lantern glass stays world-down despite actor rotation",bounds.top>grip(doc,handle,f)[1]);assertEquals(0,SketchDocument.transformAt(glass,f).getDouble("rotation"),0);
        }
    }

    @Test public void parcelStaffAndLanternHaveDifferentOriginalEditableNativeCurves()throws Exception {
        java.util.Set<String> art=new java.util.HashSet<>();
        for(String kind:new String[]{"lantern","parcel","staff"}){JSONObject doc=document(30,120);actor(doc,"front");SketchActorProp.apply(doc,operation(kind,"near"));JSONArray objects=layer(doc).getJSONArray("objects"),prop=new JSONArray();
            for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(!o.getString("id").startsWith("held_")||o.getString("id").equals("held_grip"))continue;assertEquals("path",o.getString("kind"));assertEquals(120,o.getJSONArray("keys").length());prop.put(o);}
            assertTrue(prop.length()>=3);assertTrue(art.add(SketchDocument.canonical(prop)));assertNotNull(SketchDocument.object(doc,"held_handle_0"));
        }
    }

    @Test public void realNativeFingersCoverHandleWhileItsExposedArcRemainsVisible()throws Exception {
        for(String attachment:new String[]{"near","far"}){
            JSONObject doc=document(30,120),original=actor(doc,"front"),bare=SketchDocument.copy(doc);
            SketchActorProp.apply(doc,operation("lantern",attachment));
            JSONObject body=SketchDocument.object(doc,"adult_rig"),overlay=SketchDocument.object(doc,"held_grip");
            assertEquals("without_"+attachment+"_hand",body.getJSONObject("human").getString("parts"));
            assertEquals(attachment+"_hand",overlay.getJSONObject("human").getString("parts"));
            JSONObject expected=SketchDocument.copy(original).put("id","held_grip");expected.getJSONObject("human").put("parts",attachment+"_hand");
            assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(overlay));
            JSONArray objects=layer(doc).getJSONArray("objects");assertEquals("held_grip",objects.getJSONObject(objects.length()-1).getString("id"));
            // Use the actual native handle, with a diagnostic color and thicker stroke so pixel
            // coverage cannot pass merely through antialiasing at an infinitesimal contact.
            JSONObject handle=SketchDocument.object(doc,"held_handle_0");handle.put("color","#FFFF2020").put("width",.006);
            JSONObject behind=SketchDocument.copy(doc);JSONArray withoutOverlay=new JSONArray();for(int i=0;i<objects.length()-1;i++)withoutOverlay.put(SketchDocument.copy(objects.getJSONObject(i)));layer(behind).put("objects",withoutOverlay);
            try(SketchFrameRenderer before=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),behind);SketchFrameRenderer after=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc);SketchFrameRenderer skin=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),bare)){
                for(int f:new int[]{0,119}){
                    Bitmap a=before.render(f),b=after.render(f),c=skin.render(f);try{
                        float[] contact=grip(doc,handle,f);int x=Math.round(contact[0]),y=Math.round(contact[1]),covered=0,visible=0;
                        for(int dy=-5;dy<=32;dy++)for(int dx=-22;dx<=22;dx++){
                            int px=x+dx,py=y+dy;if(a.getPixel(px,py)!=0xFFFF2020)continue;
                            if(Math.abs(dx)<=5&&Math.abs(dy)<=5&&b.getPixel(px,py)==c.getPixel(px,py)&&b.getPixel(px,py)!=0xFFFF2020)covered++;
                            if(b.getPixel(px,py)==0xFFFF2020)visible++;
                        }
                        assertTrue("Actual skin must cover the handle at the pinch: "+attachment,covered>0);
                        assertTrue("The actual handle must remain visible outside the fingers",visible>0);
                    }finally{a.recycle();b.recycle();c.recycle();}
                }
            }
        }
    }

    @Test public void secondHeldPropAndLateOverlayBudgetFailureLeaveWholeActorUntouched()throws Exception {
        JSONObject doc=document(30,120);actor(doc,"front");SketchActorProp.apply(doc,operation("lantern","near"));String before=SketchDocument.canonical(doc);
        assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(doc,operation("parcel","far").put("propId","second")));assertEquals(before,SketchDocument.canonical(doc));
        JSONObject full=document(30,120);actor(full,"front");JSONArray objects=layer(full).getJSONArray("objects");JSONObject path=SketchEditOps.completeObject(new JSONObject().put("id","filler").put("kind","path").put("commands",new JSONArray().put(new JSONArray().put("M").put(.1).put(.1)).put(new JSONArray().put("L").put(.2).put(.2))));
        // Leave room for the six lantern curves but not its mandatory retained hand overlay.
        while(objects.length()<SketchDocument.MAX_OBJECTS-6)objects.put(SketchDocument.copy(path).put("id","filler_"+objects.length()));
        SketchDocument.validate(full);before=SketchDocument.canonical(full);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(full,operation("lantern","near")));assertEquals(before,SketchDocument.canonical(full));
    }

    @Test public void floorPropNeedsNoActorAndHasStaticKeysAtItsExplicitPosition()throws Exception {
        JSONObject doc=document(30,120),op=operation("parcel","floor");op.remove("actorId");SketchActorProp.apply(doc,op);JSONObject handle=SketchDocument.object(doc,"held_handle_0");
        assertEquals(0,handle.getJSONArray("keys").length());assertArrayEquals(new float[]{640,540},grip(doc,handle,0),.001f);assertArrayEquals(grip(doc,handle,0),grip(doc,handle,119),0);
    }

    @Test public void missingCrossLayerLockedDuplicateOutOfBoundsAndBudgetsRejectAtomically()throws Exception {
        JSONObject missing=document(30,120);String before=SketchDocument.canonical(missing);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(missing,operation("lantern","near")));assertEquals(before,SketchDocument.canonical(missing));
        JSONObject doc=document(30,120);actor(doc,"front");JSONObject second=SketchDocument.copy(layer(doc)).put("id","other").put("objects",new JSONArray());doc.getJSONArray("layers").put(second);
        before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(doc,operation("lantern","near").put("layerId","other")));assertEquals(before,SketchDocument.canonical(doc));
        layer(doc).put("locked",true);before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(doc,operation("lantern","near")));assertEquals(before,SketchDocument.canonical(doc));layer(doc).put("locked",false);
        SketchActorProp.apply(doc,operation("lantern","near"));before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(doc,operation("parcel","near")));assertEquals(before,SketchDocument.canonical(doc));
        JSONObject floor=document(30,120);before=SketchDocument.canonical(floor);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(floor,operation("lantern","floor").put("settings",new JSONObject().put("kind","lantern").put("attachment","floor").put("x",0).put("y",1))));assertEquals(before,SketchDocument.canonical(floor));
        JSONObject longDoc=document(30,1200);actor(longDoc,"front");before=SketchDocument.canonical(longDoc);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(longDoc,operation("lantern","near")));assertEquals(before,SketchDocument.canonical(longDoc));
    }

    @Test public void malformedSettingsAndIdentifiersNeverMutateTarget()throws Exception {
        JSONObject doc=document(30,120);actor(doc,"front");
        for(JSONObject settings:new JSONObject[]{new JSONObject().put("kind","sword"),new JSONObject().put("attachment","drop"),new JSONObject().put("scale",.3),new JSONObject().put("x",-1),new JSONObject().put("scale",true),new JSONObject().put("unknown",1)}){
            String before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(doc,operation("lantern","near").put("settings",settings)));assertEquals(before,SketchDocument.canonical(doc));
        }
        String before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchActorProp.apply(doc,operation("lantern","near").put("propId","a".repeat(21))));assertEquals(before,SketchDocument.canonical(doc));
    }

    @Test public void portraitAndSquareKeepUniformPropGeometryAndActualHandContact()throws Exception {
        double aspect=0;
        for(int[] canvas:new int[][]{{1280,720},{720,1280},{900,900}}){
            JSONObject doc=document(30,120).put("width",canvas[0]).put("height",canvas[1]),actor=actor(doc,"high");SketchActorProp.apply(doc,operation("lantern","far"));
            JSONObject handle=SketchDocument.object(doc,"held_handle_0"),glass=SketchDocument.object(doc,"held_glass_0");
            for(int frame:new int[]{0,37,119}){assertArrayEquals(hand(doc,actor,"far",frame),grip(doc,handle,frame),.001f);actualFingersTouchHandle(doc,actor,handle,"far",frame);}
            RectF b=new RectF();SketchFrameRenderer.path(glass.getJSONArray("commands"),canvas[0],canvas[1]).computeBounds(b,true);
            if(aspect==0)aspect=b.width()/b.height();else assertEquals(aspect,b.width()/b.height(),.001);
            assertEquals(120,doc.getInt("frameCount"));assertEquals(30,doc.getInt("fps"));SketchDocument.validate(doc);
        }
    }
}
