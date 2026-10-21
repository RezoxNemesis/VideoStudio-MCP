package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import android.graphics.Bitmap;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Isolated sidecar contract; deliberately bypasses the parent body/settings integration. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAnatomyHeadTest {
    private static final String IRIS="#FF4B5150";

    private static SketchHumanFigure.Skeleton skeleton(String view,JSONObject controls)throws Exception {
        SketchHumanFigure.Skeleton p=new SketchHumanFigure.Skeleton();
        p.head=new double[]{0,-345,0};p.neck=new double[]{0,-321,0};
        p.camera=new SketchHumanFigure.Camera(view,true);p.controls=controls;
        p.lean=controls.optDouble("bodyLean",0);p.headTilt=controls.optDouble("headTilt",0);
        return p;
    }
    private static JSONObject settings()throws Exception {
        return new JSONObject().put("skinColor","#FFF1EEE8").put("hairColor","#FF555150").put("hairStyle","short");
    }
    private static JSONObject layer()throws Exception {
        return new JSONObject().put("id","head_test").put("objects",new JSONArray());
    }
    private static void draw(JSONObject layer,String actor,JSONObject s,SketchHumanFigure.Skeleton p)throws Exception {
        Class<?> type;
        try{type=Class.forName("com.rezoxnemesis.videostudio.SketchAnatomyHead");}
        catch(ClassNotFoundException e){throw new AssertionError("Native anatomy head sidecar is missing",e);}
        try{type.getDeclaredMethod("draw",JSONObject.class,String.class,JSONObject.class,SketchHumanFigure.Skeleton.class).invoke(null,layer,actor,s,p);}
        catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    private static JSONObject draw(String view,JSONObject controls)throws Exception {
        JSONObject l=layer();draw(l,"actor",settings(),skeleton(view,controls));return l;
    }
    private static int fills(JSONObject l,String color)throws Exception {
        int n=0;JSONArray objects=l.getJSONArray("objects");
        for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(o.getBoolean("fill")&&color.equals(o.getString("color")))n++;}
        return n;
    }

    @Test public void appendsOnlyNativeEditablePathsAndNeverRenamesExistingObjects()throws Exception {
        for(String view:new String[]{"front","left","right","back","high","low","three_quarter_left","three_quarter_right"}){
            JSONObject l=layer(),existing=new JSONObject().put("id","parent_owned").put("sentinel",42);
            l.getJSONArray("objects").put(existing);String before=existing.toString();
            draw(l,"actor",settings(),skeleton(view,new JSONObject()));
            assertSame(existing,l.getJSONArray("objects").getJSONObject(0));assertEquals(before,existing.toString());
            JSONArray objects=l.getJSONArray("objects");assertTrue(objects.length()>25);assertTrue("Bounded head object count",objects.length()<260);
            Set<String> ids=new HashSet<>();
            for(int i=1;i<objects.length();i++){
                JSONObject o=objects.getJSONObject(i);assertTrue(o.getString("id").matches("actor_anatomy_head_[0-9]+"));assertTrue(ids.add(o.getString("id")));
                assertEquals("path",o.getString("kind"));assertFalse(o.has("image"));assertTrue(o.getDouble("width")>0);
                JSONArray commands=o.getJSONArray("commands");assertTrue(commands.length()>1);
                for(int j=0;j<commands.length();j++){
                    JSONArray c=commands.getJSONArray(j);assertTrue(Set.of("M","L","Q","C","Z").contains(c.getString(0)));
                    for(int k=1;k<c.length();k++)assertTrue("Staged coordinates are finite and unclipped",Double.isFinite(c.getDouble(k))&&c.getDouble(k)>0&&c.getDouble(k)<1);
                }
            }
        }
    }

    @Test public void frontHasTwoIrisesProfileHasOneAndBackHidesExpression()throws Exception {
        assertEquals(2,fills(draw("front",new JSONObject()),IRIS));
        for(String v:new String[]{"left","right"})assertEquals(v,1,fills(draw(v,new JSONObject()),IRIS));
        for(String v:new String[]{"three_quarter_left","three_quarter_right"})assertEquals(v,2,fills(draw(v,new JSONObject()),IRIS));
        JSONObject back=draw("back",new JSONObject());assertEquals(0,fills(back,IRIS));
        assertEquals(back.toString(),draw("back",new JSONObject().put("blink",1).put("gazeX",1).put("mouth",1).put("smile",1).put("brow",1)).toString());
    }

    @Test public void fullBlinkHidesIrisesAndPoseChangesAreDeterministic()throws Exception {
        for(String view:new String[]{"front","left","right","high","low"}){
            JSONObject open=draw(view,new JSONObject()),closed=draw(view,new JSONObject().put("blink",1));
            assertTrue(fills(open,IRIS)>0);assertEquals(0,fills(closed,IRIS));assertNotEquals(open.toString(),closed.toString());
            for(String key:new String[]{"gazeX","gazeY","mouth","smile","brow","headTilt"}){
                JSONObject pose=new JSONObject().put(key,key.equals("headTilt")?15:.8);
                assertNotEquals(view+" honors "+key,open.toString(),draw(view,pose).toString());
                assertEquals(draw(view,pose).toString(),draw(view,pose).toString());
            }
        }
    }

    @Test public void usesSkeletonCameraEvenWhenSettingsDisagreeAndLongHairChangesGeometry()throws Exception {
        JSONObject l=layer();draw(l,"actor",settings().put("view","back"),skeleton("front",new JSONObject()));
        assertEquals(draw("front",new JSONObject()).toString(),l.toString());
        JSONObject longHair=layer();draw(longHair,"actor",settings().put("hairStyle","long"),skeleton("front",new JSONObject()));
        assertNotEquals(l.toString(),longHair.toString());
    }

    @Test public void sideViewsKeepNearFringeLocksInsteadOfDroppingTheWholeForeheadFringe()throws Exception {
        // Unique authored lock fill, distinct from cap/curtain/skin/eye fills.
        // The reference problem was loss of geometry, not merely a changed PNG.
        JSONObject monochrome=settings().put("skinColor","#FFFFFFFF").put("hairColor","#FF000000").put("hairStyle","long");
        for(String view:new String[]{"left","right"}){
            JSONObject actual=layer();draw(actual,"actor",monochrome,skeleton(view,new JSONObject()));
            assertTrue("Near forehead locks must remain visible in "+view,fills(actual,"#FF333333")>0);
        }
        JSONObject rear=layer();draw(rear,"actor",monochrome,skeleton("back",new JSONObject()));
        assertEquals("Rear view must not show the front locks through the skull",0,fills(rear,"#FF333333"));
    }

    @Test public void sideFringeHasCranialDepthRatherThanCollapsingIntoFlatStrips()throws Exception {
        JSONObject monochrome=settings().put("skinColor","#FFFFFFFF").put("hairColor","#FF000000").put("hairStyle","long");
        for(String view:new String[]{"left","right"}){
            JSONObject actual=layer();draw(actual,"actor",monochrome,skeleton(view,new JSONObject()));
            JSONArray paths=actual.getJSONArray("objects");int inspected=0;
            for(int i=0;i<paths.length();i++){
                JSONObject path=paths.getJSONObject(i);if(!path.getBoolean("fill")||!"#FF333333".equals(path.getString("color")))continue;
                double left=Double.POSITIVE_INFINITY,right=Double.NEGATIVE_INFINITY;JSONArray commands=path.getJSONArray("commands");
                for(int j=0;j<commands.length();j++){JSONArray c=commands.getJSONArray(j);for(int k=1;k<c.length();k+=2){left=Math.min(left,c.getDouble(k));right=Math.max(right,c.getDouble(k));}}
                assertTrue("A curved forehead lock must span cranial depth in "+view,(right-left)*1280*16>5);inspected++;
            }
            assertTrue("Inspect real filled forehead locks",inspected>0);
        }
    }

    @Test public void translationIsAppliedExactlyOnceBeforeParentNormalization()throws Exception {
        SketchHumanFigure.Skeleton p=skeleton("front",new JSONObject());JSONObject a=layer(),b=layer();draw(a,"actor",settings(),p);
        p.head=new double[]{16,-313,0};draw(b,"actor",settings(),p);
        JSONArray aa=a.getJSONArray("objects"),bb=b.getJSONArray("objects");assertEquals(aa.length(),bb.length());
        for(int i=0;i<aa.length();i++){
            JSONArray ac=aa.getJSONObject(i).getJSONArray("commands"),bc=bb.getJSONObject(i).getJSONArray("commands");assertEquals(ac.length(),bc.length());
            for(int j=0;j<ac.length();j++)for(int k=1;k<ac.getJSONArray(j).length();k++){
                double delta=(k%2==1?16d/1280:32d/720)/16;
                assertEquals(delta,bc.getJSONArray(j).getDouble(k)-ac.getJSONArray(j).getDouble(k),1e-12);
            }
        }
    }

    @Test public void nativeRendererCanDrawIsolatedHeadsAcrossEightViews()throws Exception {
        for(String view:new String[]{"front","left","right","back","high","low","three_quarter_left","three_quarter_right"}){
            JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","head_native").put("width",512).put("height",512).put("frameCount",1).put("background","#FFF8F6F0"));
            JSONObject l=doc.getJSONArray("layers").getJSONObject(0);
            SketchHumanFigure.Skeleton p=skeleton(view,new JSONObject());draw(l,"actor",settings(),p);
            double[] centre=p.camera.point(p.head);JSONArray objects=l.getJSONArray("objects");
            for(int i=0;i<objects.length();i++){
                JSONObject o=objects.getJSONObject(i);JSONArray commands=new JSONArray(o.getJSONArray("commands").toString());o.put("commands",commands);
                for(int j=0;j<commands.length();j++){
                    JSONArray c=commands.getJSONArray(j);
                    for(int k=1;k<c.length();k+=2){
                        double x=(c.getDouble(k)*1280-640)*16-centre[0],y=(c.getDouble(k+1)*720-360)*16-centre[1];
                        c.put(k,(256+x*6.5)/512);c.put(k+1,(232+y*6.5)/512);
                    }
                }
                o.put("width",o.getDouble("width")*720*6.5/512);
            }
            SketchDocument.validate(doc);
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
                Bitmap bitmap=renderer.render(0);
                try{
                    int count=0;for(int y=0;y<512;y+=4)for(int x=0;x<512;x+=4)if(bitmap.getPixel(x,y)!=0xFFF8F6F0)count++;
                    assertTrue("Native head has visible ink in "+view,count>500);
                    if(view.equals("front"))assertNotEquals("The closed hair cap must contain its crown, not just fringe outlines",0xFFF8F6F0,bitmap.getPixel(256,90));
                    if(view.equals("left")||view.equals("right"))assertNotEquals("Profile mandible must retain its native skin fill",0xFFF8F6F0,bitmap.getPixel(view.equals("right")?308:204,362));
                    String directory=System.getProperty("anatomy.head.evidence");
                    if(directory!=null){File output=new File(directory);assertTrue(output.isDirectory()||output.mkdirs());
                        try(FileOutputStream out=new FileOutputStream(new File(output,view+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
                }finally{bitmap.recycle();}
            }
        }
    }
}
