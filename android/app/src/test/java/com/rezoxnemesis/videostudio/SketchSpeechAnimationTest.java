package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.RectF;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSpeechAnimationTest {
    static JSONObject cue(int start,int end,String shape)throws Exception {
        return new JSONObject().put("startMs",start).put("endMs",end).put("shape",shape);
    }
    static JSONObject speech()throws Exception {
        return new JSONObject().put("transitionMs",60).put("cues",new JSONArray()
                .put(cue(500,700,"closed")).put(cue(700,1100,"open"))
                .put(cue(1100,1400,"round")).put(cue(1700,1900,"teeth"))
                .put(cue(1900,2200,"wide")).put(cue(2200,2400,"closed")));
    }
    static JSONObject op(boolean face,String view,JSONObject speech)throws Exception {
        return new JSONObject().put("type",face?"add_face_animation":"add_mouth_animation")
                .put("layerId","layer_1").put(face?"faceId":"mouthId","speaker")
                .put("settings",new JSONObject().put("view",view)).put("speech",speech);
    }
    static JSONObject doc(int fps)throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId","speech_"+fps)
                .put("width",1280).put("height",720).put("fps",fps).put("frameCount",fps*4));
    }
    static JSONObject part(JSONObject d,String ending,int frame)throws Exception {
        JSONArray a=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);
            if(o.getString("id").endsWith(ending)&&o.getInt("frame")<=frame&&frame<o.getInt("frame")+o.getInt("exposure"))return o;}
        return null;
    }
    static RectF bounds(JSONObject o)throws Exception {
        RectF b=new RectF();SketchFrameRenderer.path(o.getJSONArray("commands"),1280,720).computeBounds(b,true);return b;
    }
    @Test public void speakingCuesClosePressedLipsAndQuietGapsInsteadOfRepeatingSilentPresets()throws Exception {
        JSONObject d=doc(30);SketchEditOps.apply(d,op(false,"front",speech()));SketchDocument.validate(d);
        for(int frame:new int[]{0,15,18,45,48,69,72,119})
            assertNull("Rest or pressed lips at frame "+frame,part(d,"_cavity",frame));
        assertNotNull(part(d,"_cavity",27));assertNotNull(part(d,"_cavity",37));
        assertTrue("Rounded phoneme must narrow the aperture",bounds(part(d,"_cavity",37)).width()<bounds(part(d,"_cavity",27)).width()*.8);
        assertNotNull("Visible consonant teeth",part(d,"_upper_teeth",54));
        assertEquals(120,d.getInt("frameCount"));assertEquals(30,d.getInt("fps"));
    }
    @Test public void speakingFacePreservesIndependentGazeAndMovesItsChin()throws Exception {
        JSONObject d=doc(30);SketchEditOps.apply(d,op(true,"front",speech()));SketchDocument.validate(d);
        assertNotNull(part(d,"_iris",27));assertNotNull(part(d,"_nose",27));
        assertTrue("Spoken vowel drives the actual chin",bounds(part(d,"_head",27)).bottom>bounds(part(d,"_head",15)).bottom+12);
        assertEquals(bounds(part(d,"_nose",15)),bounds(part(d,"_nose",27)));
        assertNull(part(d,"_cavity",48));
    }
    @Test public void roundedVowelsHaveRoundedSideWallsInsteadOfPointedSmileCorners()throws Exception {
        JSONObject d=doc(30);SketchEditOps.apply(d,op(false,"front",speech()));
        float[] points=SketchFrameRenderer.path(part(d,"_cavity",37).getJSONArray("commands"),1280,720).approximate(.05f);
        float minX=Float.POSITIVE_INFINITY,top=Float.POSITIVE_INFINITY,bottom=Float.NEGATIVE_INFINITY,sideY=0;
        for(int i=0;i<points.length;i+=3){top=Math.min(top,points[i+2]);bottom=Math.max(bottom,points[i+2]);if(points[i+1]<minX){minX=points[i+1];sideY=points[i+2];}}
        assertEquals("The O aperture needs a rounded side at midheight, not a pointed smile corner",(top+bottom)/2,sideY,3);
    }
    @Test public void profileSpeechKeepsTheLeadingLipsAttachedWhenRoundedOrResized()throws Exception {
        for(String view:new String[]{"left","right"})for(double size:new double[]{.8,1,1.2}){
            JSONObject d=doc(30),op=op(true,view,speech());op.getJSONObject("settings").put("mouthSize",size);SketchEditOps.apply(d,op);
            float[] points=SketchFrameRenderer.path(part(d,"_cavity",37).getJSONArray("commands"),1280,720).approximate(.05f);
            double leading="left".equals(view)?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
            for(int i=0;i<points.length;i+=3)leading="left".equals(view)?Math.min(leading,points[i+1]):Math.max(leading,points[i+1]);
            double lipLandmark=640+("left".equals(view)?-1:1)*1280*.34*.53;
            assertEquals("Narrow rounded profile mouth must meet the facial silhouette, not float in the cheek",lipLandmark,leading,6);
        }
    }
    @Test public void fullyOpenProfileLipEndpointFollowsTheActualCurvedHeadContour()throws Exception {
        double worst=0;JSONArray distances=new JSONArray();String evidence=System.getenv("VIDEOSTUDIO_SPEECH_EVIDENCE");
        for(String view:new String[]{"front","left","right","back","high","low"})for(double size:new double[]{.8,1,1.2}){
            JSONObject d=doc(30),operation=op(true,view,speech());operation.remove("speech");
            operation.put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("open",1).put("wide",1).put("roundness",1)));
            operation.getJSONObject("settings").put("mouthSize",size);SketchEditOps.apply(d,operation);SketchDocument.validate(d);
            if("left".equals(view)||"right".equals(view)){
                float[] edge=SketchFrameRenderer.path(part(d,"_cavity",0).getJSONArray("commands"),1280,720).approximate(.025f);
                double upper=720*.43+720*.7*.22-720*.7*.16*size*.18;
                double leading="left".equals(view)?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY,pointY=0;
                for(int i=0;i<edge.length;i+=3)if(edge[i+2]<=upper+.1&&("left".equals(view)?edge[i+1]<leading:edge[i+1]>leading)){leading=edge[i+1];pointY=edge[i+2];}
                android.graphics.PathMeasure contour=new android.graphics.PathMeasure(SketchFrameRenderer.path(part(d,"_head",0).getJSONArray("commands"),1280,720),true);
                float[] position=new float[2];double distance=Double.POSITIVE_INFINITY;
                for(float along=0;along<=contour.getLength();along+=.25f){contour.getPosTan(along,position,null);distance=Math.min(distance,Math.hypot(position[0]-leading,position[1]-pointY));}
                worst=Math.max(worst,distance);distances.put(new JSONObject().put("view",view).put("mouthSize",size).put("gapPixels",distance));
            }
            if(evidence!=null)try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){
                android.graphics.Bitmap image=renderer.render(0);try{SketchActionFilmTest.png(new java.io.File(evidence,"profile-extremes/"+view+"-"+size+".png"),image);}finally{image.recycle();}
            }
        }
        if(evidence!=null)java.nio.file.Files.write(new java.io.File(evidence,"profile-extremes/distances.json").toPath(),distances.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue("Leading upper lips must meet actual head contours; worst gap="+worst,worst<2);
    }
    @Test public void invalidSpeechRejectsAtomicallyAndCannotSilentlyOverridePoseKeys()throws Exception {
        JSONObject d=doc(30);String before=SketchDocument.canonical(d);
        for(JSONObject invalid:new JSONObject[]{
                new JSONObject().put("cues",new JSONArray().put(cue(700,900,"open")).put(cue(800,1000,"round"))),
                new JSONObject().put("cues",new JSONArray().put(cue(0,4001,"open"))),
                new JSONObject().put("cues",new JSONArray().put(cue(300,300,"rest"))),
                new JSONObject().put("cues",new JSONArray().put(cue(0,300,"phoneme_guess"))),
                speech().put("transitionMs",401),speech().put("cues",new JSONArray()),
                speech().put("download","https://invalid.example/audio.wav")}){
            assertThrows(Exception.class,()->SketchEditOps.apply(d,op(true,"front",invalid)));
            assertEquals(before,SketchDocument.canonical(d));
        }
        assertThrows(Exception.class,()->SketchEditOps.apply(d,op(false,"front",speech()).put("poses",new JSONArray().put(new JSONObject().put("frame",0)))));
        assertEquals(before,SketchDocument.canonical(d));
    }
    @Test public void commonPhysicalTimesKeepTheSameMouthAtThirtyAndSixtyFps()throws Exception {
        JSONObject a=doc(30),b=doc(60);SketchEditOps.apply(a,op(false,"front",speech()));SketchEditOps.apply(b,op(false,"front",speech()));
        for(int f=0;f<120;f++){
            JSONObject x=part(a,"_cavity",f),y=part(b,"_cavity",f*2);
            assertEquals("Visibility at "+f,x==null,y==null);
            if(x!=null)assertEquals("Geometry at "+f,x.getJSONArray("commands").toString(),y.getJSONArray("commands").toString());
        }
    }
    static JSONObject prediction()throws Exception {
        return new JSONObject().put("transitionMs",40).put("prediction",new JSONObject().put("startMs",500)
                .put("segments",new JSONArray().put(new JSONObject().put("phonemes","mˈA bˈO")
                .put("startSample",0).put("sampleCount",24000).put("modelDurationUnits",new JSONArray("[4,4,2,6,4,4,2,10,4]"))
                .put("unitsPerSecond",40).put("forcedAlignmentVerified",false))));
    }
    @Test public void modelPhonemeDurationsBecomeNativeSpeechWithClosedBilabialsAndRoundedVowels()throws Exception {
        JSONObject d=doc(30);SketchEditOps.apply(d,op(false,"front",prediction()));
        assertNull("Leading model boundary is rest",part(d,"_cavity",16));
        assertNull("M is pressed lips",part(d,"_cavity",20));
        assertNotNull("Stress carries the following vowel instead of making a false closure",part(d,"_cavity",24));
        assertNotNull("Rounded O is visible",part(d,"_cavity",38));
        assertNull("After audio duration is rest",part(d,"_cavity",60));
        JSONObject invalid=prediction();invalid.getJSONObject("prediction").getJSONArray("segments").getJSONObject(0).put("sampleCount",23999);
        assertThrows("Never stretch predicted timing to fit different audio",Exception.class,()->SketchEditOps.apply(doc(30),op(false,"front",invalid)));
        assertThrows(Exception.class,()->SketchEditOps.apply(doc(30),op(false,"front",prediction().put("cues",speech().getJSONArray("cues")))));
    }
    @Test public void liveDispatchIsIdempotentAndUndoRetainsTheOriginalDrawing()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(c,120,30);
        JSONObject request=SketchMouthAnimationTest.request(id,op(true,"left",speech()));
        JSONObject result=SketchEditOps.execute(c,"sketch_edit",request);assertEquals(result.toString(),SketchEditOps.execute(c,"sketch_edit",request).toString());
        try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(2,store.get(id).getInt("revision"));}
        SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","undo_speech"));
        try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(0,store.get(id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}
    }
}
