package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Native editable source-guided precision pen; no raster paste. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchPrecisionContourTest {
    private static JSONObject doc()throws Exception{
        return SketchDocument.create(new JSONObject()
                .put("sketchId","precision_test").put("name","Reference contour test")
                .put("width",512).put("height",512).put("fps",24).put("frameCount",48));
    }
    private static JSONArray authoredStroke()throws Exception{
        JSONArray out=new JSONArray();
        for(int i=0;i<=30;i++){
            double t=i/30d;
            out.put(new JSONArray().put(.12+.72*t)
                    .put(.52+.22*Math.sin(Math.PI*2*t))
                    .put(.35+.6*Math.sin(Math.PI*t)));
        }
        return out;
    }
    private static JSONObject op(boolean pressure)throws Exception{
        return new JSONObject().put("type","precision_contour")
                .put("objectId",pressure?"ink_silhouette":"ink_bezier")
                .put("layerId","layer_1")
                .put("points",authoredStroke())
                .put("width",.008).put("color","#FFAA3377")
                .put("tension",.9).put("tolerancePx",2)
                .put("pressureOutline",pressure)
                .put("frame",4).put("exposure",35);
    }

    @Test public void stylusStrokeIsAnEditableCubicCurveAtTheCorrectExposure()throws Exception{
        JSONObject source=doc();
        SketchEditOps.apply(source,op(false));
        SketchDocument.validate(source);
        JSONObject item=SketchDocument.object(source,"ink_bezier");
        assertEquals("path",item.getString("kind"));
        JSONArray commands=item.getJSONArray("commands");
        assertTrue(commands.length()>=2);
        assertEquals("M",commands.getJSONArray(0).getString(0));
        assertEquals("C",commands.getJSONArray(1).getString(0));
        assertFalse(item.getBoolean("fill"));
        assertEquals(4,item.getInt("frame"));
        assertEquals(35,item.getInt("exposure"));
        assertEquals(0,item.getJSONArray("keys").length());
        assertFalse("No pasted bitmap may become the drawing",item.has("image"));
        assertFalse(item.has("reference"));
        assertTrue(SketchDocument.validateCommands(commands)>0);
    }

    @Test public void pressureMakesAnEditableFilledSilhouetteInsteadOfRasterPaste()throws Exception{
        JSONObject source=doc();
        SketchEditOps.apply(source,op(true));
        SketchDocument.validate(source);
        JSONObject object=SketchDocument.object(source,"ink_silhouette");
        JSONArray commands=object.getJSONArray("commands");
        assertEquals("path",object.getString("kind"));
        assertTrue(object.getBoolean("fill"));
        assertEquals("M",commands.getJSONArray(0).getString(0));
        assertEquals("Z",commands.getJSONArray(commands.length()-1).getString(0));
        assertTrue(commands.length()>15);
        assertEquals(1,source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
    }

    @Test public void precisionDrawingCanBecomeVersionedMasterAndChangeLinkedFrames()throws Exception{
        JSONObject source=doc();
        SketchEditOps.apply(source,op(false));
        SketchMasterAssets.apply(source,new JSONObject().put("type","master_create")
                .put("masterId","original_ink").put("name","Original authored contour")
                .put("sourceObjectId","ink_bezier"));
        SketchMasterAssets.apply(source,new JSONObject().put("type","master_instance")
                .put("masterId","original_ink").put("layerId","layer_1")
                .put("objectId","linked_ink").put("frame",10).put("exposure",25)
                .put("transform",SketchDocument.transform().put("x",.07))
                .put("keys",new JSONArray().put(
                        new JSONObject().put("frame",20).put("transform",
                                SketchDocument.transform().put("x",.19))
                                .put("interpolation","smooth"))));
        String beforeKeys=SketchDocument.canonical(
                SketchDocument.object(source,"linked_ink").getJSONArray("keys"));
        SketchMasterAssets.apply(source,new JSONObject().put("type","master_patch")
                .put("masterId","original_ink").put("expectedVersion",1)
                .put("patch",new JSONObject().put("color","#FF44BB88")));
        assertEquals("#FF44BB88",SketchDocument.object(source,"ink_bezier").getString("color"));
        assertEquals("#FF44BB88",SketchDocument.object(source,"linked_ink").getString("color"));
        assertEquals(beforeKeys,SketchDocument.canonical(
                SketchDocument.object(source,"linked_ink").getJSONArray("keys")));
        SketchDocument.validate(source);
    }

    @Test public void lockedLayerAndInvalidReferenceCannotWrite()throws Exception{
        JSONObject d=doc();JSONObject invalid=op(false).put("referenceFile","source.png");
        String before=SketchDocument.canonical(d);
        try{SketchEditOps.apply(d,invalid);fail();}
        catch(IllegalArgumentException expected){}
        assertEquals(before,SketchDocument.canonical(d));
        d.getJSONArray("layers").getJSONObject(0).put("locked",true);
        String frozen=SketchDocument.canonical(d);
        try{SketchEditOps.apply(d,op(false));fail();}
        catch(IllegalStateException expected){}
        assertEquals(frozen,SketchDocument.canonical(d));
        JSONArray bad=new JSONArray().put(new JSONArray().put("NaN").put(.5))
                .put(new JSONArray().put(.5).put(.6));
        try{SketchEditOps.apply(doc(),new JSONObject(op(false).toString()).put("points",bad));fail();}
        catch(Exception expected){}
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void realNativeCanvasDrawsFittedInkAtAuthoredFrameOnly()throws Exception{
        JSONObject drawing=doc();
        int[] before;
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(
                RuntimeEnvironment.getApplication(),drawing)){
            Bitmap blank=renderer.render(4);
            before=new int[blank.getWidth()*blank.getHeight()];
            blank.getPixels(before,0,blank.getWidth(),0,0,
                    blank.getWidth(),blank.getHeight());
        }
        SketchEditOps.apply(drawing,op(true));
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(
                RuntimeEnvironment.getApplication(),drawing)){
            Bitmap hidden=renderer.render(3);
            Bitmap visible=renderer.render(4);
            int width=visible.getWidth(),height=visible.getHeight();
            int[] hiddenPixels=new int[width*height],inkPixels=new int[width*height];
            hidden.getPixels(hiddenPixels,0,width,0,0,width,height);
            visible.getPixels(inkPixels,0,width,0,0,width,height);
            assertArrayEquals(before,hiddenPixels);
            int changed=0;
            for(int i=0;i<before.length;i++)if(before[i]!=inkPixels[i])changed++;
            assertTrue("Native SVG-like ink must create actual pixels",changed>60);
        }
    }
}
