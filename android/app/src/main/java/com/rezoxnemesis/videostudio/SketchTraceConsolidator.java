package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Compact a locally traced image into FEWER genuine editable vector paths.
 *
 * This never reads Gallery/media and never places a source image on the Canvas.
 * It coalesces pre-existing filled, opaque, identical-style, closed path regions
 * into a compound path with multiple M...Z subpaths. Native touch/MCP edits,
 * pixel rendering and undo history remain the same SketchDocument system.
 *
 * Only nearby regions in the same bounded source-order window can coalesce:
 * local paint ordering is mostly preserved, but anti-alias seams may differ.
 * Never treat the resulting vector as a certified pixel-perfect trace without
 * rendering and visually reviewing the before/after frames on the device.
 */
final class SketchTraceConsolidator {
    static final int MAX_WINDOW=96;
    static final int MAX_MERGED_SUBPATHS=32;

    private SketchTraceConsolidator(){}

    static void apply(JSONObject document,JSONObject operation) throws Exception {
        SketchDocument.fields(operation,"type,layerId,window,maxMergeSize,idPrefix");
        String layerId=SketchDocument.id(operation,"layerId");
        JSONObject layer=SketchDocument.layer(document,layerId);
        SketchEditOps.requireUnlocked(layer);
        int window=operation.has("window")
                ?(int)SketchDocument.integer(operation,"window",4,MAX_WINDOW):32;
        int maxMerge=operation.has("maxMergeSize")
                ?(int)SketchDocument.integer(operation,"maxMergeSize",2,MAX_MERGED_SUBPATHS):16;
        String prefix=operation.optString("idPrefix","rr_trace_");
        if(prefix.length()<4||prefix.length()>90||prefix.contains("/")||prefix.contains("\\"))
            throw SketchDocument.bad("A valid owned traced-vector ID prefix is required");
        JSONArray before=layer.getJSONArray("objects");
        if(before.length()==0)
            throw SketchDocument.bad("There are no paths to consolidate");

        JSONArray output=new JSONArray();int merged=0;
        for(int start=0;start<before.length();start+=window){
            int stop=Math.min(before.length(),start+window);
            Map<String,Group> groups=new HashMap<>();
            for(int i=start;i<stop;i++){
                JSONObject candidate=before.getJSONObject(i);
                String key=key(candidate,prefix);
                Group group=key==null?null:groups.get(key);
                if(group!=null&&group.members<maxMerge){
                    JSONArray old=group.leader.getJSONArray("commands");
                    JSONArray next=candidate.getJSONArray("commands");
                    if(old.length()+next.length()<=4096){
                        // A closed subpath must remain disjoint from the next M.
                        for(int k=0;k<next.length();k++)
                            old.put(new JSONArray(next.getJSONArray(k).toString()));
                        group.members++;
                        merged++;
                        continue;
                    }
                }
                JSONObject retained=SketchDocument.copy(candidate);
                output.put(retained);
                if(key!=null)groups.put(key,new Group(retained));
            }
        }
        if(merged==0)throw SketchDocument.bad(
                "No safe identical-style traced paths could be compacted");
        layer.put("objects",output);
    }

    private static String key(JSONObject object,String prefix)throws Exception {
        if(!object.getString("id").startsWith(prefix)
                ||!"path".equals(object.optString("kind"))
                ||!object.optBoolean("fill")
                ||object.has("gradient")||object.has("styleId")||SketchShapeKeys.animated(object)
                ||object.has("brush")||object.has("referenceCrop")
                ||object.optJSONArray("keys")==null
                ||object.getJSONArray("keys").length()>0)return null;
        String color=object.getString("color");
        if(!color.matches("#FF[0-9A-Fa-f]{6}"))return null;
        JSONArray commands=object.getJSONArray("commands");
        if(commands.length()<3
                ||!"M".equals(commands.getJSONArray(0).getString(0))
                ||!"Z".equals(commands.getJSONArray(commands.length()-1).getString(0)))
            return null;
        JSONObject t=object.getJSONObject("transform");
        if(Math.abs(t.getDouble("x"))>1e-9||Math.abs(t.getDouble("y"))>1e-9
                ||Math.abs(t.getDouble("scaleX")-1)>1e-9
                ||Math.abs(t.getDouble("scaleY")-1)>1e-9
                ||Math.abs(t.getDouble("rotation"))>1e-9
                ||Math.abs(t.getDouble("opacity")-1)>1e-9)return null;
        return color+"|"+object.getDouble("width")+"|"
                +object.getInt("frame")+"|"+object.getInt("exposure")
                +"|"+t.getDouble("anchorX")+"|"+t.getDouble("anchorY");
    }

    private static final class Group {
        final JSONObject leader;
        int members=1;
        Group(JSONObject leader){this.leader=leader;}
    }
}
