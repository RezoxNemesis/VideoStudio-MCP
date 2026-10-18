package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
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
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Direct product-helper fixtures, independently of the parent's optional Figure dispatch.
 * A missing iris, leaked pigment, mirrored front profile or ignored channel fails these
 * tests against real Skia paths/pixels. No reference image or drawing substitute is used.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanFaceTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] STYLES={"angular","soft"};
    private static final double ZOOM=6;

    @Test public void sixAuthoredViewsRenderNativePixelsAndRetainEditableUniqueLabels()throws Exception {
        for(String style:STYLES)for(String view:VIEWS){
            JSONObject doc=frameDocument(style,view,new JSONObject());SketchDocument.validate(doc);
            Set<String> ids=new HashSet<>();JSONArray objects=layer(doc).getJSONArray("objects");
            for(int i=0;i<objects.length();i++){
                JSONObject o=objects.getJSONObject(i);assertEquals("path",o.getString("kind"));assertTrue(ids.add(o.getString("id")));
                assertTrue(o.getString("id").startsWith("cast_"));assertTrue(o.getJSONArray("commands").length()>1);
            }
            assertNotNull(part(doc,"head"));assertNotNull(part(doc,"hair_strands"));assertNotNull(part(doc,"hair_highlight"));
            Bitmap b=render(doc);try{
                int count=0;for(int y=150;y<535;y+=4)for(int x=450;x<830;x+=4)if(b.getPixel(x,y)!=Color.WHITE)count++;
                assertTrue("Actual native head pixels "+style+" "+view,count>1200);
                String folder=System.getProperty("videostudio.face.output");
                if(folder!=null){File file=new File(folder,style+"-"+view+".png");assertTrue(file.getParentFile().isDirectory()||file.getParentFile().mkdirs());try(FileOutputStream out=new FileOutputStream(file)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
            }finally{b.recycle();}
        }
    }
    @Test public void rearHasNoVisibleFacialGeometryOrResponseToSpeechGazeAndBlink()throws Exception {
        JSONObject extremes=new JSONObject().put("mouth",1).put("mouthTeeth",1).put("mouthTongue",1).put("blink",1).put("gazeX",1).put("brow",1);
        for(String style:STYLES){JSONObject doc=frameDocument(style,"back",extremes);
            for(String name:new String[]{"eye_near","eye_far","eye_near_iris","eye_near_closed_lid","eye_near_brow","mouth","teeth","tongue","nose","nose_detail"})assertNull("Rear occludes "+name,part(doc,name));
            assertSamePixels(frameDocument(style,"back",new JSONObject()),doc);
        }
    }
    @Test public void profilesHaveIndependentNoseLipChinAnatomyAndOnlyOneEye()throws Exception {
        for(String style:STYLES)for(String view:new String[]{"left","right"}){
            JSONObject doc=frameDocument(style,view,new JSONObject());assertNotNull(part(doc,"eye_near"));assertNull(part(doc,"eye_far"));assertNull(part(doc,"eye_far_brow"));
            Path head=path(part(doc,"head"));RectF forehead=band(head,360-12*6),nose=band(head,360+4*6),chin=band(head,360+22*6),lip=band(head,360+11*6);
            if(view.equals("right")){assertTrue(nose.right>forehead.right+20);assertTrue(nose.right>chin.right+30);assertTrue(lip.right>chin.right+8);}
            else {assertTrue(nose.left<forehead.left-20);assertTrue(nose.left<chin.left-30);assertTrue(lip.left<chin.left-8);}
            assertNotEquals("A profile is not front geometry reflected",part(frameDocument(style,"front",new JSONObject()),"head").getJSONArray("commands").length(),part(doc,"head").getJSONArray("commands").length());
        }
    }
    @Test public void highAndLowChangeLandmarksAndSurfacesBeyondScalingOrMirroringFront()throws Exception {
        for(String style:STYLES){JSONObject front=frameDocument(style,"front",new JSONObject()),high=frameDocument(style,"high",new JSONObject()),low=frameDocument(style,"low",new JSONObject());
            assertTrue(bounds(part(high,"eye_near")).centerY()>bounds(part(front,"eye_near")).centerY()+15);
            assertTrue(bounds(part(low,"eye_near")).centerY()<bounds(part(front,"eye_near")).centerY()-15);
            assertNotNull(part(high,"crown"));assertNull(part(front,"crown"));assertNotNull(part(low,"nose_under"));assertNotNull(part(low,"jaw_plane"));
            assertDifferentPixels(front,high);assertDifferentPixels(front,low);
        }
    }
    @Test public void variantsDifferInActualJawHairAndEyeShapesWithIdenticalPalettes()throws Exception {
        JSONObject angular=frameDocument("angular","front",new JSONObject()),soft=frameDocument("soft","front",new JSONObject());
        for(String name:new String[]{"head","hair","eye_near"})assertNotEquals("Distinct authored "+name,part(angular,name).getJSONArray("commands").toString(),part(soft,name).getJSONArray("commands").toString());
        assertTrue("Angular jaw is broader at the mandibular turn",band(path(part(angular,"head")),480).width()>band(path(part(soft,"head")),480).width()+5);
        assertDifferentPixels(angular,soft);
        assertDifferentPixels(frameDocument("angular","back",new JSONObject()),frameDocument("soft","back",new JSONObject()));
    }
    @Test public void pureVerticalGazeHasReadableTravelWithoutMovingTheLidsEvenDuringBlink()throws Exception {
        for(String style:STYLES)for(String view:new String[]{"front","left","right","high","low"})for(double blink:new double[]{0,.5,.95}){
            JSONObject up=frameDocument(style,view,new JSONObject().put("gazeY",-1).put("blink",blink)),down=frameDocument(style,view,new JSONObject().put("gazeY",1).put("blink",blink));
            assertEquals(part(up,"eye_near").getJSONArray("commands").toString(),part(down,"eye_near").getJSONArray("commands").toString());
            // A large iris clipped by both lids can have fixed bounding-box edges.
            // Track its catchlight, and require the unclosed pupil itself to move.
            double travel=bounds(part(down,"eye_near_glint")).centerY()-bounds(part(up,"eye_near_glint")).centerY();
            assertTrue("Readable vertical gaze travel: "+style+" "+view+" "+blink+" = "+travel,travel>=.75*ZOOM*(1-blink));
            if(blink==0)assertTrue(bounds(part(down,"eye_near_pupil")).centerY()-bounds(part(up,"eye_near_pupil")).centerY()>=.75*ZOOM);
            // At 95% closure the physical lid stroke occludes the tiny iris. Its
            // travel and containment still matter; visible gaze is checked open/half.
            if(blink<=.5)assertDifferentPixels(up,down);
        }
    }
    @Test public void neutralIrisHasAdultCoverageAndUpperLidOccludesItsRoundSurface()throws Exception {
        for(String style:STYLES)for(String view:new String[]{"front","left","right","high","low"}){
            JSONObject doc=frameDocument(style,view,new JSONObject());
            for(String eye:new String[]{"eye_near","eye_far"}){
                JSONObject white=part(doc,eye);if(white==null)continue;
                RectF aperture=curveBounds(white),iris=bounds(part(doc,eye+"_iris"));
                double ratio=iris.width()/aperture.width();
                assertTrue("Neutral iris must cover at least 30% of aperture width: "+style+" "+view+" "+eye+" ratio="+ratio,ratio>=.30);
                assertTrue("Retain sclera beside the iris",ratio<=.45);
                assertTrue("Upper lid must occlude a broad iris rather than leave a white stare",iris.top<aperture.top+.15*ZOOM);
                assertTrue("A separate dark pupil is visible",bounds(part(doc,eye+"_pupil")).width()>iris.width()*.3);
            }
        }
    }
    @Test public void blinkNarrowsApertureAndAllGazePigmentsRemainInscribedInActualLids()throws Exception {
        for(String style:STYLES)for(String view:VIEWS){double previous=Double.POSITIVE_INFINITY;
            for(double blink:new double[]{0,.5,.95,.99,1}){
                for(int gx:new int[]{-1,1})for(int gy:new int[]{-1,1}){
                    JSONObject doc=frameDocument(style,view,new JSONObject().put("blink",blink).put("gazeX",gx).put("gazeY",gy));
                    for(String eye:new String[]{"eye_near","eye_far"}){
                        boolean hidden=view.equals("back")||eye.equals("eye_far")&&(view.equals("left")||view.equals("right"));
                        JSONObject aperture=part(doc,eye),iris=part(doc,eye+"_iris"),closed=part(doc,eye+"_closed_lid");
                        if(hidden){assertNull(aperture);assertNull(iris);assertNull(closed);continue;}
                        if(blink==1){assertNull(aperture);assertNull(iris);assertNotNull(closed);assertFalse(closed.getBoolean("fill"));}
                        else {assertNotNull(aperture);assertNotNull(iris);assertNull(closed);for(String pigment:new String[]{"_iris","_pupil","_glint"})inside(path(part(doc,eye+pigment)),path(aperture),style+" "+view+" "+blink+pigment);}
                    }
                }
                JSONObject doc=frameDocument(style,view,new JSONObject().put("blink",blink));JSONObject opening=part(doc,"eye_near");
                if(opening!=null){double height=bounds(opening).height();assertTrue("Actual lid opening decreases",height<previous);previous=height;}
            }
        }
        assertDifferentPixels(frameDocument("angular","front",new JSONObject()),frameDocument("angular","front",new JSONObject().put("blink",1)));
    }
    @Test public void speechExtremesKeepCavityInHeadAndTeethAndTongueInsideCavity()throws Exception {
        for(String style:STYLES)for(String view:VIEWS)for(int wide:new int[]{-1,1})for(int round:new int[]{0,1})for(int smile:new int[]{-1,1}){
            JSONObject doc=frameDocument(style,view,new JSONObject().put("mouth",1).put("mouthWide",wide).put("mouthRound",round).put("smile",smile).put("mouthTeeth",1).put("mouthTongue",1));
            if(view.equals("back")){assertNull(part(doc,"mouth"));continue;}
            Path cavity=path(part(doc,"mouth"));inside(cavity,path(part(doc,"head")),"Cavity within "+style+" "+view+" head");
            inside(path(part(doc,"teeth")),cavity,"Teeth within "+view+" mouth");inside(path(part(doc,"tongue")),cavity,"Tongue within "+view+" mouth");
        }
    }
    @Test public void everyFacialChannelChangesItsNativeGeometryAndPixels()throws Exception {
        for(String channel:new String[]{"mouth","mouthWide","mouthRound","mouthTeeth","mouthTongue","smile","brow","gazeX","gazeY","blink"}){
            JSONObject base=new JSONObject().put("mouth",.7),changed=new JSONObject(base.toString()).put(channel,1);
            JSONObject a=frameDocument("angular","front",base),b=frameDocument("angular","front",changed);
            assertDifferentPixels(a,b);
        }
        for(String style:STYLES)for(String view:VIEWS){JSONObject doc=frameDocument(style,view,new JSONObject().put("mouth",0).put("mouthTeeth",1).put("mouthTongue",1));assertNull(part(doc,"teeth"));assertNull(part(doc,"tongue"));}
    }
    @Test public void profileSpeechOpensAtTheLipInsteadOfFloatingInTheCheek()throws Exception {
        for(String style:STYLES)for(String view:new String[]{"left","right"}){
            JSONObject doc=frameDocument(style,view,new JSONObject().put("mouth",1).put("mouthWide",.5).put("mouthRound",.4));
            RectF cavity=bounds(part(doc,"mouth")),lip=band(path(part(doc,"head")),cavity.centerY());
            double gap=view.equals("right")?lip.right-cavity.right:cavity.left-lip.left;
            assertTrue("Speech cavity should approach the actual lip silhouette: "+style+" "+view+" gap="+gap,gap>=0&&gap<1.7*ZOOM);
        }
    }
    @Test public void phaseAndUnrelatedBodyChannelsDoNotInventBlinkOrHeadMovement()throws Exception {
        JSONObject base=frameDocument("soft","front",new JSONObject().put("blink",.5));
        JSONObject unrelated=frameDocument("soft","front",new JSONObject().put("blink",.5).put("phase",.7).put("rootX",300).put("headTilt",20));
        assertEquals(layer(base).getJSONArray("objects").toString(),layer(unrelated).getJSONArray("objects").toString());assertSamePixels(base,unrelated);
    }
    @Test public void actualSkinAndHairPaletteReachPixelsAndHairOccludesScalp()throws Exception {
        JSONObject s=new JSONObject().put("faceStyle","soft").put("skinColor","#FFBC8063").put("hairColor","#FF173C58"),doc=frameDocument(s,"front",new JSONObject(),1,0);
        Bitmap b=render(doc);try{
            assertEquals("Forehead skin",Color.parseColor("#FFBC8063"),b.getPixel(640,360-13*6));
            assertEquals("Solid native hair covers top scalp",Color.parseColor("#FF173C58"),b.getPixel(640+10*6,360-23*6));
        }finally{b.recycle();}
        assertTrue(index(doc,"hair")>index(doc,"head"));
    }
    @Test public void callerRotationAndVerticalScaleComposeOnceAndStrokeWeightsUndoStaging()throws Exception {
        JSONObject s=new JSONObject().put("faceStyle","angular"),a=frameDocument(s,"front",new JSONObject(),1,0),b=frameDocument(s,"front",new JSONObject(),.8,0),rotated=frameDocument(s,"front",new JSONObject(),1,18);
        assertEquals(bounds(part(a,"head")).height()*.8,bounds(part(b,"head")).height(),.01);
        assertEquals(.62*ZOOM/720,part(a,"head",1).getDouble("width"),1e-12);
        assertDifferentPixels(a,rotated);
        JSONObject staged=SketchDocument.create(new JSONObject().put("sketchId","face_alias")),l=layer(staged);SketchHumanFace.draw(new JungleCelInk(l).at(640,360,1/16d,0),l,"cast",s,new JSONObject(),"front",1);
        JSONObject fill=part(staged,"head"),stroke=part(staged,"head",1);JSONArray original=new JSONArray(stroke.getJSONArray("commands").toString());fill.getJSONArray("commands").getJSONArray(0).put(1,.1);assertEquals(original.toString(),stroke.getJSONArray("commands").toString());
    }
    @Test public void malformedStyleViewOrDestinationRejectBeforeAppendingPaths()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","face_invalid")),l=layer(doc);JungleCelInk d=new JungleCelInk(l);
        assertThrows(IllegalArgumentException.class,()->SketchHumanFace.draw(d,l,"cast",new JSONObject().put("faceStyle","graphic"),new JSONObject(),"front",1));
        assertThrows(IllegalArgumentException.class,()->SketchHumanFace.draw(d,l,"cast",new JSONObject(),new JSONObject(),"quarter",1));
        assertThrows(IllegalArgumentException.class,()->SketchHumanFace.draw(d,new JSONObject(),"cast",new JSONObject(),new JSONObject(),"front",1));
        assertThrows(IllegalArgumentException.class,()->SketchHumanFace.draw(d,l,"cast",new JSONObject(),new JSONObject(),"front",Double.NaN));
        assertEquals(0,l.getJSONArray("objects").length());
    }
    @Test public void expressiveSpeechAndSquintsRenderThroughTheSameNativeFrameFixture()throws Exception {
        for(String style:STYLES)for(String view:VIEWS)for(String expression:new String[]{"speaking","squint"}){
            JSONObject pose=new JSONObject().put("mouth",expression.equals("speaking")?1:.4).put("mouthWide",.5).put("mouthRound",.4)
                .put("mouthTeeth",1).put("mouthTongue",1).put("blink",expression.equals("squint")?.5:0).put("smile",expression.equals("speaking")?1:-1)
                .put("brow",expression.equals("speaking")?1:-1).put("gazeY",expression.equals("speaking")?-1:1);
            JSONObject doc=frameDocument(style,view,pose);SketchDocument.validate(doc);
            Bitmap b=render(doc);try{String folder=System.getProperty("videostudio.face.output");if(folder!=null){File file=new File(folder,style+"-"+view+"-"+expression+".png");assertTrue(file.getParentFile().isDirectory()||file.getParentFile().mkdirs());try(FileOutputStream out=new FileOutputStream(file)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}}finally{b.recycle();}
        }
    }

    /** Reproduces the real Painter staging/lift without requiring unfinished settings integration. */
    private static JSONObject frameDocument(String style,String view,JSONObject pose)throws Exception{return frameDocument(new JSONObject().put("faceStyle",style),view,pose,1,0);}
    private static JSONObject frameDocument(JSONObject settings,String view,JSONObject pose,double vertical,double angle)throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","original_face").put("width",1280).put("height",720).put("frameCount",1).put("fps",30));
        JSONObject layer=layer(doc);JungleCelInk drawing=new JungleCelInk(layer).at(640,360,1/16d,0).at(0,0,1,angle);
        SketchHumanFace.draw(drawing,layer,"cast",settings,pose,view,vertical);
        JSONArray objects=layer.getJSONArray("objects");
        for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);JSONArray commands=o.getJSONArray("commands");
            for(int j=0;j<commands.length();j++){JSONArray c=commands.getJSONArray(j);for(int k=1;k<c.length();k+=2){c.put(k,(640+(c.getDouble(k)*1280-640)*16*ZOOM)/1280);c.put(k+1,(360+(c.getDouble(k+1)*720-360)*16*ZOOM)/720);}}
            o.put("width",o.getDouble("width")*ZOOM);
        }
        return doc;
    }
    private static JSONObject layer(JSONObject doc)throws Exception{return doc.getJSONArray("layers").getJSONObject(0);}
    private static JSONObject part(JSONObject doc,String name)throws Exception{return part(doc,name,0);}
    private static JSONObject part(JSONObject doc,String name,int serial)throws Exception {JSONArray a=layer(doc).getJSONArray("objects");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals("cast_"+name+"_"+serial))return a.getJSONObject(i);return null;}
    private static int index(JSONObject doc,String name)throws Exception {JSONArray a=layer(doc).getJSONArray("objects");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals("cast_"+name+"_0"))return i;return -1;}
    private static Path path(JSONObject o)throws Exception {assertNotNull(o);return SketchFrameRenderer.path(o.getJSONArray("commands"),1280,720);}
    private static RectF bounds(JSONObject o)throws Exception {RectF r=new RectF();path(o).computeBounds(r,true);return r;}
    // Path.computeBounds includes Bezier controls; sample the actual white curve
    // when measuring lid occlusion, rather than mistaking its control for its edge.
    private static RectF curveBounds(JSONObject o)throws Exception {float[] p=path(o).approximate(.005f);RectF r=new RectF(p[1],p[2],p[1],p[2]);for(int i=3;i<p.length;i+=3){r.left=Math.min(r.left,p[i+1]);r.right=Math.max(r.right,p[i+1]);r.top=Math.min(r.top,p[i+2]);r.bottom=Math.max(r.bottom,p[i+2]);}return r;}
    private static RectF band(Path p,float y){Path strip=new Path(),section=new Path();strip.addRect(0,y-.1f,1280,y+.1f,Path.Direction.CW);assertTrue(section.op(p,strip,Path.Op.INTERSECT));RectF r=new RectF();section.computeBounds(r,true);assertFalse(r.isEmpty());return r;}
    private static void inside(Path child,Path parent,String label){Path difference=new Path();assertTrue(difference.op(child,parent,Path.Op.DIFFERENCE));assertTrue(label,difference.isEmpty());}
    private static Bitmap render(JSONObject doc)throws Exception {try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){return renderer.render(0);}}
    private static void assertDifferentPixels(JSONObject a,JSONObject b)throws Exception {Bitmap x=render(a),y=render(b);try{assertFalse("Native facial pixels must respond",x.sameAs(y));}finally{x.recycle();y.recycle();}}
    private static void assertSamePixels(JSONObject a,JSONObject b)throws Exception {Bitmap x=render(a),y=render(b);try{assertTrue("Occluded/unrelated channels must not affect native pixels",x.sameAs(y));}finally{x.recycle();y.recycle();}}
}
