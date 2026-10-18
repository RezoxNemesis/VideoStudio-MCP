package com.rezoxnemesis.videostudio;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import android.graphics.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchCharacterAnimationTest {
    static JSONObject doc(int w,int h,int fps)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","character_"+w+"_"+fps).put("width",w).put("height",h).put("frameCount",fps*4).put("fps",fps).put("background","#FFE7E2D9"));}
    static JSONObject op(String view)throws Exception{return new JSONObject().put("type","add_character_animation").put("layerId","layer_1").put("characterId","scholar").put("settings",new JSONObject().put("view",view));}
    static JSONObject object(JSONObject d,String suffix)throws Exception {JSONArray a=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").endsWith(suffix))return a.getJSONObject(i);return null;}
    @Test public void adultBustShouldersAreWiderThanTheHeadAndEveryInkPairAgrees()throws Exception {
        JSONObject d=doc(1280,720,30);SketchEditOps.apply(d,op("front"));RectF coat=new RectF(),head=new RectF();
        SketchFrameRenderer.path(object(d,"_coat").getJSONArray("commands"),1280,720).computeBounds(coat,true);
        SketchFrameRenderer.path(SketchSpeechAnimationTest.part(d,"_head",0).getJSONArray("commands"),1280,720).computeBounds(head,true);
        assertTrue("Adult shoulder/head width ratio",coat.width()/head.width()>2.1);
        assertEquals(object(d,"_coat").getJSONArray("commands").toString(),object(d,"_coat_1").getJSONArray("commands").toString());
    }
    @Test public void dispatchCreatesAttachedEditableAnatomyAndRealSpeechInSixViews()throws Exception {
        for(String view:new String[]{"front","left","right","back","high","low"}){
            JSONObject d=doc(1280,720,30),o=op(view).put("speech",SketchSpeechAnimationTest.speech());SketchEditOps.apply(d,o);SketchDocument.validate(d);
            assertNotNull(object(d,"_coat"));assertNotNull(object(d,"_forearm"));assertNotNull(object(d,"_thumb"));assertNotNull(object(d,"_index_tip"));
            if(!view.equals("back")){assertNotNull(object(d,"_pendant"));assertNotNull(SketchSpeechAnimationTest.part(d,"_cavity",27));}
            else {assertNull(object(d,"_pendant"));assertNull(SketchSpeechAnimationTest.part(d,"_cavity",27));assertNull(object(d,"_lapel"));}
            assertTrue(object(d,"_forearm").getJSONArray("keys").length()>2);
        }
    }
    @Test public void neckOverlapsTheActualJawInEveryView()throws Exception {
        for(String v:new String[]{"front","left","right","back","high","low"}){
            JSONObject d=doc(1280,720,30);SketchEditOps.apply(d,op(v));RectF a=new RectF(),b=new RectF();
            SketchFrameRenderer.path(object(d,"_neck").getJSONArray("commands"),1280,720).computeBounds(a,true);
            SketchFrameRenderer.path(SketchSpeechAnimationTest.part(d,"_head",0).getJSONArray("commands"),1280,720).computeBounds(b,true);
            assertTrue("Neck must overlap jaw in "+v,a.top<b.bottom-4);
        }
    }
    @Test public void rejectedInputsPreserveExistingArtAndTiming()throws Exception {
        JSONObject d=doc(1280,720,30);String before=d.toString();
        JSONObject[] bad={op("rear"),op("front"),op("front")};
        bad[1].getJSONObject("settings").put("armLift",1.01);bad[2].getJSONObject("settings").put("unknown",1);
        for(JSONObject o:bad){try{SketchEditOps.apply(d,o);fail("Invalid constructor accepted");}catch(IllegalArgumentException expected){}assertEquals(before,d.toString());}
        d.getJSONArray("layers").getJSONObject(0).put("locked",true);before=d.toString();try{SketchEditOps.apply(d,op("front"));fail("Locked layer accepted");}catch(IllegalArgumentException expected){}assertEquals(before,d.toString());
    }
    private static float[] moved(JSONObject o,double x,double y,int frame)throws Exception {
        float[] p={(float)(38.4+x*.94),(float)(21.6+y*.94)};
        SketchFrameRenderer.matrix(SketchDocument.transformAt(o,frame),1280,720).mapPoints(p);return p;
    }
    @Test public void elbowWristAndHeldPendantStayAttachedAtEveryOutputFrameAtThirtyAndSixtyFps()throws Exception {
        for(int fps:new int[]{30,60}){
            JSONObject d=doc(1280,720,fps);SketchEditOps.apply(d,op("front"));
            JSONObject upper=object(d,"_upper_arm"),fore=object(d,"_forearm"),hand=object(d,"_palm"),prop=object(d,"_pendant");
            for(int f=0;f<fps*4;f++){
                float[] a=moved(upper,486,493,f),b=moved(fore,486,493,f),c=moved(fore,486,610,f),e=moved(hand,486,610,f);
                assertTrue("Elbow frame "+f,Math.hypot(a[0]-b[0],a[1]-b[1])<.75);
                assertTrue("Wrist frame "+f,Math.hypot(c[0]-e[0],c[1]-e[1])<.75);
                float[] pinch=moved(hand,492,637,f),link=moved(prop,492,637,f),gem=moved(prop,484,704,f);
                assertEquals("Held link X",pinch[0],link[0],.01);assertEquals("Held link Y",pinch[1],link[1],.01);
                assertTrue("Flexible pendant must hang below its grip instead of rotating above the wrist at frame "+f,gem[1]>link[1]+40);
            }
            assertEquals(fps*4,d.getInt("frameCount"));assertEquals(fps,d.getInt("fps"));
        }
    }
    @Test public void portraitAndSquareCanvasesKeepProportionsAndAllArtInBounds()throws Exception {
        double ratio=0;
        for(int[] size:new int[][]{{1280,720},{720,1280},{900,900}}){
            JSONObject d=doc(size[0],size[1],30);SketchEditOps.apply(d,op("front"));SketchDocument.validate(d);
            RectF b=new RectF();SketchFrameRenderer.path(object(d,"_coat").getJSONArray("commands"),size[0],size[1]).computeBounds(b,true);
            if(ratio==0)ratio=b.width()/b.height();else assertEquals(ratio,b.width()/b.height(),.001);
            JSONArray a=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
            for(int i=0;i<a.length();i++){JSONObject q=a.getJSONObject(i);RectF r=new RectF();SketchFrameRenderer.path(q.getJSONArray("commands"),size[0],size[1]).computeBounds(r,true);assertTrue(r.left>=0&&r.top>=0&&r.right<=size[0]&&r.bottom<=size[1]);}
        }
    }
    @Test public void transformedDestinationLayersRejectInvisibleOutOfCanvasArtAtomically()throws Exception {
        JSONObject d=doc(1280,720,30);d.getJSONArray("layers").getJSONObject(0).getJSONObject("transform").put("x",1);String before=d.toString();
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(d,op("front")));assertEquals(before,d.toString());
        JSONObject animated=doc(1280,720,30);animated.getJSONArray("layers").getJSONObject(0).getJSONArray("keys").put(new JSONObject().put("frame",0).put("interpolation","linear").put("transform",SketchDocument.transform())).put(new JSONObject().put("frame",60).put("interpolation","linear").put("transform",SketchDocument.transform().put("y",1)));
        before=animated.toString();assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(animated,op("front")));assertEquals(before,animated.toString());
    }
    @Test public void broadProfileBuildChangesTheActualCoatRatherThanIgnoringTheControl()throws Exception {
        JSONObject slender=doc(1280,720,30),broad=doc(1280,720,30),operation=op("left");SketchEditOps.apply(slender,operation);operation.getJSONObject("settings").put("build","broad");SketchEditOps.apply(broad,operation);
        RectF a=new RectF(),b=new RectF();SketchFrameRenderer.path(object(slender,"_coat").getJSONArray("commands"),1280,720).computeBounds(a,true);SketchFrameRenderer.path(object(broad,"_coat").getJSONArray("commands"),1280,720).computeBounds(b,true);
        assertTrue(b.width()>a.width()*1.1);
    }
    @Test public void rightProfileMaterialGradientCoordinatesFollowTheMirroredSleeve()throws Exception {
        JSONObject d=doc(1280,720,30);SketchEditOps.apply(d,op("right"));JSONObject o=object(d,"_forearm"),g=o.getJSONObject("gradient");
        RectF b=new RectF();SketchFrameRenderer.path(o.getJSONArray("commands"),1280,720).computeBounds(b,true);
        double x0=g.getDouble("x0")*1280,x1=g.getDouble("x1")*1280;
        assertTrue("Gradient must illuminate the actual mirrored sleeve",x0>=b.left-.01&&x0<=b.right+.01&&x1>=b.left-.01&&x1<=b.right+.01);
        assertTrue("Mirrored material endpoints reverse with the local surface",x0>x1);
    }
}
