package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/**
 * Color edits must change real Canvas pixels on every linked exposed cel
 * without rewriting the geometry or original independent fallback pigments.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaletteStylesTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private JSONObject rect(String id,int start,int duration,String pigment,double x)throws Exception{
        return SketchEditOps.completeObject(new JSONObject()
                .put("id",id).put("kind","rect").put("color",pigment)
                .put("frame",start).put("exposure",duration).put("fill",true)
                .put("bounds",new JSONObject().put("x",x).put("y",.12)
                        .put("width",.25).put("height",.26)));
    }
    private JSONObject original()throws Exception{
        JSONObject source=SketchDocument.create(new JSONObject()
                .put("sketchId","linked_palette_sketch").put("width",256).put("height",256)
                .put("frameCount",12));
        JSONArray objects=source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        objects.put(rect("coat_front",0,12,"#FFC83140",.10));
        objects.put(rect("coat_back",6,6,"#FFC83140",.40));
        objects.put(rect("unrelated_blue",0,12,"#FF153AC8",.72));
        SketchDocument.validate(source);
        return source;
    }
    @Test public void styleColorUpdatesEveryLinkedCelInActualCanvasRenderer()throws Exception{
        JSONObject original=original(),next=SketchDocument.copy(original);
        String pristine=SketchDocument.canonical(original);
        SketchEditOps.apply(next,new JSONObject().put("type","palette_upsert")
                .put("styleId","jacket").put("name","Character jacket")
                .put("color","#FFC83140"));
        SketchEditOps.apply(next,new JSONObject().put("type","palette_link_matching")
                .put("styleId","jacket").put("originalColor","#FFC83140"));
        String linked=SketchDocument.canonical(next);
        assertEquals("jacket",SketchDocument.object(next,"coat_front").getString("styleId"));
        assertEquals("jacket",SketchDocument.object(next,"coat_back").getString("styleId"));
        int originalInk=Color.parseColor("#FFC83140");
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,next)){
            Bitmap frame0=renderer.render(0);
            try{
                assertEquals(originalInk,frame0.getPixel(53,54));
                assertEquals(Color.parseColor("#FF153AC8"),frame0.getPixel(205,54));
            }finally{frame0.recycle();}
            SketchEditOps.apply(next,new JSONObject().put("type","palette_upsert")
                    .put("styleId","jacket").put("name","Character jacket")
                    .put("color","#FF30B46A"));
            renderer.updateDocument(context,next);
            for(int frame:new int[]{0,6,9,11}){
                Bitmap colored=renderer.render(frame);
                try{
                    assertEquals("Frame "+frame+" shows current shared color",
                            Color.parseColor("#FF30B46A"),colored.getPixel(53,54));
                    if(frame>=6)assertEquals(Color.parseColor("#FF30B46A"),colored.getPixel(123,54));
                    assertEquals("Unlinked pigments remain pixel-exact",
                            Color.parseColor("#FF153AC8"),colored.getPixel(205,54));
                }finally{colored.recycle();}
            }
        }
        assertEquals("Changing the palette must not mutate the prepared original sketch",
                pristine,SketchDocument.canonical(original));
        assertEquals("Original pigment fallback stays independently present",
                "#FFC83140",SketchDocument.object(next,"coat_front").getString("color"));
        assertNotEquals("Only the shared palette entry changes",linked,SketchDocument.canonical(next));
    }
    @Test public void touchingExactlyOneLinkedObjectWithDirectColorUnlinksOnlyThatObject()throws Exception{
        JSONObject scene=original();
        SketchEditOps.apply(scene,new JSONObject().put("type","palette_upsert").put("styleId","cloth")
                .put("name","Cloth").put("color","#FFFF4455"));
        SketchEditOps.apply(scene,new JSONObject().put("type","palette_link_matching")
                .put("styleId","cloth").put("originalColor","#FFC83140"));
        SketchEditOps.apply(scene,new JSONObject().put("type","update_object").put("objectId","coat_front")
                .put("patch",new JSONObject().put("color","#FF334455")));
        assertFalse("Direct recolor must explicitly opt out of linked style",SketchDocument.object(scene,"coat_front").has("styleId"));
        assertEquals("cloth",SketchDocument.object(scene,"coat_back").getString("styleId"));
        SketchEditOps.apply(scene,new JSONObject().put("type","palette_upsert")
                .put("styleId","cloth").put("name","Cloth").put("color","#FF00AA99"));
        assertEquals("#FF334455",SketchDocument.object(scene,"coat_front").getString("color"));
        SketchEditOps.apply(scene,new JSONObject().put("type","palette_unlink").put("objectId","coat_back"));
        assertEquals("#FF00AA99",SketchDocument.object(scene,"coat_back").getString("color"));
        assertFalse(SketchDocument.object(scene,"coat_back").has("styleId"));
        SketchEditOps.apply(scene,new JSONObject().put("type","palette_delete").put("styleId","cloth"));
        assertEquals(0,scene.getJSONArray("paletteStyles").length());
    }
    @Test public void fiveHundredVectorCelsRecolorViaOneStyleEditAndPreserveGeometry()throws Exception{
        JSONObject original=SketchDocument.create(new JSONObject().put("sketchId","five_hundred_palette")
                .put("width",256).put("height",256).put("frameCount",48));
        JSONArray layers=original.getJSONArray("layers");
        for(int l=0;l<5;l++){
            JSONObject layer=l==0?layers.getJSONObject(0):new JSONObject()
                    .put("id","level_"+l).put("name","Level "+l)
                    .put("visible",true).put("locked",false).put("opacity",1)
                    .put("transform",SketchDocument.transform()).put("keys",new JSONArray())
                    .put("objects",new JSONArray());
            if(l>0)layers.put(layer);
            for(int j=0;j<100;j++){
                double x=(j%10)*.068,y=.02+(j/10)*.088;
                layer.getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject()
                        .put("id","ink_"+l+"_"+j).put("kind","ellipse")
                        .put("frame",0).put("exposure",48).put("fill",true)
                        .put("color","#FF234567").put("bounds",new JSONObject()
                                .put("x",x).put("y",y).put("width",.02).put("height",.03))));
            }
        }
        SketchDocument.validate(original);
        JSONObject working=SketchDocument.copy(original);
        SketchEditOps.apply(working,new JSONObject().put("type","palette_upsert")
                .put("styleId","uniform").put("name","Uniform").put("color","#FF234567"));
        String before=SketchDocument.canonical(original);
        SketchEditOps.apply(working,new JSONObject().put("type","palette_link_matching")
                .put("styleId","uniform").put("originalColor","#FF234567"));
        SketchEditOps.apply(working,new JSONObject().put("type","palette_upsert")
                .put("styleId","uniform").put("name","Uniform").put("color","#FFDDAA55"));
        int linked=0;
        for(int l=0;l<layers.length();l++){
            JSONArray objects=working.getJSONArray("layers").getJSONObject(l).getJSONArray("objects");
            for(int j=0;j<objects.length();j++){
                JSONObject item=objects.getJSONObject(j);
                assertEquals("uniform",item.getString("styleId"));
                assertEquals("#FF234567",item.getString("color"));
                linked++;
            }
        }
        assertEquals(500,linked);
        assertEquals(before,SketchDocument.canonical(original));
        assertEquals(1,working.getJSONArray("paletteStyles").length());
        assertEquals(Color.parseColor("#FFDDAA55"),SketchPaletteStyles.resolvedColor(
                SketchDocument.object(working,"ink_4_99"),SketchPaletteStyles.indexed(working)));
    }
    @Test public void rejectUnknownStylesGradientsAndLockedMatchingLayersWithoutPartialMutation()throws Exception{
        JSONObject working=original();
        String before=SketchDocument.canonical(working);
        try{SketchEditOps.apply(working,new JSONObject().put("type","palette_assign")
                .put("objectId","coat_front").put("styleId","unknown"));fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("style"));}
        assertEquals(before,SketchDocument.canonical(working));
        SketchEditOps.apply(working,new JSONObject().put("type","palette_upsert")
                .put("styleId","test").put("name","Test").put("color","#FFAAEE22"));
        working.getJSONArray("layers").getJSONObject(0).put("locked",true);
        String locked=SketchDocument.canonical(working);
        try{SketchEditOps.apply(working,new JSONObject().put("type","palette_link_matching")
                .put("styleId","test").put("originalColor","#FFC83140"));fail();}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("LOCKED"));}
        assertEquals(locked,SketchDocument.canonical(working));
    }
}
