package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Atomic flipbook operations. No object-count-dependent UI batch splitting. */
public final class SketchCelOps {
    private SketchCelOps() {}
    public static void apply(JSONObject doc,JSONObject op) throws Exception {
        String type=op.getString("type");
        if("duplicate_cel".equals(type)) {
            SketchDocument.fields(op,"type,layerId,fromFrame,toFrame,idPrefix");
            int from=(int)SketchDocument.integer(op,"fromFrame",0,doc.getInt("frameCount")-1),to=(int)SketchDocument.integer(op,"toFrame",0,SketchDocument.MAX_FRAMES-1);
            if(from==to)throw SketchDocument.bad("Choose a different destination cel");String prefix=SketchDocument.id(op,"idPrefix");if(prefix.length()>72)throw SketchDocument.bad("Cel ID prefix too long");
            JSONObject layer=SketchDocument.layer(doc,SketchDocument.id(op,"layerId"));SketchEditOps.requireUnlocked(layer);JSONArray copies=new JSONArray(),objects=layer.getJSONArray("objects");
            for(int i=0;i<objects.length();i++){JSONObject original=objects.getJSONObject(i);if(!SketchFrameRenderer.exposed(original,from))continue;copies.put(sampled(original,from).put("id",prefix+"_"+copies.length()).put("frame",to).put("exposure",1).put("keys",new JSONArray()).put("transform",SketchDocument.transformAt(original,from)));}
            doc.put("frameCount",Math.max(doc.getInt("frameCount"),to+1));clear(layer,to);
            for(int i=0;i<copies.length();i++)layer.getJSONArray("objects").put(copies.getJSONObject(i));
        }else if("clear_cel".equals(type)) {
            SketchDocument.fields(op,"type,layerId,frame");int frame=(int)SketchDocument.integer(op,"frame",0,doc.getInt("frameCount")-1);
            JSONObject layer=SketchDocument.layer(doc,SketchDocument.id(op,"layerId"));SketchEditOps.requireUnlocked(layer);clear(layer,frame);
        }else{
            SketchDocument.fields(op,"type,frame");boolean insert="insert_frame".equals(type);int count=doc.getInt("frameCount");
            if(insert&&count==SketchDocument.MAX_FRAMES||!insert&&count==1)throw SketchDocument.bad("Frame count outside flipbook budget");
            int frame=(int)SketchDocument.integer(op,"frame",0,insert?count:count-1);JSONArray layers=doc.getJSONArray("layers");
            for(int i=0;i<layers.length();i++){
                JSONObject layer=layers.getJSONObject(i);shiftKeys(layer,frame,insert);
                layer.put("objects",shiftObjects(layer.getJSONArray("objects"),frame,insert));
            }
            doc.put("frameCount",count+(insert?1:-1));
        }
    }
    private static JSONObject sampled(JSONObject object,int frame)throws Exception {
        String kind=object.getString("kind");
        if("hand".equals(kind))return SketchHandAnimation.sampled(object,frame);
        if("human".equals(kind))return SketchHumanAnimation.sampled(object,frame);
        return SketchShapeKeys.sampled(object,frame);
    }
    private static JSONObject retained(JSONObject object)throws Exception {
        String kind=object.getString("kind");return "hand".equals(kind)||"human".equals(kind)?object.getJSONObject(kind):null;
    }
    /** Preserve the programme clock at firstSourceFrame before the caller changes frame/exposure.
     * Poses, easing and speech remain in their original clock; renderFrame still wins for frozen cels.
     * Fully held offsets normalize to the endpoint; a later range edit starts from that normalized clock.
     * Hand/Human localFrame and validation share this optional sourceFrameOffset payload field. */
    static void retainSourceClock(JSONObject object,int firstSourceFrame)throws Exception {
        JSONObject payload=retained(object);if(payload==null||payload.has("renderFrame"))return;
        long sourceOffset=(long)payload.optInt("sourceFrameOffset",0)+firstSourceFrame-object.getInt("frame");
        int offset=(int)Math.max(-SketchDocument.MAX_FRAMES,Math.min(payload.getInt("durationFrames")-1L,sourceOffset));
        if(offset==0)payload.remove("sourceFrameOffset");else payload.put("sourceFrameOffset",offset);
    }
    private static JSONArray shiftObjects(JSONArray original,int frame,boolean insert)throws Exception {
        JSONArray result=new JSONArray();
        for(int i=0;i<original.length();i++){
            JSONObject object=original.getJSONObject(i),payload=retained(object);
            int start=object.getInt("frame"),end=start+object.getInt("exposure");
            boolean live=payload!=null&&!payload.has("renderFrame");
            if(live&&start<frame&&frame<end&&insert){
                JSONObject tail=SketchDocument.copy(object);retainSourceClock(tail,frame);
                tail.put("id",UUID.randomUUID().toString()).put("frame",frame+1).put("exposure",end-frame);
                JSONObject held=sampled(object,frame-1).put("id",UUID.randomUUID().toString())
                        .put("frame",frame).put("exposure",1).put("keys",new JSONArray())
                        .put("transform",SketchDocument.transformAt(object,frame-1));
                object.put("exposure",frame-start);result.put(object).put(held).put(tail);
            }else if(live&&!insert&&start<=frame&&frame<end){
                if(end>frame+1){
                    JSONObject tail=SketchDocument.copy(object);retainSourceClock(tail,frame+1);
                    tail.put("frame",frame).put("exposure",end-frame-1);
                    if(start<frame)tail.put("id",UUID.randomUUID().toString());
                    if(start<frame)result.put(object.put("exposure",frame-start));result.put(tail);
                }else if(start<frame)result.put(object.put("exposure",frame-start));
            }else{
                if(insert){if(start>=frame)object.put("frame",start+1);else if(end>frame)object.put("exposure",end-start+1);}
                else{if(start==frame&&end==frame+1)continue;if(start>frame)object.put("frame",start-1);else if(end>frame)object.put("exposure",end-start-1);}
                result.put(object);
            }
        }
        for(int i=0;i<result.length();i++)shiftKeys(result.getJSONObject(i),frame,insert);
        return result;
    }
    private static void shiftKeys(JSONObject item,int frame,boolean insert) throws Exception {
        SketchPaintMesh.shiftKeys(item,frame,insert);
        for(String field:new String[]{"keys","shapeKeys"}){
            JSONArray keys=item.optJSONArray(field);if(keys==null)continue;
            for(int i=keys.length()-1;i>=0;i--){JSONObject key=keys.getJSONObject(i);int time=key.getInt("frame");if(!insert&&time==frame)keys.remove(i);else if(time>=frame)key.put("frame",time+(insert?1:-1));}
        }
    }
    private static void clear(JSONObject layer,int frame) throws Exception {
        JSONArray objects=layer.getJSONArray("objects"),result=new JSONArray();for(int i=0;i<objects.length();i++){
            JSONObject o=objects.getJSONObject(i);if(!SketchFrameRenderer.exposed(o,frame)){result.put(o);continue;}
            int start=o.getInt("frame"),end=start+o.getInt("exposure");
            if(end>frame+1){
                JSONObject tail=SketchDocument.copy(o);retainSourceClock(tail,frame+1);
                tail.put("frame",frame+1).put("exposure",end-frame-1);
                if(start<frame)tail.put("id",UUID.randomUUID().toString());
                if(start<frame)result.put(o.put("exposure",frame-start));result.put(tail);
            }else if(start<frame)result.put(o.put("exposure",frame-start));
        }
        layer.put("objects",result);
    }
}
