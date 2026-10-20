package com.rezoxnemesis.videostudio;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A reference-first bridge from exact source-bound raster parts to EDITABLE
 * Bézier silhouette guides. It does NOT falsely convert original photos into
 * complete vector illustration or infer unobserved anatomy.
 *
 * A separate, opt-in bounded edge extension repairs small holes that appear
 * behind independently moving foreground cutouts. Pixels concealed for more
 * than 48 px remain unknown; the receipt advertises synthesized underlay.
 */
final class SketchReferenceStructure {
    static final int MAX_NAMED_PARTS=24;
    static final int MAX_EDGE_EXTEND_PX=48;
    static final int MAX_EXTEND_PIXELS=2_097_152;
    static final int MAX_RING_COUNT=64;

    private SketchReferenceStructure(){}

    /**
     * Create an independently editable, invisible native vector silhouette.
     * Source mask vertices are the only anchors; no imagined eyes, muscles,
     * hands or missing 3D views. Native nodes can be refined later in Studio.
     */
    static JSONObject outline(JSONObject descriptor,JSONArray ring,int ringIndex,
                              int width,int height,int frame,int exposure) throws Exception {
        if(descriptor==null||ring==null||ring.length()<3||ring.length()>64||ringIndex<0||ringIndex>7)
            throw new IllegalArgumentException("Invalid reference-first contour");
        SketchDocument.integer(new JSONObject().put("frame",frame),"frame",0,SketchDocument.MAX_FRAMES-1);
        SketchDocument.integer(new JSONObject().put("exposure",exposure),"exposure",1,SketchDocument.MAX_FRAMES);
        List<OmegaBezierFitter.Point> samples=new ArrayList<>(ring.length());
        for(int i=0;i<ring.length();i++){
            JSONArray point=ring.getJSONArray(i);
            if(point.length()!=2)throw new IllegalArgumentException("Contour vertices require [x,y]");
            samples.add(new OmegaBezierFitter.Point(point.getDouble(0),point.getDouble(1)));
        }
        OmegaBezierFitter.StrokeFit fit=OmegaBezierFitter.fitStroke(samples,width,height,.75,true,.8);
        JSONArray commands=new JSONArray();
        for(OmegaBezierFitter.Command command:OmegaBezierFitter.nativeCommands(fit.contour)){
            JSONArray step=new JSONArray().put(command.kind);
            for(double value:command.values())step.put(value);
            commands.put(step);
        }
        SketchDocument.validateCommands(commands);
        String stable=descriptor.getString("layerId")+"/"+descriptor.getString("maskId")
                +"/"+descriptor.getString("objectId")+"/ring-"+ringIndex;
        String id="outline_"+UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8));
        return SketchEditOps.completeObject(new JSONObject()
                .put("id",id).put("kind","path")
                .put("commands",commands).put("fill",false)
                .put("color","#FF13B0EE").put("width",.0025)
                .put("frame",frame).put("exposure",exposure)
                .put("transform",SketchDocument.transform().put("opacity",0)));
    }

    /**
     * Fill only the nearest 'radius' pixels below already extracted source
     * objects, using real colors on the closest visible source background.
     * This is a small-motion edge patch, NEVER hidden-scene reconstruction.
     *
     * The input must be fully opaque: translucent image parts could reveal
     * this underlay and otherwise spoil exact stationary-pixel fidelity.
     */
    static int[] edgeExtend(int[] source,BitSet background,int width,int height,int radius) {
        if(source==null||background==null||width<1||height<1
                ||(long)width*height>MAX_EXTEND_PIXELS||source.length!=width*height
                ||radius<0||radius>MAX_EDGE_EXTEND_PX)
            throw new IllegalArgumentException("Invalid bounded reference underlay");
        final int length=source.length;
        int[] output=new int[length];
        if(radius==0){
            for(int pixel=background.nextSetBit(0);pixel>=0&&pixel<length;
                    pixel=background.nextSetBit(pixel+1))output[pixel]=source[pixel];
            return output;
        }
        byte[] distance=new byte[length];Arrays.fill(distance,(byte)-1);
        int[] queue=new int[length];int tail=0;
        for(int i=0;i<length;i++){
            if((source[i]>>>24)!=255)
                throw new IllegalArgumentException("Background edge extension requires an opaque source");
            if(background.get(i)){
                distance[i]=0;output[i]=source[i];queue[tail++]=i;
            }
        }
        for(int head=0;head<tail;head++){
            final int i=queue[head],d=distance[i]&255;
            if(d>=radius)continue;
            final int x=i%width,y=i/width;
            final int[] candidates={x>0?i-1:-1,x<width-1?i+1:-1,
                    y>0?i-width:-1,y<height-1?i+width:-1};
            for(int next:candidates){
                if(next<0||distance[next]!=-1)continue;
                distance[next]=(byte)(d+1);output[next]=output[i];queue[tail++]=next;
            }
        }
        return output;
    }

    static JSONArray contoursForPart(JSONObject descriptor,JSONArray rings,
                                     int width,int height,int frame,int exposure)throws Exception{
        JSONArray result=new JSONArray();
        SketchSourceMask.rings(rings,8);
        for(int i=0;i<rings.length();i++)
            result.put(outline(descriptor,rings.getJSONArray(i),i,width,height,frame,exposure));
        return result;
    }
}
