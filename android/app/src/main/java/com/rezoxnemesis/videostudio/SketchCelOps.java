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
            for(int i=0;i<objects.length();i++){JSONObject original=objects.getJSONObject(i);if(!SketchFrameRenderer.exposed(original,from))continue;copies.put(SketchDocument.copy(original).put("id",prefix+"_"+copies.length()).put("frame",to).put("exposure",1).put("keys",new JSONArray()).put("transform",SketchDocument.transformAt(original,from)));}
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
                JSONObject layer=layers.getJSONObject(i);shiftKeys(layer,frame,insert);JSONArray objects=layer.getJSONArray("objects");
                for(int j=objects.length()-1;j>=0;j--){JSONObject o=objects.getJSONObject(j);int start=o.getInt("frame"),end=start+o.getInt("exposure");
                    if(insert){if(start>=frame)o.put("frame",start+1);else if(end>frame)o.put("exposure",o.getInt("exposure")+1);}
                    else{if(start==frame&&o.getInt("exposure")==1){objects.remove(j);continue;}if(start>frame)o.put("frame",start-1);else if(end>frame)o.put("exposure",o.getInt("exposure")-1);}
                    shiftKeys(o,frame,insert);
                }
            }
            doc.put("frameCount",count+(insert?1:-1));
        }
    }
    private static void shiftKeys(JSONObject item,int frame,boolean insert) throws Exception {
        JSONArray keys=item.getJSONArray("keys");for(int i=keys.length()-1;i>=0;i--){JSONObject key=keys.getJSONObject(i);int time=key.getInt("frame");if(!insert&&time==frame)keys.remove(i);else if(time>=frame)key.put("frame",time+(insert?1:-1));}
    }
    private static void clear(JSONObject layer,int frame) throws Exception {
        JSONArray objects=layer.getJSONArray("objects");for(int i=objects.length()-1;i>=0;i--){JSONObject o=objects.getJSONObject(i);if(!SketchFrameRenderer.exposed(o,frame))continue;int start=o.getInt("frame"),end=start+o.getInt("exposure");
            if(start<frame){o.put("exposure",frame-start);if(end>frame+1)objects.put(SketchDocument.copy(o).put("id",UUID.randomUUID().toString()).put("frame",frame+1).put("exposure",end-frame-1));}
            else if(end>frame+1)o.put("frame",frame+1).put("exposure",end-frame-1);else objects.remove(i);
        }
    }
}
