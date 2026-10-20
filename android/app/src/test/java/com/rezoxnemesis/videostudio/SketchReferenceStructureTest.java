package com.rezoxnemesis.videostudio;

import java.util.BitSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native source image stays authoritative; only contour guides are editable. */
public final class SketchReferenceStructureTest {
    private static JSONArray ring(double left,double top,double right,double bottom){
        return new JSONArray().put(new JSONArray().put(left).put(top))
                .put(new JSONArray().put(right).put(top))
                .put(new JSONArray().put(right).put(bottom))
                .put(new JSONArray().put(left).put(bottom));
    }

    @Test public void userMaskMakesGenuineEditableNativeBezierContour()throws Exception{
        JSONObject descriptor=new JSONObject().put("maskId","front_head")
                .put("layerId","mask_front_head").put("objectId","pixels_front_head");
        JSONArray shape=ring(.21,.24,.62,.58);
        JSONObject contour=SketchReferenceStructure.outline(descriptor,shape,0,720,1280,0,48);
        assertEquals("path",contour.getString("kind"));
        assertFalse(contour.getBoolean("fill"));
        assertEquals(0,contour.getJSONObject("transform").getDouble("opacity"),0);
        assertEquals(48,contour.getInt("exposure"));
        JSONArray commands=contour.getJSONArray("commands");
        assertEquals("M",commands.getJSONArray(0).getString(0));
        assertEquals("Z",commands.getJSONArray(commands.length()-1).getString(0));
        assertTrue("Must be editable cubic geometry, not rasterized pixels",
                commands.toString().contains("\"C\""));
        assertTrue(commands.length()>2);
        assertEquals(SketchDocument.validateCommands(commands)>0,true);
        assertEquals(contour.getString("id"),SketchReferenceStructure
                .outline(descriptor,shape,0,720,1280,0,48).getString("id"));
        JSONArray guides=SketchReferenceStructure.contoursForPart(descriptor,
                new JSONArray().put(shape).put(ring(.32,.34,.42,.44)),720,1280,0,48);
        assertEquals(2,guides.length());
        assertNotEquals(guides.getJSONObject(0).getString("id"),
                guides.getJSONObject(1).getString("id"));
    }

    @Test public void opaqueUnderlayFillsOnlyBoundedBackgroundEdgeHoles(){
        int w=64,h=64;int[] source=new int[w*h];BitSet background=new BitSet(w*h);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            source[y*w+x]=x<32?0xff213745:0xff445566;
            if(x<24||x>=40||y<24||y>=40)background.set(y*w+x);
        }
        int[] none=SketchReferenceStructure.edgeExtend(source,background,w,h,0);
        int[] extended=SketchReferenceStructure.edgeExtend(source,background,w,h,4);
        assertEquals(0,none[26*w+26]);
        assertNotEquals(0,extended[26*w+26]);
        assertEquals(0,extended[31*w+31]); // >4 pixels from available source edge
        assertEquals(source[30*w+5],extended[30*w+5]);
        assertEquals(source[30*w+59],extended[30*w+59]);
        // Original source is read-only and is never re-painted in place.
        assertEquals(0xff213745,source[26*w+26]);
    }

    @Test public void transparentOriginalsAndOversizeInpaintingFailClosed(){
        int[] pixels=new int[16*16];java.util.Arrays.fill(pixels,0xff112233);
        pixels[0]=0x80112233;
        BitSet background=new BitSet(256);background.set(16,256);
        assertThrows(IllegalArgumentException.class,
                ()->SketchReferenceStructure.edgeExtend(pixels,background,16,16,1));
        assertThrows(IllegalArgumentException.class,
                ()->SketchReferenceStructure.edgeExtend(pixels,background,16,16,49));
        assertThrows(IllegalArgumentException.class,
                ()->SketchReferenceStructure.edgeExtend(new int[1],background,2048,2048,4));
    }
}
